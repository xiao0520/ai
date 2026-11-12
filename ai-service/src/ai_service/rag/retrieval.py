"""绑定 Java 授权快照的 LangChain 混合检索器。

职责：
  - AuthorizedHybridRetriever — 按请求级授权成员过滤 Qdrant，执行三路并行召回。
  - 三路召回：Dense 文本向量、Dense 图片向量（可选）、BM25 稀疏向量。
  - 各路结果经 RRF 融合、去重、排序后返回 rerank_top_k 条候选。

关键约束：
  - 仅查询 Java 授权快照中的成员（通过 page_manifest 分页读取）。
  - scope.expires_at 过期时立即拒绝检索。
  - manifest 分页游标必须由 Java 签发，空清单绝不退化为无过滤查询。
"""

from __future__ import annotations

import asyncio
from collections.abc import Coroutine
from dataclasses import dataclass
from datetime import UTC, datetime
from time import perf_counter
from typing import Literal

from langchain_core.callbacks import (
    AsyncCallbackManagerForRetrieverRun,
    CallbackManagerForRetrieverRun,
)
from langchain_core.documents import Document
from langchain_core.retrievers import BaseRetriever
from pydantic import ConfigDict, Field, PrivateAttr, ValidationError
from qdrant_client import AsyncQdrantClient, models

from ai_service.contracts.rag_types import EmbeddingProfile, IndexSnapshot
from ai_service.contracts.retrieval import (
    KnowledgeRetrievalSettings,
    ManifestMember,
    ManifestPageRequest,
    RagExecutionScope,
    SourceLocation,
)
from ai_service.infra.knowledge_scope_client import KnowledgeScopeClient
from ai_service.providers.embedding_protocol import EmbeddingBatch, EmbeddingProviderFactory
from ai_service.providers.langchain_adapters import ProfileBoundEmbeddings
from ai_service.providers.metering import ExecutionUsageTracker
from ai_service.rag.fusion import ReciprocalRankFusion
from ai_service.rag.langchain_documents import source_location_from_document
from ai_service.rag.sparse_encoding import ChineseBm25SparseEmbeddings

# ============================================================================
# 类型别名
# ============================================================================


type RetrievalRoute = Literal["dense_text", "dense_vision", "sparse"]


# ============================================================================
# 异常与数据类
# ============================================================================


class AuthorizedRetrievalError(RuntimeError):
    """授权快照、清单或 Qdrant payload 不一致。"""


@dataclass(frozen=True, slots=True)
class _ScoredCandidate:
    """单路召回结果；RRF 阶段稍后消费各路排名。"""

    document: Document
    route: RetrievalRoute
    score: float
    rank: int


@dataclass(frozen=True, slots=True)
class RetrievalStageMetric:
    """一次检索阶段的聚合耗时和候选数量。"""

    stage: Literal["DENSE", "SPARSE", "RRF"]
    duration_ms: int
    input_count: int
    output_count: int


# ============================================================================
# 授权混合检索器
# ============================================================================


class AuthorizedHybridRetriever(BaseRetriever):
    """按请求级授权成员过滤 Qdrant，并保留每条召回路线的原始诊断。"""

    model_config = ConfigDict(arbitrary_types_allowed=True)

    request_id: str = Field(min_length=6, max_length=128)
    scope: RagExecutionScope
    scope_client: KnowledgeScopeClient
    embedding_providers: EmbeddingProviderFactory
    qdrant_client: AsyncQdrantClient
    timeout_seconds: int = Field(default=30, ge=1, le=300)
    manifest_page_size: int = Field(default=1000, ge=1, le=1000)
    max_concurrency: int = Field(default=4, ge=1, le=32)
    usage_tracker: ExecutionUsageTracker | None = Field(default=None, exclude=True)
    _embedding_batches: list[tuple[EmbeddingProfile, EmbeddingBatch]] = PrivateAttr(
        default_factory=list
    )
    _route_metrics: list[tuple[RetrievalRoute, int, int]] = PrivateAttr(default_factory=list)
    _fusion_metrics: list[RetrievalStageMetric] = PrivateAttr(default_factory=list)

    def _get_relevant_documents(
        self,
        query: str,
        *,
        run_manager: CallbackManagerForRetrieverRun,
    ) -> list[Document]:
        """服务主链必须使用原生异步检索，防止同步网络请求阻塞事件循环。"""
        del query, run_manager
        raise RuntimeError("AuthorizedHybridRetriever 仅支持异步调用")

    async def _aget_relevant_documents(
        self,
        query: str,
        *,
        run_manager: AsyncCallbackManagerForRetrieverRun,
    ) -> list[Document]:
        """分页读取授权成员后按每个索引执行文本、视觉和 BM25 独立召回。"""
        del run_manager
        if self.scope.expires_at <= datetime.now(UTC):
            raise AuthorizedRetrievalError("AUTHORIZATION")
        snapshots = {
            (snapshot.knowledge_id, snapshot.index_version): snapshot
            for snapshot in self.scope.index_snapshots
        }
        semaphore = asyncio.Semaphore(self.max_concurrency)

        async def retrieve_one(snapshot: IndexSnapshot) -> list[_ScoredCandidate]:
            """先取得该索引的授权成员，再在并发上限内执行检索。"""
            async with semaphore:
                members = await self._load_members(snapshot)
                if not members:
                    return []
                return await self._retrieve_snapshot(query, snapshot, members)

        tasks = [asyncio.create_task(retrieve_one(snapshot)) for snapshot in snapshots.values()]
        try:
            result_sets = await asyncio.gather(*tasks)
        finally:
            # 发出失败终态前停止并收拢其他索引，避免后台继续产生未结算模型消耗。
            for task in tasks:
                if not task.done():
                    task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
        started_at = perf_counter()
        settings_by_knowledge: dict[str, KnowledgeRetrievalSettings] = {}
        for knowledge_scope in self.scope.knowledge_scopes:
            if knowledge_scope.retrieval_settings is None:
                raise AuthorizedRetrievalError("INDEX_MISMATCH")
            settings_by_knowledge[knowledge_scope.knowledge_id] = knowledge_scope.retrieval_settings
        fused: list[Document] = []
        input_count = 0
        for snapshot, candidates in zip(snapshots.values(), result_sets, strict=True):
            settings = settings_by_knowledge[snapshot.knowledge_id]
            merged = self._merge_route_diagnostics(candidates)
            input_count += len(merged)
            limit = (
                settings.rerank_input_count if settings.rerank_enabled else settings.recall_count
            )
            fused.extend(ReciprocalRankFusion(k=settings.rrf_k, limit=limit).fuse(merged))
        # 跨库只比较各自融合后的名次，避免不同 RRF k 的分数被直接比较。
        fused.sort(
            key=lambda item: (
                item.metadata["rrfRank"],
                source_location_from_document(item).source.knowledge_id,
                source_location_from_document(item).source.chunk_id,
            )
        )
        for rank, document in enumerate(fused, start=1):
            document.metadata["rrfRank"] = rank
        self._fusion_metrics.append(
            RetrievalStageMetric(
                stage="RRF",
                duration_ms=self._elapsed_ms(started_at),
                input_count=input_count,
                output_count=len(fused),
            )
        )
        return fused

    def take_embedding_batches(
        self,
    ) -> tuple[tuple[EmbeddingProfile, EmbeddingBatch], ...]:
        """取走查询向量用量，供后续 RAG 执行预算和记账阶段使用。"""
        batches = tuple(self._embedding_batches)
        self._embedding_batches.clear()
        return batches

    def take_stage_metrics(self) -> tuple[RetrievalStageMetric, ...]:
        """聚合同一请求中多索引、多向量路线的阶段指标。"""
        metrics: list[RetrievalStageMetric] = []
        stage_routes: tuple[tuple[Literal["DENSE", "SPARSE"], set[RetrievalRoute]], ...] = (
            ("DENSE", {"dense_text", "dense_vision"}),
            ("SPARSE", {"sparse"}),
        )
        for stage, routes in stage_routes:
            selected = [item for item in self._route_metrics if item[0] in routes]
            metrics.append(
                RetrievalStageMetric(
                    stage=stage,
                    duration_ms=sum(item[1] for item in selected),
                    input_count=len(selected),
                    output_count=sum(item[2] for item in selected),
                )
            )
        metrics.extend(self._fusion_metrics)
        self._route_metrics.clear()
        self._fusion_metrics.clear()
        return tuple(metrics)

    async def _load_members(self, snapshot: IndexSnapshot) -> tuple[ManifestMember, ...]:
        """游标必须由 Java 签发并最终返回 null，空清单绝不退化为无过滤查询。"""
        cursor: str | None = None
        seen_cursors: set[str] = set()
        members: list[ManifestMember] = []
        while True:
            response = await self.scope_client.page_manifest(
                self.scope.scope_snapshot_id,
                ManifestPageRequest.model_validate(
                    {
                        "contractVersion": "rag-v1",
                        "requestId": self.request_id,
                        "knowledgeId": snapshot.knowledge_id,
                        "indexVersion": snapshot.index_version,
                        "manifestRevision": snapshot.manifest_revision,
                        "cursor": cursor,
                        "pageSize": self.manifest_page_size,
                    }
                ),
            )
            if (
                response.scope_snapshot_id != self.scope.scope_snapshot_id
                or response.knowledge_id != snapshot.knowledge_id
                or response.index_version != snapshot.index_version
                or response.manifest_revision != snapshot.manifest_revision
                or response.expires_at <= datetime.now(UTC)
            ):
                raise AuthorizedRetrievalError("INDEX_MISMATCH")
            members.extend(response.members)
            next_cursor = response.next_cursor
            if next_cursor is None:
                return tuple(members)
            if next_cursor in seen_cursors:
                raise AuthorizedRetrievalError("INDEX_MISMATCH")
            seen_cursors.add(next_cursor)
            cursor = next_cursor

    async def _retrieve_snapshot(
        self,
        query: str,
        snapshot: IndexSnapshot,
        members: tuple[ManifestMember, ...],
    ) -> list[_ScoredCandidate]:
        """同一 collection 内只允许查询清单列出的 generation。"""
        # 清单是 Java 本轮授权成员的唯一来源；禁止退化为不带成员条件的查询。
        member_by_generation = {member.generation_id: member for member in members}
        if len(member_by_generation) != len(members):
            raise AuthorizedRetrievalError("INDEX_MISMATCH")
        query_filter = models.Filter(
            must=[
                models.FieldCondition(
                    key="metadata.knowledgeId",
                    match=models.MatchValue(value=snapshot.knowledge_id),
                ),
                models.FieldCondition(
                    key="metadata.indexVersion",
                    match=models.MatchValue(value=snapshot.index_version),
                ),
                models.FieldCondition(
                    key="metadata.generationId",
                    match=models.MatchAny(any=list(member_by_generation)),
                ),
            ]
        )
        scope = next(
            item
            for item in self.scope.knowledge_scopes
            if item.knowledge_id == snapshot.knowledge_id
        )
        settings = scope.retrieval_settings
        if settings is None:
            raise AuthorizedRetrievalError("INDEX_MISMATCH")
        dense_enabled = settings.dense_enabled
        sparse_enabled = settings.sparse_enabled
        dense_limit = settings.recall_count
        sparse_limit = settings.recall_count
        searches: list[Coroutine[object, object, list[_ScoredCandidate]]] = []
        if dense_enabled:
            dense_embeddings = ProfileBoundEmbeddings(
                self.embedding_providers, snapshot.embedding, usage_tracker=self.usage_tracker
            )
            dense_vector = await dense_embeddings.aembed_query(query)
            self._embedding_batches.extend(
                (snapshot.embedding, batch) for batch in dense_embeddings.take_batches()
            )
            searches.append(
                self._query_route(
                    snapshot,
                    query_filter,
                    member_by_generation,
                    "dense_text",
                    dense_vector,
                    snapshot.dense_vector_name,
                    dense_limit,
                )
            )
        if sparse_enabled:
            sparse_encoder = ChineseBm25SparseEmbeddings(
                snapshot.sparse_revision, snapshot.sparse_corpus_revision
            )
            sparse_vector = await sparse_encoder.aembed_query(query)
            searches.append(
                self._query_route(
                    snapshot,
                    query_filter,
                    member_by_generation,
                    "sparse",
                    models.SparseVector(
                        indices=sparse_vector.indices,
                        values=sparse_vector.values,
                    ),
                    snapshot.sparse_vector_name,
                    sparse_limit,
                )
            )
        if (
            dense_enabled
            and snapshot.multimodal_embedding is not None
            and snapshot.dense_vision_vector_name is not None
        ):
            vision_embeddings = ProfileBoundEmbeddings(
                self.embedding_providers,
                snapshot.multimodal_embedding,
                usage_tracker=self.usage_tracker,
            )
            vision_vector = await vision_embeddings.aembed_query(query)
            self._embedding_batches.extend(
                (snapshot.multimodal_embedding, batch) for batch in vision_embeddings.take_batches()
            )
            searches.append(
                self._query_route(
                    snapshot,
                    query_filter,
                    member_by_generation,
                    "dense_vision",
                    vision_vector,
                    snapshot.dense_vision_vector_name,
                    dense_limit,
                )
            )
        # 查询向量可顺序准备，各条 Qdrant 检索路线并发执行。
        results = await asyncio.gather(*searches)
        return [candidate for route in results for candidate in route]

    async def _query_route(
        self,
        snapshot: IndexSnapshot,
        query_filter: models.Filter,
        members: dict[str, ManifestMember],
        route: RetrievalRoute,
        query_vector: list[float] | models.SparseVector,
        vector_name: str,
        limit: int,
    ) -> list[_ScoredCandidate]:
        """执行单路原生异步查询；LangChain Retriever 负责统一编排和输出。"""
        started_at = perf_counter()
        response = await self.qdrant_client.query_points(
            collection_name=snapshot.collection_name,
            query=query_vector,
            using=vector_name,
            query_filter=query_filter,
            limit=limit,
            with_payload=True,
            with_vectors=False,
            timeout=self.timeout_seconds,
        )
        candidates = [
            _ScoredCandidate(
                document=self._to_document(point, snapshot, members),
                route=route,
                score=float(point.score),
                rank=rank,
            )
            for rank, point in enumerate(response.points, start=1)
        ]
        self._route_metrics.append((route, self._elapsed_ms(started_at), len(candidates)))
        return candidates

    @staticmethod
    def _elapsed_ms(started_at: float) -> int:
        """毫秒指标至少为零，避免极短测试出现负值或浮点字段。"""
        return max(0, round((perf_counter() - started_at) * 1000))

    def _to_document(
        self,
        point: models.ScoredPoint,
        snapshot: IndexSnapshot,
        members: dict[str, ManifestMember],
    ) -> Document:
        """校验 Qdrant payload 与 Java 清单成员完全一致后才创建框架文档。"""
        payload = point.payload or {}
        page_content = payload.get("page_content")
        raw_metadata = payload.get("metadata")
        if (
            not isinstance(page_content, str)
            or not page_content
            or not isinstance(raw_metadata, dict)
        ):
            raise AuthorizedRetrievalError("INDEX_MISMATCH")
        try:
            location = SourceLocation.model_validate(raw_metadata.get("location"))
        except ValidationError as error:
            raise AuthorizedRetrievalError("INDEX_MISMATCH") from error
        source = location.source
        member = members.get(source.generation_id)
        if (
            member is None
            or source.knowledge_id != snapshot.knowledge_id
            or source.index_version != snapshot.index_version
            or source.document_id != member.document_id
            or source.document_version_id != member.document_version_id
            or raw_metadata.get("chunkId") != source.chunk_id
            or raw_metadata.get("contentHash") != source.content_hash
        ):
            raise AuthorizedRetrievalError("INDEX_MISMATCH")
        metadata = dict(raw_metadata)
        metadata["location"] = location.model_dump(by_alias=True, mode="json")
        return Document(id=str(point.id), page_content=page_content, metadata=metadata)

    @staticmethod
    def _merge_route_diagnostics(
        candidates: list[_ScoredCandidate],
    ) -> list[Document]:
        """相同来源只返回一个 Document，同时保留各路分数和名次供 RRF 使用。"""
        merged: dict[tuple[str, ...], Document] = {}
        for candidate in candidates:
            location = SourceLocation.model_validate(candidate.document.metadata["location"])
            source = location.source
            key = (
                source.knowledge_id,
                source.index_version,
                source.document_id,
                source.document_version_id,
                source.generation_id,
                source.chunk_id,
                source.content_hash,
            )
            document = merged.get(key)
            if document is None:
                document = candidate.document.model_copy(deep=True)
                document.metadata["retrievalScores"] = {}
                document.metadata["retrievalRanks"] = {}
                merged[key] = document
            scores = document.metadata["retrievalScores"]
            ranks = document.metadata["retrievalRanks"]
            if not isinstance(scores, dict) or not isinstance(ranks, dict):
                raise AuthorizedRetrievalError("INDEX_MISMATCH")
            scores[candidate.route] = candidate.score
            ranks[candidate.route] = candidate.rank
        return list(merged.values())
