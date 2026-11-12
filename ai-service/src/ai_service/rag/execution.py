"""Java 复核位于 RRF 与分组 Rerank 之间的 LangChain 检索执行链。

职责：
  - AuthorizedRetrievalChain — 执行授权召回、Java 候选复核和文档级分组 Rerank。
  - KnowledgeRetrievalTestUseCase — 将检索链包装为可调用用例，供 API 和 RAG 聊天复用。

核心流程：
  1. 混合检索（Dense + BM25）→ RRF 融合。
  2. Java 第一次复核 → 过滤未授权候选。
  3. 分组 Rerank → 上下文补齐 → Java 第二次复核。
  4. 返回最终允许的文档、验证票据和用量统计。

关键约束：
  - 两次 Java 复核（RRF 后一次，上下文选完后一次），防止上下文补齐引入未授权片段。
  - 拒绝过期、扩权或 scope 不匹配的复核响应。
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from time import perf_counter
from typing import Literal
from uuid import NAMESPACE_URL, uuid5

from langchain_core.documents import Document
from langchain_core.runnables import Runnable, RunnableLambda
from qdrant_client import AsyncQdrantClient

from ai_service.contracts.rag_types import (
    EmbeddingProfile,
    ExecutionUsageItem,
    RequestMeasurement,
    RerankProfile,
    TokenMeasurement,
)
from ai_service.contracts.retrieval import (
    CandidateValidationResponse,
    RagExecutionScope,
    RetrievalStageStats,
    RetrievalTestRequest,
    RetrievalTestResponse,
    RetrievedChunk,
    SourceRef,
    ValidateCandidatesRequest,
)
from ai_service.infra.knowledge_scope_client import KnowledgeScopeClient
from ai_service.providers.embedding_protocol import EmbeddingBatch, EmbeddingProviderFactory
from ai_service.providers.metering import ExecutionUsageTracker
from ai_service.providers.rerank_protocol import RerankBatch, RerankProviderFactory
from ai_service.rag.context import ContextSelectionInput, ContextSelector
from ai_service.rag.langchain_documents import source_location_from_document
from ai_service.rag.reranking import (
    GroupedRerankInput,
    ProfileGroupedReranker,
)
from ai_service.rag.retrieval import AuthorizedHybridRetriever, RetrievalStageMetric

# ============================================================================
# 异常与数据类
# ============================================================================


class AuthorizedRetrievalChainError(RuntimeError):
    """Java 复核响应或候选来源违反当前请求快照。"""


@dataclass(frozen=True, slots=True)
class AuthorizedRetrievalInput:
    """LangChain 检索执行链的强类型输入。"""

    query: str
    purpose: Literal["MODEL_CONTEXT", "COMPLETION", "CHUNK_PREVIEW"]


@dataclass(frozen=True, slots=True)
class AuthorizedRetrievalResult:
    """只包含 Java 复核允许且已完成文档级重排的候选。"""

    documents: tuple[Document, ...]
    validation_ticket: str
    validation_expires_at: datetime
    retrieval_metrics: tuple[RetrievalStageMetric, ...]
    rerank_duration_ms: int
    rerank_input_count: int
    rerank_output_count: int
    context_duration_ms: int
    context_input_count: int
    context_token_count: int
    embedding_batches: tuple[tuple[EmbeddingProfile, EmbeddingBatch], ...]
    rerank_batches: tuple[tuple[RerankProfile, RerankBatch], ...]


# ============================================================================
# 授权检索执行链
# ============================================================================


class AuthorizedRetrievalChain:
    """执行授权召回、Java 候选复核和文档级分组 Rerank。"""

    def __init__(
        self,
        request_id: str,
        scope: RagExecutionScope,
        scope_client: KnowledgeScopeClient,
        retriever: AuthorizedHybridRetriever,
        reranker: ProfileGroupedReranker,
        context_selector: ContextSelector,
    ) -> None:
        """装配当前请求的授权范围、复核客户端与检索后处理节点。"""
        self._request_id = request_id
        self._scope = scope
        self._scope_client = scope_client
        self._retriever = retriever
        self._reranker = reranker
        self._context_selector = context_selector

    def _passes_knowledge_threshold(self, document: Document) -> bool:
        """按候选所属知识库过滤低于查询阈值的证据。"""
        knowledge_id = source_location_from_document(document).source.knowledge_id
        scope = next(
            item for item in self._scope.knowledge_scopes if item.knowledge_id == knowledge_id
        )
        settings = scope.retrieval_settings
        if settings is None or settings.relevance_threshold is None:
            return True
        score = document.metadata.get("rerankScore")
        if score is None:
            scores = document.metadata.get("retrievalScores")
            if not isinstance(scores, dict):
                raise AuthorizedRetrievalChainError("INDEX_MISMATCH")
            dense_scores = [
                value for route, value in scores.items() if route in {"dense_text", "dense_vision"}
            ]
            if not dense_scores:
                return False
            score = max(dense_scores)
        if isinstance(score, bool) or not isinstance(score, float):
            raise AuthorizedRetrievalChainError("INDEX_MISMATCH")
        return score >= settings.relevance_threshold

    def as_runnable(self) -> Runnable[AuthorizedRetrievalInput, AuthorizedRetrievalResult]:
        """返回可复用于检索测试和 RAG 聊天的 LCEL 节点。"""
        return RunnableLambda(self._invoke)

    async def _invoke(self, request: AuthorizedRetrievalInput) -> AuthorizedRetrievalResult:
        """执行召回、两次 Java 候选复核、重排和上下文预算选择。"""
        query = request.query.strip()
        if not query:
            raise AuthorizedRetrievalChainError("VALIDATION")
        documents = tuple(await self._retriever.ainvoke(query))
        candidate_sources = tuple(
            source_location_from_document(document).source for document in documents
        )
        # 首次复核只允许进入本轮候选集的成员参与重排。
        validation = await self._scope_client.validate_candidates(
            self._scope.scope_snapshot_id,
            ValidateCandidatesRequest.model_validate(
                {
                    "contractVersion": "rag-v1",
                    "requestId": self._request_id,
                    "candidates": [
                        source.model_dump(by_alias=True, mode="json")
                        for source in candidate_sources
                    ],
                    "purpose": request.purpose,
                }
            ),
        )
        allowed_sources = self._require_validation(validation, candidate_sources)
        allowed_keys = {self._source_key(source) for source in allowed_sources}
        allowed_documents = tuple(
            document
            for document in documents
            if self._source_key(source_location_from_document(document).source) in allowed_keys
        )

        rerank_started = perf_counter()
        if allowed_documents:
            reranked = await self._reranker.as_runnable().ainvoke(
                GroupedRerankInput(
                    query=query,
                    documents=allowed_documents,
                    allowed_sources=allowed_sources,
                    validation_ticket=validation.validation_ticket,
                )
            )
        else:
            reranked = []
        # 每个知识库都携带自己的查询策略，跨库上限取各库返回数量之和。
        settings = [item.retrieval_settings for item in self._scope.knowledge_scopes]
        if any(item is None for item in settings):
            raise AuthorizedRetrievalChainError("INDEX_MISMATCH")
        configured_limit = sum(item.rerank_output_count for item in settings if item is not None)
        final_limit = min(
            max(configured_limit, 1),
            self._scope.retrieval_snapshot.max_context_chunks,
        )
        reranked_documents = tuple(
            document for document in reranked if self._passes_knowledge_threshold(document)
        )[:final_limit]
        context = await self._context_selector.as_runnable().ainvoke(
            ContextSelectionInput(documents=reranked_documents)
        )
        context_sources = tuple(
            source_location_from_document(document).source for document in context.documents
        )
        # 上下文扩展可能带入原候选外的同文档片段，必须再次交 Java 复核。
        final_validation = await self._scope_client.validate_candidates(
            self._scope.scope_snapshot_id,
            ValidateCandidatesRequest.model_validate(
                {
                    "contractVersion": "rag-v1",
                    "requestId": self._request_id,
                    "candidates": [
                        source.model_dump(by_alias=True, mode="json") for source in context_sources
                    ],
                    "purpose": request.purpose,
                }
            ),
        )
        final_allowed = self._require_validation(final_validation, context_sources)
        final_allowed_keys = {self._source_key(source) for source in final_allowed}
        final_documents = tuple(
            document
            for document in context.documents
            if self._source_key(source_location_from_document(document).source)
            in final_allowed_keys
        )
        return AuthorizedRetrievalResult(
            documents=final_documents,
            validation_ticket=final_validation.validation_ticket,
            validation_expires_at=final_validation.expires_at,
            retrieval_metrics=self._retriever.take_stage_metrics(),
            rerank_duration_ms=self._elapsed_ms(rerank_started),
            rerank_input_count=len(allowed_documents),
            rerank_output_count=len(reranked_documents),
            context_duration_ms=context.duration_ms,
            context_input_count=context.input_count,
            context_token_count=context.token_count,
            embedding_batches=tuple(self._retriever.take_embedding_batches()),
            rerank_batches=tuple(self._reranker.take_batches()),
        )

    def _require_validation(
        self,
        response: CandidateValidationResponse,
        candidates: tuple[SourceRef, ...],
    ) -> tuple[SourceRef, ...]:
        """校验 Java 返回的候选复核：scope 一致、未过期、允许集是候选的子集。

        拒绝过期（expires_at 已过或超过 scope 有效期）、扩权（允许了没有请求的候选）
        或 scope 不匹配的响应，这些都可能表明中间人篡改或 Java 端配置错误。"""
        if (
            response.scope_snapshot_id != self._scope.scope_snapshot_id
            or response.expires_at <= datetime.now(UTC)
            or response.expires_at > self._scope.expires_at
        ):
            raise AuthorizedRetrievalChainError("AUTHORIZATION")
        candidate_keys = {self._source_key(source) for source in candidates}
        if len(candidate_keys) != len(candidates):
            raise AuthorizedRetrievalChainError("INDEX_MISMATCH")
        allowed_keys = {self._source_key(source) for source in response.allowed_candidates}
        if len(allowed_keys) != len(response.allowed_candidates) or not allowed_keys.issubset(
            candidate_keys
        ):
            raise AuthorizedRetrievalChainError("AUTHORIZATION")
        return tuple(response.allowed_candidates)

    @staticmethod
    def _source_key(source: SourceRef) -> tuple[str, ...]:
        """片段级稳定键，包含 chunk_id 和 content_hash 用于精确去重。"""
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
    def _elapsed_ms(started_at: float) -> int:
        """perf_counter 差值转毫秒，下限钳制为 0 避免浮点舍入产生负数。"""
        return max(0, round((perf_counter() - started_at) * 1000))


# ============================================================================
# 检索测试用例：API 和 RAG 聊天共用的检索能力
# ============================================================================


class KnowledgeRetrievalTestUseCase:
    """执行与生产 RAG 共用的检索链，并返回受控诊断 DTO。"""

    def __init__(
        self,
        scope_client: KnowledgeScopeClient,
        embedding_providers: EmbeddingProviderFactory,
        rerank_providers: RerankProviderFactory,
        qdrant_client: AsyncQdrantClient,
        *,
        timeout_seconds: int,
        max_concurrency: int,
    ) -> None:
        """保存检索测试共用依赖；每次执行单独构建授权检索链。"""
        self._scope_client = scope_client
        self._embedding_providers = embedding_providers
        self._rerank_providers = rerank_providers
        self._qdrant_client = qdrant_client
        self._timeout_seconds = timeout_seconds
        self._max_concurrency = max_concurrency

    async def execute(
        self, request: RetrievalTestRequest, *, usage_tracker: ExecutionUsageTracker | None = None
    ) -> RetrievalTestResponse:
        """调用真实 Embedding/Qdrant/Rerank，同时由 Java 复核候选。"""
        retriever = AuthorizedHybridRetriever(
            request_id=request.request_id,
            scope=request.rag,
            scope_client=self._scope_client,
            embedding_providers=self._embedding_providers,
            qdrant_client=self._qdrant_client,
            timeout_seconds=self._timeout_seconds,
            max_concurrency=self._max_concurrency,
            usage_tracker=usage_tracker,
        )
        reranker = ProfileGroupedReranker(
            request.rag,
            self._rerank_providers,
            max_concurrency=self._max_concurrency,
            usage_tracker=usage_tracker,
        )
        result = (
            await AuthorizedRetrievalChain(
                request.request_id,
                request.rag,
                self._scope_client,
                retriever,
                reranker,
                ContextSelector(
                    request.rag,
                    self._qdrant_client,
                    timeout_seconds=self._timeout_seconds,
                ),
            )
            .as_runnable()
            .ainvoke(AuthorizedRetrievalInput(query=request.query, purpose="MODEL_CONTEXT"))
        )
        return RetrievalTestResponse.model_validate(
            {
                "requestId": request.request_id,
                "scopeSnapshotId": request.rag.scope_snapshot_id,
                "chunks": [self._to_chunk(document) for document in result.documents],
                "validationTicket": result.validation_ticket,
                "executionUsage": self._usage(request.request_id, result),
                "stages": self._stages(request.rag, result),
            }
        )

    @staticmethod
    def _to_chunk(document: Document) -> RetrievedChunk:
        """从 RRF/Rerank 后的 Document 提取诊断用 DTO，包含各路分数。"""
        scores = document.metadata.get("retrievalScores")
        if not isinstance(scores, dict):
            raise AuthorizedRetrievalChainError("INDEX_MISMATCH")
        dense_values = [
            value
            for route in ("dense_text", "dense_vision")
            if isinstance((value := scores.get(route)), float)
        ]
        return RetrievedChunk.model_validate(
            {
                "location": source_location_from_document(document),
                "text": document.page_content,
                "denseScore": max(dense_values) if dense_values else None,
                "sparseScore": KnowledgeRetrievalTestUseCase._optional_score(scores.get("sparse")),
                "rrfScore": KnowledgeRetrievalTestUseCase._optional_score(
                    document.metadata.get("rrfScore")
                ),
                "rerankScore": KnowledgeRetrievalTestUseCase._optional_score(
                    document.metadata.get("rerankScore")
                ),
            }
        )

    @staticmethod
    def _optional_score(value: object) -> float | None:
        """None 或有限浮点数，拒绝 bool 和 int 防止类型混淆。"""
        if value is None:
            return None
        if isinstance(value, bool) or not isinstance(value, float):
            raise AuthorizedRetrievalChainError("INDEX_MISMATCH")
        return value

    @staticmethod
    def _usage(
        request_id: str,
        result: AuthorizedRetrievalResult,
    ) -> list[ExecutionUsageItem]:
        """将检索链中的 Embedding 和 Rerank 批次转为统一的用量项列表。

        重排优先使用供应商 Token；未返回时按查询与候选文本估算并标记。"""
        usage: list[ExecutionUsageItem] = []
        for index, (embedding_profile, embedding_batch) in enumerate(
            result.embedding_batches, start=1
        ):
            usage.append(
                ExecutionUsageItem.model_validate(
                    {
                        "stage": "EMBEDDING",
                        "callId": KnowledgeRetrievalTestUseCase._call_id(
                            request_id, "embedding", index
                        ),
                        "attemptId": request_id,
                        "profile": embedding_profile.profile,
                        "measurement": TokenMeasurement.model_validate(
                            {
                                "unit": "TOKENS",
                                "quality": "ACTUAL",
                                "inputTokens": embedding_batch.prompt_tokens,
                                "outputTokens": max(
                                    0,
                                    embedding_batch.total_tokens - embedding_batch.prompt_tokens,
                                ),
                            }
                        ),
                    }
                )
            )
        for index, (rerank_profile, rerank_batch) in enumerate(result.rerank_batches, start=1):
            usage.append(
                ExecutionUsageItem.model_validate(
                    {
                        "stage": "RERANK",
                        "callId": KnowledgeRetrievalTestUseCase._call_id(
                            request_id, "rerank", index
                        ),
                        "attemptId": request_id,
                        "profile": rerank_profile.profile,
                        "measurement": TokenMeasurement.model_validate(
                            {
                                "unit": "TOKENS",
                                "quality": rerank_batch.measurement_quality,
                                "inputTokens": rerank_batch.input_tokens,
                                "outputTokens": 0,
                            }
                        )
                        if rerank_batch.input_tokens is not None
                        else RequestMeasurement(
                            unit="DOCUMENT_PAIRS",
                            quality="ACTUAL",
                            quantity=rerank_batch.document_pair_count,
                        ),
                    }
                )
            )
        return usage

    @staticmethod
    def _stages(
        scope: RagExecutionScope,
        result: AuthorizedRetrievalResult,
    ) -> list[RetrievalStageStats]:
        """将检索链内部指标转为面向外部的阶段统计 DTO。

        Rerank 阶段在 DEVELOPMENT_NO_RERANK 模式或无输入时标记为 SKIPPED。"""
        stages = [
            RetrievalStageStats.model_validate(
                {
                    "stage": metric.stage,
                    "status": "COMPLETED",
                    "durationMs": metric.duration_ms,
                    "inputCount": metric.input_count,
                    "outputCount": metric.output_count,
                }
            )
            for metric in result.retrieval_metrics
        ]
        rerank_completed = (
            scope.retrieval_snapshot.mode == "DENSE_BM25_RRF_RERANK"
            and result.rerank_input_count > 0
        )
        stages.append(
            RetrievalStageStats.model_validate(
                {
                    "stage": "RERANK",
                    "status": "COMPLETED" if rerank_completed else "SKIPPED",
                    "durationMs": result.rerank_duration_ms if rerank_completed else 0,
                    "inputCount": result.rerank_input_count,
                    "outputCount": result.rerank_output_count,
                }
            )
        )
        stages.append(
            RetrievalStageStats.model_validate(
                {
                    "stage": "CONTEXT",
                    "status": "COMPLETED",
                    "durationMs": result.context_duration_ms,
                    "inputCount": result.context_input_count,
                    "outputCount": len(result.documents),
                }
            )
        )
        return stages

    @staticmethod
    def _call_id(request_id: str, stage: str, index: int) -> str:
        """生成确定性调用 ID，基于 request_id + stage + index 的 UUID5 摘要。"""
        return uuid5(NAMESPACE_URL, f"{request_id}:{stage}:{index}").hex
