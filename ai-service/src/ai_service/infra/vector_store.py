"""Qdrant 技术产物适配；业务发布状态仍由 Java 验收后决定。

职责：
  - QdrantKnowledgeVectorStore — 按 IndexSnapshot 写入文本 Dense、BM25 Sparse 和可选图片 Dense。
  - 稳定 upsert 后回读核验全部 point，任一部分失败时清理当前 generation。
  - delete_generation / delete_generation_id — 按知识库、索引和 generation 三重条件精确删除。
  - reconcile_generations — 盘点当前知识库+索引版本下所有 generation，供 Java 复核后下发精确 CLEANUP。

关键约束：
  - 写入前确保 collection 存在（collection_name 来自 snapshot）。
  - 所有异常归一化为 VectorStoreError（VECTOR_UNAVAILABLE / INDEX_MISMATCH）。
  - RECONCILE 只返回盘点结果，不执行删除（删除指令由 Java 通过 CLEANUP 单独下发）。
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Literal

from langchain_qdrant import SparseVector
from qdrant_client import AsyncQdrantClient, models

from ai_service.contracts.knowledge_kafka import (
    KnowledgeDocumentTask,
    KnowledgeJobCommand,
    KnowledgeReconcileTask,
)
from ai_service.domain.knowledge_execution import GenerationMaintenanceResult
from ai_service.providers.embedding_protocol import EmbeddingBatch
from ai_service.rag.models import KnowledgeChunk

VectorStoreErrorCode = Literal["VECTOR_UNAVAILABLE", "INDEX_MISMATCH"]


class VectorStoreError(RuntimeError):
    """将 Qdrant 连接故障和索引结构冲突归一化为任务稳定错误码。"""

    def __init__(self, error_code: VectorStoreErrorCode) -> None:
        super().__init__(error_code)
        self.error_code = error_code


@dataclass(frozen=True, slots=True)
class VectorWriteResult:
    """已写入并回读核验的当前 generation 计数。

    :param dense_count: 写入的 dense（文本）向量总数
    :param sparse_count: 写入的 BM25 稀疏向量总数
    :param vision_count: 写入的图片 dense 向量总数（无图片时 = 0）"""

    dense_count: int
    sparse_count: int
    vision_count: int



class QdrantKnowledgeVectorStore:
    """按 IndexSnapshot 写入文本 dense、BM25 sparse 和可选图片 dense。"""

    def __init__(
        self,
        client: AsyncQdrantClient,
        *,
        batch_size: int,
        timeout_seconds: int,
    ) -> None:
        if batch_size < 1 or timeout_seconds < 1:
            raise ValueError("Qdrant 写入配置必须为正数")
        self.client = client
        self.batch_size = batch_size
        self.timeout_seconds = timeout_seconds

    async def write(
        self,
        command: KnowledgeJobCommand,
        chunks: tuple[KnowledgeChunk, ...],
        dense_batch: EmbeddingBatch,
        sparse_vectors: tuple[SparseVector, ...],
        vision_batch: EmbeddingBatch | None = None,
    ) -> VectorWriteResult:
        """稳定 upsert 后回读全部 point；任一部分失败时清理当前 generation。"""
        self._validate_batch(command, chunks, dense_batch, sparse_vectors, vision_batch)
        try:
            await self._ensure_collection(command)
        except VectorStoreError:
            raise
        except Exception as error:
            raise VectorStoreError("VECTOR_UNAVAILABLE") from error

        points = self._points(
            command, chunks, dense_batch, sparse_vectors, vision_batch
        )
        # 只在确实写入过 point 后清理，避免失败时误删此前的 generation。
        wrote_points = False
        try:
            for start in range(0, len(points), self.batch_size):
                batch = points[start : start + self.batch_size]
                await self.client.upsert(
                    collection_name=command.index_snapshot.collection_name,
                    points=batch,
                    wait=True,
                    timeout=self.timeout_seconds,
                )
                wrote_points = True
            await self._verify(command, chunks)
        except asyncio.CancelledError:
            if wrote_points:
                await self._best_effort_cleanup(command)
            raise
        except VectorStoreError:
            if wrote_points:
                await self._best_effort_cleanup(command)
            raise
        except Exception as error:
            if wrote_points:
                await self._best_effort_cleanup(command)
            raise VectorStoreError("VECTOR_UNAVAILABLE") from error
        image_chunks = sum(chunk.modality == "IMAGE" for chunk in chunks)
        return VectorWriteResult(
            dense_count=len(chunks),
            sparse_count=len(chunks),
            vision_count=image_chunks,
        )

    async def delete_generation(self, command: KnowledgeJobCommand) -> int:
        """只删除当前执行 generation，供失败或取消的入库 attempt 收尾。"""
        return await self.delete_generation_id(command, command.generation_id)

    async def delete_generation_id(
        self, command: KnowledgeJobCommand, generation_id: str
    ) -> int:
        """按知识库、索引和 generation 三重条件删除，重复执行返回零。"""
        snapshot = command.index_snapshot
        try:
            if not await self.client.collection_exists(snapshot.collection_name):
                return 0
            point_filter = self._generation_filter(command, generation_id)
            counted = await self.client.count(
                collection_name=snapshot.collection_name,
                count_filter=point_filter,
                exact=True,
                timeout=self.timeout_seconds,
            )
            await self.client.delete(
                collection_name=snapshot.collection_name,
                points_selector=models.FilterSelector(filter=point_filter),
                wait=True,
                timeout=self.timeout_seconds,
            )
            return int(counted.count)
        except Exception as error:
            raise VectorStoreError("VECTOR_UNAVAILABLE") from error

    async def reconcile_generations(
        self, command: KnowledgeJobCommand, task: KnowledgeReconcileTask
    ) -> GenerationMaintenanceResult:
        """只盘点 generation；Java 使用最新状态复核后另发精确 CLEANUP。"""
        snapshot = command.index_snapshot
        try:
            if not await self.client.collection_exists(snapshot.collection_name):
                return GenerationMaintenanceResult((), (), 0, 0)
            inventory = await self._generation_inventory(command)
            # RECONCILE 只返回盘点结果。Java 收到结果后使用最新发布成员和活跃
            # attempt 再复核，随后为确认的孤儿下发精确 CLEANUP，避免旧快照误删并发写入。
            return GenerationMaintenanceResult(
                observed_generation_ids=tuple(sorted(inventory, key=int)),
                deleted_generation_ids=(),
                observed_point_count=sum(inventory.values()),
                deleted_point_count=0,
            )
        except VectorStoreError:
            raise
        except Exception as error:
            raise VectorStoreError("VECTOR_UNAVAILABLE") from error

    async def _generation_inventory(
        self, command: KnowledgeJobCommand
    ) -> dict[str, int]:
        """滚动扫描当前知识库 + 索引版本下所有 point，按 generationId 分组计数。

        返回 {generation_id: point_count}，用于 Java 端比对活跃 generation。"""
        snapshot = command.index_snapshot
        inventory: dict[str, int] = {}
        offset: models.ExtendedPointId | None = None
        scope_filter = models.Filter(
            must=[
                models.FieldCondition(
                    key="metadata.knowledgeId",
                    match=models.MatchValue(value=command.knowledge_id),
                ),
                models.FieldCondition(
                    key="metadata.indexVersion",
                    match=models.MatchValue(value=snapshot.index_version),
                ),
            ]
        )
        while True:
            records, offset = await self.client.scroll(
                collection_name=snapshot.collection_name,
                scroll_filter=scope_filter,
                limit=self.batch_size,
                offset=offset,
                with_payload=["metadata.generationId"],
                with_vectors=False,
                timeout=self.timeout_seconds,
            )
            for record in records:
                payload = record.payload or {}
                metadata = payload.get("metadata")
                generation_id = (
                    metadata.get("generationId") if isinstance(metadata, dict) else None
                )
                if not isinstance(generation_id, str) or not generation_id.isdigit():
                    raise VectorStoreError("INDEX_MISMATCH")
                inventory[generation_id] = inventory.get(generation_id, 0) + 1
            if offset is None:
                return inventory

    def _generation_filter(
        self, command: KnowledgeJobCommand, generation_id: str
    ) -> models.Filter:
        """构造 knowledgeId + indexVersion + generationId 的三重 Qdrant 过滤条件。"""
        snapshot = command.index_snapshot
        return models.Filter(
            must=[
                models.FieldCondition(
                    key="metadata.knowledgeId",
                    match=models.MatchValue(value=command.knowledge_id),
                ),
                models.FieldCondition(
                    key="metadata.indexVersion",
                    match=models.MatchValue(value=snapshot.index_version),
                ),
                models.FieldCondition(
                    key="metadata.generationId",
                    match=models.MatchValue(value=generation_id),
                ),
            ]
        )

    def _validate_batch(
        self,
        command: KnowledgeJobCommand,
        chunks: tuple[KnowledgeChunk, ...],
        dense_batch: EmbeddingBatch,
        sparse_vectors: tuple[SparseVector, ...],
        vision_batch: EmbeddingBatch | None,
    ) -> None:
        """写入前全量校验：chunk 与三种向量数量/ID/维度对齐、图片 media_id 非空。

        同时验证 vision_batch 的 input_id 按 media_id 去重且与 chunk 一致。"""
        if not isinstance(command.task, KnowledgeDocumentTask) or not chunks:
            raise VectorStoreError("INDEX_MISMATCH")
        if len(chunks) != len(dense_batch.embeddings) or len(chunks) != len(sparse_vectors):
            raise VectorStoreError("INDEX_MISMATCH")
        expected_ids = [chunk.chunk_id for chunk in chunks]
        actual_ids = [embedding.input_id for embedding in dense_batch.embeddings]
        if actual_ids != expected_ids:
            raise VectorStoreError("INDEX_MISMATCH")
        dimensions = command.index_snapshot.embedding.dimensions
        if any(len(embedding.values) != dimensions for embedding in dense_batch.embeddings):
            raise VectorStoreError("INDEX_MISMATCH")
        if any(
            not vector.indices or len(vector.indices) != len(vector.values)
            for vector in sparse_vectors
        ):
            raise VectorStoreError("INDEX_MISMATCH")

        image_chunks = [chunk for chunk in chunks if chunk.modality == "IMAGE"]
        if any(
            chunk.media_id is None or chunk.media_hash is None for chunk in image_chunks
        ):
            raise VectorStoreError("INDEX_MISMATCH")
        # 多个图片切片可共享同一媒体，视觉向量按首次出现顺序去重后匹配。
        expected_media_ids = list(dict.fromkeys(chunk.media_id for chunk in image_chunks))
        if not expected_media_ids:
            if vision_batch is not None:
                raise VectorStoreError("INDEX_MISMATCH")
            return
        snapshot = command.index_snapshot
        if (
            vision_batch is None
            or snapshot.multimodal_embedding is None
            or snapshot.dense_vision_vector_name is None
        ):
            raise VectorStoreError("INDEX_MISMATCH")
        actual_media_ids = [item.input_id for item in vision_batch.embeddings]
        if actual_media_ids != expected_media_ids:
            raise VectorStoreError("INDEX_MISMATCH")
        vision_dimensions = snapshot.multimodal_embedding.dimensions
        if any(len(item.values) != vision_dimensions for item in vision_batch.embeddings):
            raise VectorStoreError("INDEX_MISMATCH")

    async def _ensure_collection(self, command: KnowledgeJobCommand) -> None:
        """按 IndexSnapshot 创建/校验 Qdrant 集合：dense 向量名 + 维度 + 距离匹配。

        集合不存在时创建后设置 generationId/documentId/documentVersionId/knowledgeId/indexVersion/modality
        六个 payload 关键词索引以加速过滤查询。"""
        snapshot = command.index_snapshot
        if not await self.client.collection_exists(snapshot.collection_name):
            vectors = {
                snapshot.dense_vector_name: models.VectorParams(
                    size=snapshot.embedding.dimensions,
                    distance=self._distance(snapshot.distance),
                )
            }
            if snapshot.dense_vision_vector_name is not None:
                multimodal = snapshot.multimodal_embedding
                if multimodal is None:
                    raise VectorStoreError("INDEX_MISMATCH")
                vectors[snapshot.dense_vision_vector_name] = models.VectorParams(
                    size=multimodal.dimensions,
                    distance=self._distance(snapshot.distance),
                )
            try:
                await self.client.create_collection(
                    collection_name=snapshot.collection_name,
                    vectors_config=vectors,
                    sparse_vectors_config={
                        snapshot.sparse_vector_name: models.SparseVectorParams(
                            modifier=models.Modifier.IDF
                        )
                    },
                    timeout=self.timeout_seconds,
                )
            except Exception:
                # 并发任务可能同时建集合；只有集合仍不存在时才传播创建异常。
                if not await self.client.collection_exists(snapshot.collection_name):
                    raise
        await self._validate_collection(command)
        for field in (
            "metadata.generationId",
            "metadata.documentId",
            "metadata.documentVersionId",
            "metadata.knowledgeId",
            "metadata.indexVersion",
            "metadata.modality",
        ):
            await self.client.create_payload_index(
                collection_name=snapshot.collection_name,
                field_name=field,
                field_schema=models.PayloadSchemaType.KEYWORD,
                wait=True,
                timeout=self.timeout_seconds,
            )

    async def _validate_collection(self, command: KnowledgeJobCommand) -> None:
        """回读 Qdrant 集合配置：dense 向量名/维度/距离、sparse IDF modifier 必须与快照一致。

        不一致通常是同一 knowledge_id 下有旧索引版本残留或手动修改了 Qdrant。"""
        snapshot = command.index_snapshot
        info = await self.client.get_collection(snapshot.collection_name)
        actual_dense = info.config.params.vectors
        actual_sparse = info.config.params.sparse_vectors
        if not isinstance(actual_dense, dict) or actual_sparse is None:
            raise VectorStoreError("INDEX_MISMATCH")
        expected_names = {snapshot.dense_vector_name}
        if snapshot.dense_vision_vector_name is not None:
            expected_names.add(snapshot.dense_vision_vector_name)
        if set(actual_dense) != expected_names or set(actual_sparse) != {
            snapshot.sparse_vector_name
        }:
            raise VectorStoreError("INDEX_MISMATCH")
        dense = actual_dense[snapshot.dense_vector_name]
        if (
            dense.size != snapshot.embedding.dimensions
            or dense.distance != self._distance(snapshot.distance)
        ):
            raise VectorStoreError("INDEX_MISMATCH")
        if snapshot.dense_vision_vector_name is not None:
            multimodal = snapshot.multimodal_embedding
            vision = actual_dense[snapshot.dense_vision_vector_name]
            if (
                multimodal is None
                or vision.size != multimodal.dimensions
                or vision.distance != self._distance(snapshot.distance)
            ):
                raise VectorStoreError("INDEX_MISMATCH")
        if actual_sparse[snapshot.sparse_vector_name].modifier != models.Modifier.IDF:
            raise VectorStoreError("INDEX_MISMATCH")

    def _points(
        self,
        command: KnowledgeJobCommand,
        chunks: tuple[KnowledgeChunk, ...],
        dense_batch: EmbeddingBatch,
        sparse_vectors: tuple[SparseVector, ...],
        vision_batch: EmbeddingBatch | None,
    ) -> list[models.PointStruct]:
        """将 chunk + 三种向量组装为 Qdrant PointStruct 列表：point_id = chunk.point_id。

        payload.metadata 写入 generationId、chunk 链、token_count、parser/splitter/tokenizer 版本
        和完整 location JSON；图片 chunk 额外挂载 vision dense 向量。"""
        task = command.task
        if not isinstance(task, KnowledgeDocumentTask):
            raise VectorStoreError("INDEX_MISMATCH")
        snapshot = command.index_snapshot
        vision_by_media_id = (
            {item.input_id: item.values for item in vision_batch.embeddings}
            if vision_batch is not None
            else {}
        )
        points: list[models.PointStruct] = []
        for chunk, dense, sparse in zip(
            chunks, dense_batch.embeddings, sparse_vectors, strict=True
        ):
            metadata = {
                "knowledgeId": command.knowledge_id,
                "indexVersion": snapshot.index_version,
                "documentId": task.document_id,
                "documentVersionId": task.document_version_id,
                "generationId": command.generation_id,
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
            }
            vectors: dict[str, models.Vector] = {
                snapshot.dense_vector_name: list(dense.values),
                snapshot.sparse_vector_name: models.SparseVector(
                    indices=sparse.indices,
                    values=sparse.values,
                ),
            }
            if chunk.modality == "IMAGE":
                if (
                    chunk.media_id is None
                    or snapshot.dense_vision_vector_name is None
                    or chunk.media_id not in vision_by_media_id
                ):
                    raise VectorStoreError("INDEX_MISMATCH")
                vectors[snapshot.dense_vision_vector_name] = list(
                    vision_by_media_id[chunk.media_id]
                )
            points.append(
                models.PointStruct(
                    id=chunk.point_id,
                    vector=vectors,
                    payload={"page_content": chunk.text, "metadata": metadata},
                )
            )
        return points

    async def _verify(
        self, command: KnowledgeJobCommand, chunks: tuple[KnowledgeChunk, ...]
    ) -> None:
        """写入后回读全部 point，逐一核验 dense/sparse/vision 向量非空且维度匹配。

        任意 point 缺失或向量结构与快照不一致即视为写入失败，触发 best-effort cleanup。"""
        snapshot = command.index_snapshot
        vector_names = [snapshot.dense_vector_name, snapshot.sparse_vector_name]
        if snapshot.dense_vision_vector_name is not None:
            vector_names.append(snapshot.dense_vision_vector_name)
        records = await self.client.retrieve(
            collection_name=snapshot.collection_name,
            ids=[chunk.point_id for chunk in chunks],
            with_payload=False,
            with_vectors=vector_names,
            timeout=self.timeout_seconds,
        )
        if len(records) != len(chunks):
            raise VectorStoreError("INDEX_MISMATCH")
        chunks_by_id = {chunk.point_id: chunk for chunk in chunks}
        if {str(record.id) for record in records} != set(chunks_by_id):
            raise VectorStoreError("INDEX_MISMATCH")
        for record in records:
            vectors = record.vector
            chunk = chunks_by_id[str(record.id)]
            if not isinstance(vectors, dict):
                raise VectorStoreError("INDEX_MISMATCH")
            dense = vectors.get(snapshot.dense_vector_name)
            sparse = vectors.get(snapshot.sparse_vector_name)
            if not isinstance(dense, list) or len(dense) != snapshot.embedding.dimensions:
                raise VectorStoreError("INDEX_MISMATCH")
            if not isinstance(sparse, models.SparseVector) or not sparse.indices:
                raise VectorStoreError("INDEX_MISMATCH")
            vision = (
                vectors.get(snapshot.dense_vision_vector_name)
                if snapshot.dense_vision_vector_name is not None
                else None
            )
            if chunk.modality == "IMAGE":
                multimodal = snapshot.multimodal_embedding
                if (
                    multimodal is None
                    or not isinstance(vision, list)
                    or len(vision) != multimodal.dimensions
                ):
                    raise VectorStoreError("INDEX_MISMATCH")
            elif vision is not None:
                raise VectorStoreError("INDEX_MISMATCH")

    async def _best_effort_cleanup(self, command: KnowledgeJobCommand) -> None:
        """写入中断后尝试删除当前 generation；删除失败不抛异常（避免覆盖原始错误）。"""
        try:
            await self.delete_generation(command)
        except VectorStoreError:
            pass

    @staticmethod
    def _distance(distance: str) -> models.Distance:
        """快照距离名称 → Qdrant Distance 枚举，未知值视为 INDEX_MISMATCH。"""
        values = {
            "COSINE": models.Distance.COSINE,
            "DOT": models.Distance.DOT,
            "EUCLID": models.Distance.EUCLID,
        }
        try:
            return values[distance]
        except KeyError as error:
            raise VectorStoreError("INDEX_MISMATCH") from error