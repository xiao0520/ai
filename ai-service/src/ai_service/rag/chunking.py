"""按 IndexSnapshot 执行结构优先、Token 限长的确定性切片。

职责：
  - SnapshotTokenizer — 把快照 revision 映射到固定 BPE 编码。
  - StructureAwareChunker — 按段落/代码/表格行结构切块，优先在自然断点分割。
  - 每块附带上块/下块的 chunk_id 链，供上下文补齐阶段展开相邻块。

关键约束：
  - 切片规则由 splitter_revision 和 tokenizer_revision 双重冻结。
  - 超长块（不可在自然断点分割时）强制在 Token 上限处截断。
  - chunk_id 同时绑定文档版本、切片配置、位置和 content_hash；仅相同输入得到相同标识。
"""

from __future__ import annotations

import hashlib
from bisect import bisect_left
from dataclasses import dataclass, replace
from uuid import NAMESPACE_URL, uuid5

import tiktoken
from tiktoken import Encoding

from ai_service.contracts.knowledge_kafka import KnowledgeDocumentTask, KnowledgeJobCommand
from ai_service.contracts.retrieval import (
    SourceLocation,
    SourceRef,
    TextRangeLocator,
)
from ai_service.rag.models import (
    KnowledgeChunk,
    KnowledgeProcessingError,
    ParsedBlock,
    ParsedDocument,
    ParsedLocator,
)

_SUPPORTED_SPLITTER_REVISION = "recursive-token-v1"
_SUPPORTED_TOKENIZERS = {"cl100k-base-v1": "cl100k_base"}
_PREFERRED_ENDINGS = ("\n\n", "\n", "。", "！", "？", "；", ". ", " ")


# ============================================================================
# 内部数据类
# ============================================================================


@dataclass(frozen=True, slots=True)
class _TokenBoundary:
    """BPE 分词边界在字符偏移上的映射点。"""
    token_index: int
    char_offset: int


@dataclass(frozen=True, slots=True)
class _BodySlice:
    """切片后的正文区间，包含原始偏移用于生成 SourceLocation。"""
    text: str
    start_offset: int
    end_offset: int


# ============================================================================
# 快照绑定分词器
# ============================================================================


class SnapshotTokenizer:
    """把快照 revision 映射到固定 BPE 编码，不按模型名猜测分词器。"""

    def __init__(self, revision: str) -> None:
        encoding_name = _SUPPORTED_TOKENIZERS.get(revision)
        if encoding_name is None:
            raise KnowledgeProcessingError("INDEX_MISMATCH")
        try:
            self.encoding: Encoding = tiktoken.get_encoding(encoding_name)
        except Exception as error:
            raise KnowledgeProcessingError("INDEX_MISMATCH") from error
        self.revision = revision

    def count(self, text: str) -> int:
        """返回固定编码下的精确 Token 数，不调用外部模型。"""
        return len(self.encoding.encode_ordinary(text))

    def truncate(self, text: str, max_tokens: int) -> str:
        """只在完整 Unicode 字符边界截断，避免产生替换字符。"""
        if max_tokens <= 0:
            return ""
        boundaries = self.boundaries(text)
        eligible = [item for item in boundaries if item.token_index <= max_tokens]
        if not eligible:
            return ""
        return text[: eligible[-1].char_offset]

    def boundaries(self, text: str) -> tuple[_TokenBoundary, ...]:
        """把 BPE token 边界映射回 Unicode 码点偏移。"""
        tokens = self.encoding.encode_ordinary(text)
        raw = text.encode("utf-8")
        byte_to_char: dict[int, int] = {0: 0}
        byte_offset = 0
        for char_offset, character in enumerate(text, start=1):
            byte_offset += len(character.encode("utf-8"))
            byte_to_char[byte_offset] = char_offset
        boundaries = [_TokenBoundary(0, 0)]
        token_bytes = bytearray()
        for token_index, token in enumerate(tokens, start=1):
            token_bytes.extend(self.encoding.decode_single_token_bytes(token))
            boundary_char_offset = byte_to_char.get(len(token_bytes))
            if boundary_char_offset is not None:
                boundaries.append(_TokenBoundary(token_index, boundary_char_offset))
        if bytes(token_bytes) != raw or boundaries[-1] != _TokenBoundary(len(tokens), len(text)):
            raise KnowledgeProcessingError("PARSE_FAILED")
        return tuple(boundaries)


# ============================================================================
# 结构感知切片器
# ============================================================================


class StructureAwareChunker:
    """保持标题、段落、代码块、表格行和页码边界，再切分超长结构块。"""

    def split(
        self, command: KnowledgeJobCommand, document: ParsedDocument
    ) -> tuple[KnowledgeChunk, ...]:
        """按命令快照生成稳定 chunkId、批次 pointId 和完整来源位置。"""
        if not isinstance(command.task, KnowledgeDocumentTask):
            raise KnowledgeProcessingError("INDEX_MISMATCH")
        snapshot = command.index_snapshot
        processing = command.document_processing_snapshot
        if processing is None:
            raise KnowledgeProcessingError("INDEX_MISMATCH")
        if snapshot.splitter_revision != _SUPPORTED_SPLITTER_REVISION:
            raise KnowledgeProcessingError("INDEX_MISMATCH")
        input_limits = [processing.text_embedding.max_input_tokens]
        if processing.multimodal_embedding is not None:
            input_limits.append(processing.multimodal_embedding.max_input_tokens)
        if snapshot.chunk_size_tokens > min(input_limits):
            raise KnowledgeProcessingError("INDEX_MISMATCH")
        tokenizer = SnapshotTokenizer(snapshot.tokenizer_revision)
        chunks: list[KnowledgeChunk] = []
        for block in document.blocks:
            prefix = self._prefix(document, block, tokenizer, snapshot.chunk_size_tokens)
            prefix_tokens = tokenizer.count(prefix)
            body_limit = snapshot.chunk_size_tokens - prefix_tokens
            if body_limit < 1:
                raise KnowledgeProcessingError("INDEX_MISMATCH")
            body_overlap = min(snapshot.chunk_overlap_tokens, max(0, body_limit - 1))
            slices = self._split_body(block.text, tokenizer, body_limit, body_overlap)
            for segment_index, body_slice in enumerate(slices):
                final_text = prefix + body_slice.text
                token_count = tokenizer.count(final_text)
                if token_count > snapshot.chunk_size_tokens:
                    raise KnowledgeProcessingError("INDEX_MISMATCH")
                content_hash = hashlib.sha256(final_text.encode("utf-8")).hexdigest()
                locator = self._segment_locator(
                    block.locator, body_slice.start_offset, body_slice.end_offset
                )
                chunk_id = self._chunk_id(command, block, segment_index, locator, content_hash)
                # chunk_id 跨重试稳定；point_id 额外绑定 generation，隔离重建批次。
                point_id = str(
                    uuid5(
                        NAMESPACE_URL,
                        f"mjn-ai:{command.knowledge_id}:{snapshot.index_version}:"
                        f"{command.generation_id}:{chunk_id}",
                    )
                )
                source_ref = SourceRef.model_validate(
                    {
                        "knowledgeId": command.knowledge_id,
                        "indexVersion": snapshot.index_version,
                        "documentId": command.task.document_id,
                        "documentVersionId": command.task.document_version_id,
                        "generationId": command.generation_id,
                        "chunkId": chunk_id,
                        "contentHash": content_hash,
                    }
                )
                location = SourceLocation.model_validate(
                    {
                        "source": source_ref,
                        "title": document.title,
                        "titlePath": list(block.title_path),
                        "locator": locator,
                    }
                )
                chunks.append(
                    KnowledgeChunk(
                        chunk_id=chunk_id,
                        point_id=point_id,
                        ordinal=len(chunks),
                        text=final_text,
                        token_count=token_count,
                        content_hash=content_hash,
                        parent_chunk_id=self._parent_chunk_id(command, block),
                        previous_chunk_id=None,
                        next_chunk_id=None,
                        location=location,
                        parser_revision=snapshot.parser_revision,
                        splitter_revision=snapshot.splitter_revision,
                        tokenizer_revision=snapshot.tokenizer_revision,
                        modality=block.modality,
                        media_id=block.media_id,
                        media_hash=block.media_hash,
                    )
                )
                if len(chunks) > 100_000:
                    raise KnowledgeProcessingError("BUDGET_EXCEEDED")
        if not chunks:
            raise KnowledgeProcessingError("PARSE_FAILED")
        return tuple(
            replace(
                chunk,
                previous_chunk_id=chunks[index - 1].chunk_id if index > 0 else None,
                next_chunk_id=chunks[index + 1].chunk_id if index + 1 < len(chunks) else None,
            )
            for index, chunk in enumerate(chunks)
        )

    def _prefix(
        self,
        document: ParsedDocument,
        block: ParsedBlock,
        tokenizer: SnapshotTokenizer,
        chunk_size: int,
    ) -> str:
        """为每个切片生成前置上下文（文档标题、章节路径、表头），最多占 chunk_size 的 1/4。

        前置信息不参与断点计算，但帮助下游 Embedding 模型理解切片在整个文档中的位置。"""
        if chunk_size < 8:
            return ""
        parts = [f"文档：{document.title}"]
        if block.title_path:
            parts.append("章节：" + " > ".join(block.title_path))
        if block.context_text:
            parts.append("表头：" + block.context_text)
        raw = "\n".join(parts)
        prefix_budget = min(128, max(4, chunk_size // 4))
        shortened = tokenizer.truncate(raw, prefix_budget).rstrip()
        prefix = shortened + "\n\n" if shortened else ""
        while prefix and tokenizer.count(prefix) >= chunk_size:
            prefix_budget -= 1
            shortened = tokenizer.truncate(raw, prefix_budget).rstrip()
            prefix = shortened + "\n\n" if shortened else ""
        return prefix

    def _split_body(
        self,
        text: str,
        tokenizer: SnapshotTokenizer,
        limit: int,
        overlap: int,
    ) -> tuple[_BodySlice, ...]:
        """在 Token 边界上按 limit 和 overlap 切分正文，优先在自然断点截断。

        使用二分查找（bisect_left）加速 Token 边界定位，避免逐字符扫描大文本。"""
        boundaries = tokenizer.boundaries(text)
        if boundaries[-1].token_index <= limit:
            trimmed = self._trim(text, 0, len(text))
            return (trimmed,) if trimmed is not None else ()
        token_indexes = [boundary.token_index for boundary in boundaries]
        slices: list[_BodySlice] = []
        start_position = 0
        while start_position < len(boundaries) - 1:
            start = boundaries[start_position]
            max_token = start.token_index + limit
            end_position = bisect_left(token_indexes, max_token + 1) - 1
            end_position = max(start_position + 1, end_position)
            end_position = self._prefer_separator(
                text, boundaries, start_position, end_position, limit
            )
            end = boundaries[end_position]
            trimmed = self._trim(text, start.char_offset, end.char_offset)
            if trimmed is not None:
                slices.append(trimmed)
            if end_position >= len(boundaries) - 1:
                break
            # 重叠窗口仍须前进至少一个 Token，避免极端断点导致循环不终止。
            target = max(start.token_index + 1, end.token_index - overlap)
            next_position = bisect_left(token_indexes, target)
            next_position = min(end_position, next_position)
            if next_position <= start_position:
                next_position = start_position + 1
            start_position = next_position
        return tuple(slices)

    def _prefer_separator(
        self,
        text: str,
        boundaries: tuple[_TokenBoundary, ...],
        start_position: int,
        end_position: int,
        limit: int,
    ) -> int:
        """在候选区间后半段寻找自然断点（段落、换行、句号等），找不到则落在最大位置。

        自然断点优先级：\n\n > \n > 。 > ！ > ？ > ； > .  > 空格。
        仅在超过最小 Token 阈值后才允许截断，防止切出过短碎片。"""
        minimum_token = boundaries[start_position].token_index + max(1, limit // 2)
        candidates = [
            position
            for position in range(start_position + 1, end_position + 1)
            if boundaries[position].token_index >= minimum_token
        ]
        for separator in _PREFERRED_ENDINGS:
            for position in reversed(candidates):
                if text[: boundaries[position].char_offset].endswith(separator):
                    return position
        return end_position

    def _trim(self, text: str, start: int, end: int) -> _BodySlice | None:
        """去除首尾空白后返回区间；全空白区间返回 None 表示该切片无效。"""
        while start < end and text[start].isspace():
            start += 1
        while end > start and text[end - 1].isspace():
            end -= 1
        if start >= end:
            return None
        return _BodySlice(text[start:end], start, end)

    def _segment_locator(self, locator: ParsedLocator, start: int, end: int) -> ParsedLocator:
        """为切割后的文本段计算在原始文档中的绝对偏移。

        TEXT_RANGE 类定位器需要累加段内偏移；PDF、Word、表格与幻灯片定位器保持不变。"""
        if isinstance(locator, TextRangeLocator):
            return TextRangeLocator.model_validate(
                {
                    "kind": "TEXT_RANGE",
                    "startOffset": locator.start_offset + start,
                    "endOffset": locator.start_offset + end,
                }
            )
        return locator

    def _chunk_id(
        self,
        command: KnowledgeJobCommand,
        block: ParsedBlock,
        segment_index: int,
        locator: ParsedLocator,
        content_hash: str,
    ) -> str:
        """生成完全确定性的 chunk ID——相同输入永远产出相同 SHA-256 摘要。

        种子串联：document_version_id \x1f config_hash \x1f parser \x1f splitter \x1f
        tokenizer \x1f block_ordinal \x1f segment_index \x1f locator_json \x1f content_hash。"""
        task = command.task
        if not isinstance(task, KnowledgeDocumentTask):
            raise KnowledgeProcessingError("INDEX_MISMATCH")
        snapshot = command.index_snapshot
        seed = "\x1f".join(
            (
                task.document_version_id,
                snapshot.config_hash,
                snapshot.parser_revision,
                snapshot.splitter_revision,
                snapshot.tokenizer_revision,
                str(block.ordinal),
                str(segment_index),
                locator.model_dump_json(by_alias=True),
                content_hash,
            )
        )
        return hashlib.sha256(seed.encode("utf-8")).hexdigest()

    def _parent_chunk_id(self, command: KnowledgeJobCommand, block: ParsedBlock) -> str:
        """父 chunk ID 标识同一文档中同章节块的所有片段，不依赖 segment_index。

        用于上下文补齐阶段按结构组召回兄弟片段。"""
        task = command.task
        if not isinstance(task, KnowledgeDocumentTask):
            raise KnowledgeProcessingError("INDEX_MISMATCH")
        seed = "\x1f".join(
            (
                task.document_version_id,
                command.index_snapshot.config_hash,
                "/".join(block.title_path),
                block.locator.kind,
                str(block.ordinal),
            )
        )
        return hashlib.sha256(seed.encode("utf-8")).hexdigest()