"""Embedding、中文 BM25、Qdrant 与不可变清单的正式入库阶段。

职责：
  - VectorKnowledgeChunkPipeline — 接收已完成解析和切片的片段，执行向量化和 Qdrant 写入。
  - 依次生成 Dense 文本向量、BM25 稀疏向量和可选的图片 Dense 向量。
  - 稳定 upsert 后立即回读核验生成的点数。
  - 所有向量完成后向 Java 上传 ArtifactManifest，由 Java 验收后才发布。

关键约束：
  - 任一路向量失败或 Qdrant 写入不完整时，清理当前 generation。
  - Token 预算在向量化前核验，超限阻断后续付费模型调用。
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, replace
from datetime import UTC, datetime

from langchain_core.runnables import Runnable, RunnableLambda
from langchain_qdrant import SparseVector

from ai_service.contracts.knowledge import IngestionResult
from ai_service.contracts.knowledge_kafka import (
    KnowledgeArtifactsEvent,
    KnowledgeDocumentTask,
    KnowledgeFailedEvent,
    KnowledgeJobCommand,
    KnowledgeProgressEvent,
)
from ai_service.contracts.rag_artifacts import ArtifactManifest
from ai_service.contracts.rag_types import EmbeddingProfile, ExecutionUsageItem
from ai_service.domain.knowledge_execution import (
    KnowledgeExecutionContext,
    KnowledgeManifestError,
    KnowledgeManifestPublisher,
    KnowledgeTerminal,
    ProgressCallback,
)
from ai_service.infra.vector_store import QdrantKnowledgeVectorStore, VectorStoreError
from ai_service.providers.embedding_protocol import (
    DenseEmbedding,
    EmbeddingBatch,
    EmbeddingMedia,
    EmbeddingProviderFactory,
)
from ai_service.providers.errors import (
    ModelConfigurationError,
    ModelProviderAuthenticationError,
    ModelProviderError,
    ModelProviderRequestError,
    ModelProviderTimeoutError,
)
from ai_service.providers.langchain_adapters import (
    ProfileBoundEmbeddings,
    VisionEmbeddingInput,
    VisionEmbeddingRunnable,
)
from ai_service.rag.budget import require_budget
from ai_service.rag.ingestion import PreparedKnowledgeDocument
from ai_service.rag.models import KnowledgeProcessingError, ParsedImage
from ai_service.rag.sparse_encoding import ChineseBm25SparseEmbeddings, SparseEncodingError

# ============================================================================
# 内部数据类
# ============================================================================


@dataclass(frozen=True, slots=True)
class _VectorizationState:
    """LCEL 向量阶段的强类型状态；不跨越任务或请求共享。"""

    command: KnowledgeJobCommand
    prepared: PreparedKnowledgeDocument
    context: KnowledgeExecutionContext
    report_progress: ProgressCallback
    usage_items: tuple[ExecutionUsageItem, ...]
    dense_batch: EmbeddingBatch | None = None
    sparse_vectors: tuple[SparseVector, ...] = ()
    vision_batch: EmbeddingBatch | None = None


# ============================================================================
# 向量化入库流水线
# ============================================================================


class VectorKnowledgeChunkPipeline:
    """执行动态模型路由、冻结稀疏编码、Qdrant 写入和清单发布。"""

    def __init__(
        self,
        embedding_providers: EmbeddingProviderFactory,
        vector_store: QdrantKnowledgeVectorStore,
        manifest_publisher: KnowledgeManifestPublisher,
    ) -> None:
        """装配顺序固定的向量阶段与清单发布器，避免重复调用模型。"""
        self.embedding_providers = embedding_providers
        self.vector_store = vector_store
        self.manifest_publisher = manifest_publisher
        self._vision_runnable = VisionEmbeddingRunnable(embedding_providers).as_runnable()
        self._vectorization_chain: Runnable[_VectorizationState, _VectorizationState] = (
            RunnableLambda(self._dense_stage)
            | RunnableLambda(self._sparse_stage)
            | RunnableLambda(self._vision_stage)
        )

    async def execute(
        self,
        command: KnowledgeJobCommand,
        prepared: PreparedKnowledgeDocument,
        context: KnowledgeExecutionContext,
        report_progress: ProgressCallback,
    ) -> KnowledgeTerminal:
        """文本片段统一入 text dense/sparse；图片片段额外写入 vision dense。"""
        processing = command.document_processing_snapshot
        if (
            not isinstance(command.task, KnowledgeDocumentTask)
            or processing is None
            or not prepared.chunks
        ):
            return self._failed("EXECUTOR_NOT_READY", list(prepared.usage_items))
        usage_items = list(prepared.usage_items)
        profile = command.index_snapshot.embedding
        try:
            state = await self._vectorization_chain.ainvoke(
                _VectorizationState(
                    command=command,
                    prepared=prepared,
                    context=context,
                    report_progress=report_progress,
                    usage_items=tuple(usage_items),
                )
            )
            usage_items = list(state.usage_items)
            dense_batch = state.dense_batch
            sparse_vectors = state.sparse_vectors
            vision_batch = state.vision_batch
            if dense_batch is None or not sparse_vectors:
                return self._failed("INDEX_MISMATCH", usage_items)
        except KnowledgeProcessingError as error:
            failure_usage = list(error.usage_items) if error.usage_items else usage_items
            return self._failed(error.error_code, failure_usage)
        except (
            ModelConfigurationError,
            ModelProviderAuthenticationError,
            ModelProviderRequestError,
        ):
            return self._failed("MODEL_UNAVAILABLE", usage_items)
        except ModelProviderTimeoutError:
            return self._failed("TIMEOUT", usage_items)
        except ModelProviderError:
            return self._failed("EMBEDDING_UNAVAILABLE", usage_items)
        except SparseEncodingError as error:
            code = "PARSE_FAILED" if str(error) == "PARSE_FAILED" else "INDEX_MISMATCH"
            return self._failed(code, usage_items)
        except ValueError:
            return self._failed("INDEX_MISMATCH", usage_items)

        # 只有全部向量及预算校验完成后，才开始写入当前 generation。
        await report_progress(
            KnowledgeProgressEvent(eventType="PROGRESS", stage="WRITING", progress=70)
        )
        try:
            write_result = await self.vector_store.write(
                command,
                prepared.chunks,
                dense_batch,
                sparse_vectors,
                vision_batch,
            )
        except VectorStoreError as error:
            return self._failed(error.error_code, usage_items)

        vision_dimensions = (
            command.index_snapshot.multimodal_embedding.dimensions
            if write_result.vision_count > 0
            and command.index_snapshot.multimodal_embedding is not None
            else None
        )
        # 向量已回读核验，清单上传失败须清理本批次以免留下孤儿点。
        manifest = self._manifest(command, prepared, vision_dimensions)
        try:
            await report_progress(
                KnowledgeProgressEvent(eventType="PROGRESS", stage="VALIDATING", progress=90)
            )
            published = await self.manifest_publisher.publish(command, context, manifest)
        except asyncio.CancelledError:
            await self._cleanup(command)
            raise
        except KnowledgeManifestError:
            await self._cleanup(command)
            return self._failed("INTERNAL", usage_items)
        return KnowledgeArtifactsEvent(
            eventType="ARTIFACTS_READY",
            result=IngestionResult.model_validate(
                {
                    "jobType": "INGESTION",
                    "manifestObjectId": published.object_id,
                    "manifestHash": published.content_hash,
                    "chunkCount": len(prepared.chunks),
                    "denseCount": write_result.dense_count,
                    "sparseCount": write_result.sparse_count,
                    "visionCount": write_result.vision_count,
                    "vectorDimensions": profile.dimensions,
                    "visionVectorDimensions": vision_dimensions,
                    "parseStats": prepared.parse_stats.model_dump(
                        by_alias=True, mode="json"
                    ),
                    "usageItems": [
                        item.model_dump(by_alias=True, mode="json")
                        for item in usage_items
                    ],
                }
            ),
        )

    async def _dense_stage(self, state: _VectorizationState) -> _VectorizationState:
        """通过 LangChain ProfileBoundEmbeddings 执行文本向量化，校验仅一个批次。

        向量完成后立即调用 require_budget 核验 Token 配额，防止超额后继续写入 Qdrant。"""
        await state.report_progress(
            KnowledgeProgressEvent(eventType="PROGRESS", stage="EMBEDDING", progress=40)
        )
        profile = state.command.index_snapshot.embedding
        embeddings = ProfileBoundEmbeddings(
            self.embedding_providers,
            profile,
            tuple(chunk.chunk_id for chunk in state.prepared.chunks),
        )
        texts = (
            [document.page_content for document in state.prepared.documents]
            if state.prepared.documents
            else [chunk.text for chunk in state.prepared.chunks]
        )
        vectors = await embeddings.aembed_documents(texts)
        batches = embeddings.take_batches()
        if len(batches) != 1:
            raise ValueError("INDEX_MISMATCH")
        provider_batch = batches[0]
        dense_batch = EmbeddingBatch(
            embeddings=tuple(
                DenseEmbedding(chunk.chunk_id, tuple(vector))
                for chunk, vector in zip(state.prepared.chunks, vectors, strict=True)
            ),
            request_count=provider_batch.request_count,
            prompt_tokens=provider_batch.prompt_tokens,
            total_tokens=provider_batch.total_tokens,
        )
        usage = self._embedding_usage(
            state.command,
            profile,
            dense_batch.prompt_tokens,
            dense_batch.total_tokens,
            "text",
        )
        usage_items = (*state.usage_items, usage)
        require_budget(state.command, usage_items)
        return replace(state, usage_items=usage_items, dense_batch=dense_batch)

    async def _sparse_stage(self, state: _VectorizationState) -> _VectorizationState:
        """复用 LangChain SparseEmbeddings 接口生成冻结版本的中文 BM25 稀疏向量。

        sparse_revision 和 sparse_corpus_revision 来自索引快照，冻结后不随 jieba 版本变化。"""
        snapshot = state.command.index_snapshot
        encoder = ChineseBm25SparseEmbeddings(
            snapshot.sparse_revision,
            snapshot.sparse_corpus_revision,
        )
        sparse_vectors = tuple(
            await encoder.aembed_documents(
                [chunk.text for chunk in state.prepared.chunks]
            )
        )
        return replace(state, sparse_vectors=sparse_vectors)

    async def _vision_stage(self, state: _VectorizationState) -> _VectorizationState:
        """图片按内容去重调用多模态 Embedding，再展开到每个来源位置。

        仅在 MULTIMODAL 处理模式 + 快照配置了 multimodal_embedding 时执行。"""
        image_chunks = [
            chunk for chunk in state.prepared.chunks if chunk.modality == "IMAGE"
        ]
        if not image_chunks:
            await state.report_progress(
                KnowledgeProgressEvent(
                    eventType="PROGRESS", stage="EMBEDDING", progress=60
                )
            )
            return state
        processing = state.command.document_processing_snapshot
        multimodal = state.command.index_snapshot.multimodal_embedding
        if (
            processing is None
            or processing.processing_mode != "MULTIMODAL"
            or multimodal is None
            or state.command.index_snapshot.dense_vision_vector_name is None
            or not state.prepared.images
        ):
            raise ValueError("INDEX_MISMATCH")
        unique_images_by_hash: dict[str, ParsedImage] = {}
        for image in state.prepared.images:
            unique_images_by_hash.setdefault(image.content_hash, image)
        unique_images = tuple(unique_images_by_hash.values())
        provider_batch = await self._vision_runnable.ainvoke(
            VisionEmbeddingInput(
                profile=multimodal,
                media=tuple(
                    EmbeddingMedia(
                        input_id=image.media_id,
                        path=image.path,
                        media_type=image.media_type,
                    )
                    for image in unique_images
                ),
            )
        )
        if [item.input_id for item in provider_batch.embeddings] != [
            image.media_id for image in unique_images
        ]:
            raise ValueError("INDEX_MISMATCH")
        vector_by_hash = {
            image.content_hash: embedding.values
            for image, embedding in zip(
                unique_images, provider_batch.embeddings, strict=True
            )
        }
        vision_batch = EmbeddingBatch(
            embeddings=tuple(
                DenseEmbedding(image.media_id, vector_by_hash[image.content_hash])
                for image in state.prepared.images
            ),
            request_count=provider_batch.request_count,
            prompt_tokens=provider_batch.prompt_tokens,
            total_tokens=provider_batch.total_tokens,
        )
        usage = self._embedding_usage(
            state.command,
            multimodal,
            vision_batch.prompt_tokens,
            vision_batch.total_tokens,
            "vision",
        )
        usage_items = (*state.usage_items, usage)
        require_budget(state.command, usage_items)
        await state.report_progress(
            KnowledgeProgressEvent(eventType="PROGRESS", stage="EMBEDDING", progress=60)
        )
        return replace(
            state,
            usage_items=usage_items,
            vision_batch=vision_batch,
        )

    def _manifest(
        self,
        command: KnowledgeJobCommand,
        prepared: PreparedKnowledgeDocument,
        vision_dimensions: int | None,
    ) -> ArtifactManifest:
        """构造写入 OSS 的产物清单：包含 point_id、ordinal、模态和定位信息。

        清单不含正文和向量，仅记录元数据索引，Java 端据此恢复文档级统计。"""
        task = command.task
        if not isinstance(task, KnowledgeDocumentTask):
            raise KnowledgeManifestError("INDEX_MISMATCH")
        return ArtifactManifest.model_validate(
            {
                "contractVersion": "rag-v1",
                "jobId": command.job_id,
                "attemptId": command.attempt_id,
                "generationId": command.generation_id,
                "knowledgeId": command.knowledge_id,
                "indexVersion": command.index_snapshot.index_version,
                "documentId": task.document_id,
                "documentVersionId": task.document_version_id,
                "configHash": command.index_snapshot.config_hash,
                "sourceHash": task.source_hash,
                "vectorDimensions": command.index_snapshot.embedding.dimensions,
                "visionVectorDimensions": vision_dimensions,
                "createdAt": datetime.now(UTC),
                "entries": [
                    {
                        "pointId": chunk.point_id,
                        "chunkOrdinal": chunk.ordinal,
                        "tokenCount": chunk.token_count,
                        "modality": chunk.modality,
                        "mediaHash": chunk.media_hash,
                        "location": chunk.location.model_dump(
                            by_alias=True, mode="json"
                        ),
                    }
                    for chunk in prepared.chunks
                ],
            }
        )

    def _embedding_usage(
        self,
        command: KnowledgeJobCommand,
        profile: EmbeddingProfile,
        prompt_tokens: int,
        total_tokens: int,
        modality: str,
    ) -> ExecutionUsageItem:
        """将 Embedding 调用的 Token 统计转为 ExecutionUsageItem。

        prompt_tokens==0 时回退为 UNKNOWN（供应商未上报），output_tokens 由 total - prompt 推导。"""
        profile_ref = profile.profile
        measurement: dict[str, object]
        if prompt_tokens == 0:
            measurement = {"unit": "UNKNOWN", "reason": "NOT_REPORTED"}
        else:
            measurement = {
                "unit": "TOKENS",
                "quality": "ACTUAL",
                "inputTokens": prompt_tokens,
                "outputTokens": max(total_tokens - prompt_tokens, 0),
            }
        return ExecutionUsageItem.model_validate(
            {
                "stage": "EMBEDDING",
                "callId": (
                    f"embedding:{modality}:{command.attempt_id}:"
                    f"{profile_ref.id}:{profile_ref.revision}"
                ),
                "attemptId": command.attempt_id,
                "profile": profile_ref.model_dump(by_alias=True, mode="json"),
                "measurement": measurement,
            }
        )

    async def _cleanup(self, command: KnowledgeJobCommand) -> None:
        """清单发布失败时尝试清理 Qdrant 中已写入的片段，避免残留孤立向量点。

        清理失败不抛异常（可能已部分成功），仅记录日志。"""
        try:
            await self.vector_store.delete_generation(command)
        except VectorStoreError:
            pass

    @staticmethod
    def _failed(
        error_code: str, usage_items: list[ExecutionUsageItem]
    ) -> KnowledgeFailedEvent:
        """将向量/写入阶段的异常转为 Kafka FAILED 事件，保留已消耗的用量项。

        Java 端解析 FAILED 事件时会提取 usage_items 进行对账和额度退还。"""
        return KnowledgeFailedEvent.model_validate(
            {
                "eventType": "FAILED",
                "errorCode": error_code,
                "usageItems": [
                    item.model_dump(by_alias=True, mode="json")
                    for item in usage_items
                ],
            }
        )