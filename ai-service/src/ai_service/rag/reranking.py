"""按知识库查询策略绑定的精确 Profile 分组执行重排。

职责：
  - ProfileGroupedReranker — 按知识库绑定的 RerankProfile 分组重排。
  - 分组内按 RRF 顺序保证确定性，同组候选一次网络调用完成。
  - 跨组结果按名次融合（Rank Fusion）归并，名次优先于分数。
  - GroupedRerankError — 候选来源、RRF 诊断或知识库策略不一致的异常。

关键约束：
  - 候选只能使用所属知识库 scope 中冻结的 RerankProfile。
  - 分组间并发执行（受 max_concurrency 限制），组内顺序执行。
  - 重排结果会按名次融合（1/(k + group_rank) + 1/(k + rrf_rank)）跨组归并。
  - DEVELOPMENT_NO_RERANK 模式下所有文档透传，不调用 Rerank 供应商。
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, replace
from typing import Literal

from langchain_core.documents import Document
from langchain_core.runnables import Runnable, RunnableLambda

from ai_service.contracts.chat import Usage
from ai_service.contracts.rag_types import RerankProfile
from ai_service.contracts.retrieval import (
    KnowledgeRetrievalSettings,
    RagExecutionScope,
    SourceRef,
)
from ai_service.providers.errors import (
    ModelConfigurationError,
    ModelProviderError,
)
from ai_service.providers.metering import ExecutionUsageTracker, estimate_tokens
from ai_service.providers.rerank_protocol import (
    RerankBatch,
    RerankDocument,
    RerankProviderFactory,
)
from ai_service.rag.langchain_documents import source_location_from_document

LOGGER = logging.getLogger(__name__)

# ============================================================================
# 异常与数据类
# ============================================================================


class GroupedRerankError(RuntimeError):
    """候选来源、RRF 诊断或知识库策略不一致。"""


@dataclass(frozen=True, slots=True)
class GroupedRerankInput:
    """分组重排 Runnable 的强类型输入。"""

    query: str
    documents: tuple[Document, ...]
    allowed_sources: tuple[SourceRef, ...]
    validation_ticket: str


@dataclass(frozen=True, slots=True)
class _GroupKey:
    """知识库、Profile 修订和输入模态组成的重排批次键。"""

    knowledge_id: str
    profile_id: str
    revision: int
    input_mode: str


# ============================================================================
# 分组重排器
# ============================================================================


class ProfileGroupedReranker:
    """按知识库绑定的 Profile 分组重排，再使用名次融合跨组归并。"""

    def __init__(
        self,
        scope: RagExecutionScope,
        providers: RerankProviderFactory,
        *,
        max_concurrency: int = 4,
        usage_tracker: ExecutionUsageTracker | None = None,
    ) -> None:
        """绑定 Java 下发的知识库策略，隔离本轮用量并限制供应商并发。"""
        if max_concurrency < 1 or max_concurrency > 32:
            raise ValueError("VALIDATION")
        self._scope = scope
        self._providers = providers
        self._max_concurrency = max_concurrency
        settings_by_knowledge: dict[str, KnowledgeRetrievalSettings] = {}
        for knowledge_scope in scope.knowledge_scopes:
            if knowledge_scope.retrieval_settings is None:
                raise GroupedRerankError("INDEX_MISMATCH")
            settings_by_knowledge[knowledge_scope.knowledge_id] = knowledge_scope.retrieval_settings
        self._settings = settings_by_knowledge
        self._batches: list[tuple[RerankProfile, RerankBatch]] = []
        self._usage_tracker = usage_tracker

    def as_runnable(self) -> Runnable[GroupedRerankInput, list[Document]]:
        """返回可接在候选复核之后的异步 LCEL 节点。"""
        return RunnableLambda(self._invoke)

    async def _invoke(self, request: GroupedRerankInput) -> list[Document]:
        """校验 Java 允许集后按 Profile 分组，限流重排并融合各组名次。"""
        if not request.query.strip():
            raise GroupedRerankError("VALIDATION")
        if len(request.validation_ticket) < 16:
            raise GroupedRerankError("AUTHORIZATION")
        allowed_sources = {
            (
                source.knowledge_id,
                source.index_version,
                source.document_id,
                source.document_version_id,
                source.generation_id,
                source.chunk_id,
                source.content_hash,
            )
            for source in request.allowed_sources
        }
        if len(allowed_sources) != len(request.allowed_sources):
            raise GroupedRerankError("INDEX_MISMATCH")
        # 每个候选只能进入一个授权批次；关闭重排的候选单独透传。
        groups: dict[_GroupKey, tuple[RerankProfile, list[Document]]] = {}
        passthrough: list[Document] = []
        seen_sources: set[tuple[str, ...]] = set()
        for original in request.documents:
            document = original.model_copy(deep=True)
            source_key = self._source_key(document)
            if source_key not in allowed_sources:
                raise GroupedRerankError("AUTHORIZATION")
            if source_key in seen_sources:
                raise GroupedRerankError("INDEX_MISMATCH")
            seen_sources.add(source_key)
            knowledge_id = source_location_from_document(document).source.knowledge_id
            settings = self._settings[knowledge_id]
            if not settings.rerank_enabled:
                passthrough.append(document)
                continue
            profile = settings.rerank_profile
            if profile is None:
                raise GroupedRerankError("INDEX_MISMATCH")
            key = _GroupKey(
                knowledge_id,
                profile.profile.id,
                profile.profile.revision,
                profile.input_mode,
            )
            existing = groups.get(key)
            if existing is None:
                groups[key] = (profile, [document])
            else:
                if existing[0] != profile:
                    raise GroupedRerankError("INDEX_MISMATCH")
                existing[1].append(document)

        semaphore = asyncio.Semaphore(self._max_concurrency)

        async def run_group(
            key: _GroupKey,
            profile: RerankProfile,
            documents: list[Document],
        ) -> list[Document]:
            """限制供应商并发；可恢复的重排故障降级为当前知识库的 RRF 顺序。"""
            async with semaphore:
                try:
                    return await self._rerank_group(request.query, key, profile, documents)
                except (
                    ModelConfigurationError,
                    ModelProviderError,
                    TimeoutError,
                ) as error:
                    LOGGER.warning(
                        "重排序调用失败，已降级为 RRF：knowledge_id=%s, profile_id=%s, error_type=%s",
                        key.knowledge_id,
                        key.profile_id,
                        type(error).__name__,
                    )
                    return self._fallback_group(key.knowledge_id, documents)

        tasks = [
            asyncio.create_task(run_group(key, profile, documents))
            for key, (profile, documents) in groups.items()
        ]
        try:
            ranked_groups = await asyncio.gather(*tasks)
        finally:
            # 单组失败或取消后必须收拢同批任务，不能在失败计量发出后继续调用模型。
            for task in tasks:
                if not task.done():
                    task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
        if passthrough:
            by_knowledge: dict[str, list[Document]] = {}
            for document in passthrough:
                knowledge_id = source_location_from_document(document).source.knowledge_id
                by_knowledge.setdefault(knowledge_id, []).append(document)
            for knowledge_id, documents in by_knowledge.items():
                documents.sort(key=self._rrf_order)
                settings = self._settings[knowledge_id]
                documents = documents[: settings.rerank_output_count]
                for rank, document in enumerate(documents, start=1):
                    document.metadata["rerankApplied"] = False
                    document.metadata["rerankGroupRank"] = rank
                ranked_groups.append(documents)
        if len(ranked_groups) == 1:
            # 单知识库请求直接保留供应商重排或 RRF 降级顺序，避免二次融合改变结果。
            for rank, document in enumerate(ranked_groups[0], start=1):
                document.metadata["rerankFinalRank"] = rank
            return ranked_groups[0]
        # 组内分数不能跨模型直接比较，跨组只融合名次与原始 RRF 名次。
        combined = [document for group in ranked_groups for document in group]
        combined.sort(key=self._cross_group_order)
        for rank, document in enumerate(combined, start=1):
            document.metadata["rerankFinalRank"] = rank
        return combined

    def take_batches(self) -> tuple[tuple[RerankProfile, RerankBatch], ...]:
        """取走当前请求的 Profile 和调用批次，并清空内部列表。

        与 take_batches 在 Embedding 适配器中的模式一致：供 RAG 执行链统一结算用量。"""
        batches = tuple(self._batches)
        self._batches.clear()
        return batches

    async def _rerank_group(
        self,
        query: str,
        key: _GroupKey,
        profile: RerankProfile,
        documents: list[Document],
    ) -> list[Document]:
        """对单组文档调用供应商 Rerank API，按分数降序排列，平局用 RRF 名次和稳定键打破。

        返回的文档不改变内容，仅附加 rerankApplied/Score/Profile/GroupRank 到 metadata。"""
        provider = self._providers.provider_for(profile)
        inputs = tuple(
            RerankDocument(
                input_id=self._input_id(document),
                text=document.page_content,
                modality=("TEXT"),
            )
            for document in documents
        )
        tracker = self._usage_tracker
        call_id: str | None = None
        input_tokens = await asyncio.to_thread(
            estimate_tokens, "\n".join(f"{query}\n{item.text}" for item in inputs)
        )
        if tracker is not None:
            call_id = tracker.begin("RERANK", profile.profile, input_tokens)
        try:
            batch = await provider.rerank(profile, query, inputs)
        except Exception:
            if tracker is not None and call_id is not None:
                tracker.reject(call_id)
            raise
        if batch.input_tokens is None or batch.input_tokens <= 0:
            batch = replace(batch, input_tokens=input_tokens, measurement_quality="ESTIMATED")
        if tracker is not None and call_id is not None:
            tracker.finish(
                call_id,
                Usage(input_tokens=batch.input_tokens, output_tokens=0),
                batch.measurement_quality,
            )
        expected_ids = {item.input_id for item in inputs}
        actual_ids = [item.input_id for item in batch.scores]
        if len(actual_ids) != len(expected_ids) or set(actual_ids) != expected_ids:
            raise GroupedRerankError("INDEX_MISMATCH")
        score_by_id = {item.input_id: item.score for item in batch.scores}
        ranked = sorted(
            documents,
            key=lambda document: (
                -score_by_id[self._input_id(document)],
                self._rrf_rank(document),
                self._source_key(document),
            ),
        )
        for rank, document in enumerate(ranked, start=1):
            document.metadata["rerankApplied"] = True
            document.metadata["rerankProfile"] = {
                "id": key.profile_id,
                "revision": key.revision,
                "inputMode": key.input_mode,
            }
            document.metadata["rerankScore"] = score_by_id[self._input_id(document)]
            document.metadata["rerankGroupRank"] = rank
        self._batches.append((profile, batch))
        settings = self._settings[key.knowledge_id]
        return ranked[: settings.rerank_output_count]

    def _fallback_group(self, knowledge_id: str, documents: list[Document]) -> list[Document]:
        """重排依赖不可用时保留已授权候选，并按确定性 RRF 顺序裁剪。"""
        settings = self._settings[knowledge_id]
        ranked = sorted(documents, key=self._rrf_order)[: settings.rerank_output_count]
        for rank, document in enumerate(ranked, start=1):
            document.metadata["rerankApplied"] = False
            document.metadata["rerankFallback"] = "RRF"
            document.metadata["rerankGroupRank"] = rank
        return ranked

    def _cross_group_order(self, document: Document) -> tuple[float, int, tuple[str, ...]]:
        """跨组排序键：rerank 组内名次与 RRF 名次的融合分数降序。

        融合公式 = 1/(k + group_rank) + 1/(k + rrf_rank)，平局由 RRF 名次和稳定键打破。"""
        group_rank = document.metadata.get("rerankGroupRank")
        if isinstance(group_rank, bool) or not isinstance(group_rank, int) or group_rank < 1:
            raise GroupedRerankError("INDEX_MISMATCH")
        knowledge_id = source_location_from_document(document).source.knowledge_id
        settings = self._settings[knowledge_id]
        k = settings.rrf_k
        score = 1.0 / (k + group_rank) + 1.0 / (k + self._rrf_rank(document))
        document.metadata["rerankFusionScore"] = score
        return -score, self._rrf_rank(document), self._source_key(document)

    @staticmethod
    def _modality(document: Document) -> Literal["TEXT", "IMAGE"]:
        """从 metadata 提取模态，仅接受 TEXT/IMAGE 两个值。"""
        modality = document.metadata.get("modality", "TEXT")
        if modality == "TEXT":
            return "TEXT"
        if modality == "IMAGE":
            return "IMAGE"
        raise GroupedRerankError("INDEX_MISMATCH")

    @staticmethod
    def _rrf_rank(document: Document) -> int:
        """提取 RRF 名次，必须为正整数。"""
        rank = document.metadata.get("rrfRank")
        if isinstance(rank, bool) or not isinstance(rank, int) or rank < 1:
            raise GroupedRerankError("INDEX_MISMATCH")
        return rank

    @classmethod
    def _rrf_order(cls, document: Document) -> tuple[int, tuple[str, ...]]:
        """未重排文档的排序键：RRF 名次升序 + 稳定键。"""
        return cls._rrf_rank(document), cls._source_key(document)

    @staticmethod
    def _source_key(document: Document) -> tuple[str, ...]:
        """从 Document metadata 提取来源键（七元组），用于去重和排序。"""
        source = source_location_from_document(document).source
        return (
            source.knowledge_id,
            source.index_version,
            source.document_id,
            source.document_version_id,
            source.generation_id,
            source.chunk_id,
            source.content_hash,
        )

    @classmethod
    def _input_id(cls, document: Document) -> str:
        """用 : 串联来源键生成稳定的 input_id，供 Rerank 供应商回传对应分数。"""
        return ":".join(cls._source_key(document))
