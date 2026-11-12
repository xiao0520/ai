"""项目知识片段与 LangChain Document 之间的受控转换。

职责：
  - knowledge_chunk_to_document — 将项目 KnowledgeChunk 转为 LangChain Document。
  - knowledge_chunks_to_documents — 批量转换，按原始顺序保持与向量清单的对应关系。
  - source_location_from_document — 从 LangChain Document 恢复并校验 SourceLocation。
  - LangChainDocumentError — 转换过程中缺失或损坏来源信息的异常。

关键约束：
  - metadata 中的 location/chunkId/contentHash 三者必须一致，不一致时拒绝。
  - 转换保持原始顺序，避免框架层改变清单与向量的对应关系。
  - Document.id 使用 point_id 而非 chunk_id，以确保与 Qdrant point 一一对应。
"""

from __future__ import annotations

from collections.abc import Iterable

from langchain_core.documents import Document
from pydantic import ValidationError

from ai_service.contracts.retrieval import SourceLocation
from ai_service.rag.models import KnowledgeChunk

# ============================================================================
# 转换异常
# ============================================================================


class LangChainDocumentError(ValueError):
    """LangChain 文档缺少可验证来源信息。"""


# ============================================================================
# 转换函数
# ============================================================================


def knowledge_chunk_to_document(chunk: KnowledgeChunk) -> Document:
    """保留稳定标识和完整来源，将项目片段转换为框架文档。"""
    return Document(
        id=chunk.point_id,
        page_content=chunk.text,
        metadata={
            "chunkId": chunk.chunk_id,
            "contentHash": chunk.content_hash,
            "parentChunkId": chunk.parent_chunk_id,
            "previousChunkId": chunk.previous_chunk_id,
            "nextChunkId": chunk.next_chunk_id,
            "chunkOrdinal": chunk.ordinal,
            "tokenCount": chunk.token_count,
            "parserRevision": chunk.parser_revision,
            "splitterRevision": chunk.splitter_revision,
            "tokenizerRevision": chunk.tokenizer_revision,
            "modality": chunk.modality,
            "mediaId": chunk.media_id,
            "mediaHash": chunk.media_hash,
            "location": chunk.location.model_dump(by_alias=True, mode="json"),
        },
    )


def knowledge_chunks_to_documents(chunks: Iterable[KnowledgeChunk]) -> tuple[Document, ...]:
    """按原顺序转换片段，避免框架层改变清单与向量的对应关系。"""
    return tuple(knowledge_chunk_to_document(chunk) for chunk in chunks)


def source_location_from_document(document: Document) -> SourceLocation:
    """从框架文档恢复并校验完整来源，拒绝任意 metadata 直接进入授权链。

    校验规则：
      1. metadata 中的 location 必须能反序列化为 SourceLocation。
      2. location.source.chunk_id 必须与 metadata 中的 chunkId 一致。
      3. location.source.content_hash 必须与 metadata 中的 contentHash 一致。
      任何不匹配视为 IndexSnapshot 与 Qdrant 数据不一致（INDEX_MISMATCH）。
    """
    raw_location = document.metadata.get("location")
    try:
        location = SourceLocation.model_validate(raw_location)
    except ValidationError as error:
        raise LangChainDocumentError("INDEX_MISMATCH") from error
    chunk_id = document.metadata.get("chunkId")
    content_hash = document.metadata.get("contentHash")
    if location.source.chunk_id != chunk_id or location.source.content_hash != content_hash:
        raise LangChainDocumentError("INDEX_MISMATCH")
    return location