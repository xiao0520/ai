"""在授权检索结果上补齐上下文并执行统一 Token 预算。

职责：
  - ContextSelector — 按父组和相邻链补齐片段，在 Token 预算内选择证据。
  - 扩展策略：先补充所有重排种子，再按排名展开父组兄弟和邻居链。
  - 最终按优先级（种子 > 兄弟 > 邻居）在 Token 预算内选择。

关键约束：
  - 补齐仅查询 Qdrant（不走 Java 复核），最终结果需经 Java validate_candidates。
  - 优先级排序保证确定性（种子 rank > 兄弟距离 > 邻居 hop > ordinal）。
  - Token 预算 = scope.context_budget_tokens，片段数上限 = max_context_chunks。
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from time import perf_counter
from typing import Any

from langchain_core.documents import Document
from langchain_core.runnables import Runnable, RunnableLambda
from pydantic import ValidationError
from qdrant_client import AsyncQdrantClient, models

from ai_service.contracts.rag_types import IndexSnapshot
from ai_service.contracts.retrieval import RagExecutionScope, SourceLocation, SourceRef
from ai_service.rag.chunking import SnapshotTokenizer
from ai_service.rag.langchain_documents import source_location_from_document

# ============================================================================
# 异常与数据类
# ============================================================================


class ContextSelectionError(RuntimeError):
    """上下文来源、索引快照或 Qdrant 载荷不一致。"""


@dataclass(frozen=True, slots=True)
class ContextSelectionInput:
    """已完成 Java 复核和分组重排的种子片段。"""

    documents: tuple[Document, ...]


@dataclass(frozen=True, slots=True)
class ContextSelectionResult:
    """尚待 Java 最终复核的预算内上下文。"""

    documents: tuple[Document, ...]
    duration_ms: int
    input_count: int
    token_count: int


@dataclass(frozen=True, slots=True)
class _DocumentInfo:
    """从受控 Document 中恢复的不可变上下文定位信息。"""

    document: Document
    location: SourceLocation
    snapshot: IndexSnapshot
    ordinal: int
    token_count: int
    parent_chunk_id: str
    previous_chunk_id: str | None
    next_chunk_id: str | None


@dataclass(frozen=True, slots=True)
class _PrioritizedDocument:
    """用于稳定证据选择的优先级；种子始终先于补齐片段。"""

    priority: tuple[int, int, int, int]
    info: _DocumentInfo


# ============================================================================
# 上下文选择器
# ============================================================================


class ContextSelector:
    """按父组和相邻链补齐片段，并在同一快照 Token 预算内选择证据。"""

    def __init__(
        self,
        scope: RagExecutionScope,
        qdrant_client: AsyncQdrantClient,
        *,
        timeout_seconds: int,
    ) -> None:
        """绑定本轮授权快照和 Qdrant 超时，补齐时仅访问快照内索引。"""
        if timeout_seconds < 1:
            raise ValueError("timeout_seconds 必须为正数")
        self._scope = scope
        self._qdrant_client = qdrant_client
        self._timeout_seconds = timeout_seconds
        self._snapshots = {
            (snapshot.knowledge_id, snapshot.index_version): snapshot
            for snapshot in scope.index_snapshots
        }

    def as_runnable(self) -> Runnable[ContextSelectionInput, ContextSelectionResult]:
        """返回可直接接入 LCEL 主链的上下文节点。"""
        return RunnableLambda(self._invoke)

    async def _invoke(self, request: ContextSelectionInput) -> ContextSelectionResult:
        """在当前授权快照内补齐父组与相邻片段，再按优先级和预算选择证据。"""
        started_at = perf_counter()
        if self._scope.expires_at <= datetime.now(UTC):
            raise ContextSelectionError("AUTHORIZATION")
        seeds = tuple(self._info(document) for document in request.documents)
        candidates: dict[tuple[str, ...], _PrioritizedDocument] = {}
        for rank, seed in enumerate(seeds, start=1):
            self._put(candidates, seed, (0, rank, 0, seed.ordinal))

        # 先保证所有重排种子进入候选，再按排名为高相关种子补齐结构父组和相邻链。
        expansion_limit = max(
            self._scope.retrieval_snapshot.max_context_chunks * 4,
            len(seeds),
        )
        for rank, seed in enumerate(seeds, start=1):
            if len(candidates) >= expansion_limit:
                break
            for sibling in await self._fetch_parent_group(seed, expansion_limit - len(candidates)):
                distance = abs(sibling.ordinal - seed.ordinal)
                self._put(candidates, sibling, (1, rank, distance, sibling.ordinal))

        # 相邻链逐跳扩展，始终使用来源过滤条件限制在原文档的同一批次。
        frontier = seeds
        for hop in range(1, self._scope.retrieval_snapshot.neighbor_window + 1):
            if not frontier or len(candidates) >= expansion_limit:
                break
            requested: dict[tuple[str, ...], tuple[_DocumentInfo, set[str]]] = {}
            for item in frontier:
                ids = {
                    chunk_id
                    for chunk_id in (item.previous_chunk_id, item.next_chunk_id)
                    if chunk_id is not None
                }
                if not ids:
                    continue
                key = self._document_key(item.location.source)
                current = requested.get(key)
                if current is None:
                    requested[key] = (item, ids)
                else:
                    current[1].update(ids)
            next_frontier: list[_DocumentInfo] = []
            for seed, chunk_ids in requested.values():
                fetched = await self._fetch_chunk_ids(
                    seed,
                    chunk_ids,
                    expansion_limit - len(candidates),
                )
                for item in fetched:
                    next_frontier.append(item)
                    seed_rank = self._nearest_seed_rank(item, seeds)
                    self._put(candidates, item, (2, seed_rank, hop, item.ordinal))
            frontier = tuple(next_frontier)

        # 扩展结果先于返回前按种子、兄弟、邻居的优先级统一裁剪。
        selected: list[Document] = []
        used_tokens = 0
        max_chunks = self._scope.retrieval_snapshot.max_context_chunks
        token_budget = self._scope.context_budget_tokens
        for candidate in sorted(candidates.values(), key=lambda item: item.priority):
            if len(selected) >= max_chunks:
                break
            if used_tokens + candidate.info.token_count > token_budget:
                continue
            document = candidate.info.document.model_copy(deep=True)
            document.metadata["contextPriority"] = list(candidate.priority)
            document.metadata["contextTokenCount"] = candidate.info.token_count
            selected.append(document)
            used_tokens += candidate.info.token_count

        return ContextSelectionResult(
            documents=tuple(selected),
            duration_ms=max(0, round((perf_counter() - started_at) * 1000)),
            input_count=len(candidates),
            token_count=used_tokens,
        )

    async def _fetch_parent_group(
        self,
        seed: _DocumentInfo,
        remaining: int,
    ) -> tuple[_DocumentInfo, ...]:
        """查询与种子共享 parentChunkId 的同文档兄弟片段，按 ordinal 排序。

        父组代表同一结构块（如一个大段落切出的多个切片），补齐有助于模型
        获得完整上下文而非孤立片段。"""
        if remaining <= 0:
            return ()
        point_filter = models.Filter(
            must=[
                *self._source_conditions(seed.location.source),
                models.FieldCondition(
                    key="metadata.parentChunkId",
                    match=models.MatchValue(value=seed.parent_chunk_id),
                ),
            ]
        )
        return await self._scroll(seed, point_filter, remaining)

    async def _fetch_chunk_ids(
        self,
        seed: _DocumentInfo,
        chunk_ids: set[str],
        remaining: int,
    ) -> tuple[_DocumentInfo, ...]:
        """按 chunkId 精确拉取相邻片段，不会退化为全文档扫描。

        仅查询同文档同 generation 的点，chunk_id 集来自种子的 previous/next 链。"""
        if remaining <= 0 or not chunk_ids:
            return ()
        point_filter = models.Filter(
            must=[
                *self._source_conditions(seed.location.source),
                models.FieldCondition(
                    key="metadata.chunkId",
                    match=models.MatchAny(any=sorted(chunk_ids)),
                ),
            ]
        )
        return await self._scroll(seed, point_filter, min(remaining, len(chunk_ids)))

    async def _scroll(
        self,
        seed: _DocumentInfo,
        point_filter: models.Filter,
        limit: int,
    ) -> tuple[_DocumentInfo, ...]:
        """封装 Qdrant scroll 调用，绑定种子所在 collection 和超时。

        返回的片段已按 ordinal 排序，确保结构兄弟的顺序与原始文档一致。"""
        try:
            records, _ = await self._qdrant_client.scroll(
                collection_name=seed.snapshot.collection_name,
                scroll_filter=point_filter,
                limit=max(1, min(limit, 100)),
                with_payload=True,
                with_vectors=False,
                timeout=self._timeout_seconds,
            )
        except Exception as error:
            raise ContextSelectionError("VECTOR_UNAVAILABLE") from error
        values = tuple(self._info_from_record(record, seed) for record in records)
        return tuple(sorted(values, key=lambda item: item.ordinal))

    def _info_from_record(
        self,
        record: models.Record,
        seed: _DocumentInfo,
    ) -> _DocumentInfo:
        """从 Qdrant Record 恢复 _DocumentInfo，同时验证补齐片段与种子同源。

        补齐片段不是独立召回结果，其 retrievalScores 和 retrievalRanks 清空，
        避免将结构兄弟的分数混淆到 RRF 和 Rerank 管道中。"""
        payload = record.payload or {}
        page_content = payload.get("page_content")
        raw_metadata = payload.get("metadata")
        if not isinstance(page_content, str) or not page_content or not isinstance(
            raw_metadata, dict
        ):
            raise ContextSelectionError("INDEX_MISMATCH")
        document = Document(
            id=str(record.id),
            page_content=page_content,
            metadata=dict(raw_metadata),
        )
        # 补齐片段不是独立召回结果，阶段分数保持为空而不是继承种子分数。
        document.metadata["retrievalScores"] = {}
        document.metadata["retrievalRanks"] = {}
        info = self._info(document)
        if self._document_key(info.location.source) != self._document_key(
            seed.location.source
        ):
            raise ContextSelectionError("INDEX_MISMATCH")
        return info

    def _info(self, document: Document) -> _DocumentInfo:
        """从 Document metadata 恢复完整来源信息，并用快照 Tokenizer 重算 Token 数校验一致性。

        这一校验防止 Qdrant payload 被意外修改后导致上下文 Token 预算计算偏差。"""
        try:
            location = source_location_from_document(document)
        except (ValueError, ValidationError) as error:
            raise ContextSelectionError("INDEX_MISMATCH") from error
        source = location.source
        snapshot = self._snapshots.get((source.knowledge_id, source.index_version))
        if snapshot is None:
            raise ContextSelectionError("INDEX_MISMATCH")
        ordinal = self._integer(document.metadata.get("chunkOrdinal"), minimum=0)
        stored_tokens = self._integer(document.metadata.get("tokenCount"), minimum=1)
        parent_chunk_id = document.metadata.get("parentChunkId")
        previous_chunk_id = document.metadata.get("previousChunkId")
        next_chunk_id = document.metadata.get("nextChunkId")
        tokenizer_revision = document.metadata.get("tokenizerRevision")
        if (
            not isinstance(parent_chunk_id, str)
            or not parent_chunk_id
            or not self._optional_label(previous_chunk_id)
            or not self._optional_label(next_chunk_id)
            or tokenizer_revision != snapshot.tokenizer_revision
        ):
            raise ContextSelectionError("INDEX_MISMATCH")
        try:
            actual_tokens = SnapshotTokenizer(snapshot.tokenizer_revision).count(
                document.page_content
            )
        except Exception as error:
            raise ContextSelectionError("INDEX_MISMATCH") from error
        if actual_tokens != stored_tokens:
            raise ContextSelectionError("INDEX_MISMATCH")
        return _DocumentInfo(
            document=document,
            location=location,
            snapshot=snapshot,
            ordinal=ordinal,
            token_count=actual_tokens,
            parent_chunk_id=parent_chunk_id,
            previous_chunk_id=previous_chunk_id,
            next_chunk_id=next_chunk_id,
        )

    @staticmethod
    def _source_conditions(source: SourceRef) -> list[models.FieldCondition]:
        """构造 Qdrant 过滤条件，限定查询范围为同文档同版本同 generation。"""
        return [
            ContextSelector._match("metadata.knowledgeId", source.knowledge_id),
            ContextSelector._match("metadata.indexVersion", source.index_version),
            ContextSelector._match("metadata.documentId", source.document_id),
            ContextSelector._match(
                "metadata.documentVersionId", source.document_version_id
            ),
            ContextSelector._match("metadata.generationId", source.generation_id),
        ]

    @staticmethod
    def _match(field: str, value: str) -> models.FieldCondition:
        """简写：按字段精确匹配的单条件过滤。"""
        return models.FieldCondition(key=field, match=models.MatchValue(value=value))

    @staticmethod
    def _integer(value: Any, *, minimum: int) -> int:
        """严格校验 metadata 中的整数字段，拒绝 bool（Python 中 bool 是 int 子类）。"""
        if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
            raise ContextSelectionError("INDEX_MISMATCH")
        return value

    @staticmethod
    def _optional_label(value: object) -> bool:
        """Previous/next 链 ID 可以为 None 或非空字符串，不接受其他类型。"""
        return value is None or (isinstance(value, str) and bool(value))

    @staticmethod
    def _source_key(source: SourceRef) -> tuple[str, ...]:
        """片段唯一标识的稳定元组键，用于集合去重和字典查找。"""
        return (
            source.knowledge_id,
            source.index_version,
            source.document_id,
            source.document_version_id,
            source.generation_id,
            source.chunk_id,
            source.content_hash,
        )

    @staticmethod
    def _document_key(source: SourceRef) -> tuple[str, ...]:
        """文档级键（不含 chunk_id/content_hash），用于判断两个片段是否来自同一文档。"""
        return (
            source.knowledge_id,
            source.index_version,
            source.document_id,
            source.document_version_id,
            source.generation_id,
        )

    @classmethod
    def _put(
        cls,
        target: dict[tuple[str, ...], _PrioritizedDocument],
        info: _DocumentInfo,
        priority: tuple[int, int, int, int],
    ) -> None:
        """仅当候选不重复或优先级更高时插入；重复片段保留最低优先级值。"""
        key = cls._source_key(info.location.source)
        current = target.get(key)
        if current is None or priority < current.priority:
            target[key] = _PrioritizedDocument(priority, info)

    @classmethod
    def _nearest_seed_rank(
        cls,
        item: _DocumentInfo,
        seeds: tuple[_DocumentInfo, ...],
    ) -> int:
        """找同文档中 ordinal 最接近的种子的排名，用于相邻链补齐片段的分组优先级。"""
        same_document = [
            (rank, seed)
            for rank, seed in enumerate(seeds, start=1)
            if cls._document_key(seed.location.source)
            == cls._document_key(item.location.source)
        ]
        if not same_document:
            raise ContextSelectionError("INDEX_MISMATCH")
        return min(same_document, key=lambda pair: abs(pair[1].ordinal - item.ordinal))[0]