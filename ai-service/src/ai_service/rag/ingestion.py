"""知识库解析、视觉理解与切片流水线。

职责：
  - ParsingKnowledgeIngestionPipeline — LangChain Runnable 编排的三阶段流水线：
    （1）解析（parse_stage）— 保留原文件结构和来源位置。
    （2）视觉理解（multimodal_stage）— 仅 MULTIMODAL 模式调用视觉模型。
    （3）切片（chunk_stage）— CPU 切块并转换为 LangChain Document。
  - 完成后交给 next_stage（下游向量化阶段）。

关键约束：
  - 所有处理仅使用命令快照；不读取业务数据库或本地模型路由。
  - 每个阶段异常映射为 KnowledgeFailedEvent 稳定错误码。
  - 切片完成后核验 Token 预算（require_budget）。
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from typing import Protocol

from langchain_core.documents import Document
from langchain_core.runnables import Runnable, RunnableLambda

from ai_service.contracts.knowledge import ParseStats
from ai_service.contracts.knowledge_kafka import (
    KnowledgeDocumentTask,
    KnowledgeFailedEvent,
    KnowledgeJobCommand,
    KnowledgeProgressEvent,
)
from ai_service.contracts.rag_types import ExecutionUsageItem
from ai_service.domain.knowledge_execution import (
    DownloadedKnowledgeSource,
    KnowledgeExecutionContext,
    KnowledgeTerminal,
    ProgressCallback,
)
from ai_service.providers.errors import (
    ModelConfigurationError,
    ModelProviderError,
    ModelProviderTimeoutError,
)
from ai_service.rag.budget import require_budget
from ai_service.rag.chunking import StructureAwareChunker
from ai_service.rag.langchain_documents import knowledge_chunks_to_documents
from ai_service.rag.models import (
    KnowledgeChunk,
    KnowledgeProcessingError,
    ParsedDocument,
    ParsedImage,
)
from ai_service.rag.multimodal import MultimodalDocumentProcessor
from ai_service.rag.parsers import KnowledgeDocumentParser

LOGGER = logging.getLogger(__name__)

# ============================================================================
# 不可变数据类
# ============================================================================


@dataclass(frozen=True, slots=True)
class PreparedKnowledgeDocument:
    """解析、视觉处理、切片及验收统计已完成的不可变文档批次。"""

    chunks: tuple[KnowledgeChunk, ...]
    parse_stats: ParseStats
    images: tuple[ParsedImage, ...] = ()
    usage_items: tuple[ExecutionUsageItem, ...] = ()
    documents: tuple[Document, ...] = ()


@dataclass(frozen=True, slots=True)
class _IngestionInput:
    """一次 LCEL 入库准备链的不可变输入。"""

    command: KnowledgeJobCommand
    source: DownloadedKnowledgeSource
    source_name: str
    report_progress: ProgressCallback


@dataclass(frozen=True, slots=True)
class _ParsedState:
    """解析及可选视觉阶段之间传递的强类型状态。"""

    request: _IngestionInput
    document: ParsedDocument
    usage_items: tuple[ExecutionUsageItem, ...] = ()


# ============================================================================
# 流水线协议
# ============================================================================


class KnowledgeChunkPipeline(Protocol):
    """接收已完成来源定位和哈希的片段，负责后续向量与产物阶段。"""

    async def execute(
        self,
        command: KnowledgeJobCommand,
        prepared: PreparedKnowledgeDocument,
        context: KnowledgeExecutionContext,
        report_progress: ProgressCallback,
    ) -> KnowledgeTerminal:
        """处理不可变片段并返回真实终态。"""
        ...


# ============================================================================
# 三阶段解析流水线
# ============================================================================


class ParsingKnowledgeIngestionPipeline:
    """由 LangChain Runnable 编排解析、视觉理解和稳定切片。"""

    def __init__(
        self,
        parser: KnowledgeDocumentParser,
        chunker: StructureAwareChunker,
        next_stage: KnowledgeChunkPipeline,
        multimodal_processor: MultimodalDocumentProcessor | None = None,
    ) -> None:
        """装配解析、可选视觉理解和切片节点，下游阶段由注入的流水线负责。"""
        self.parser = parser
        self.chunker = chunker
        self.next_stage = next_stage
        self.multimodal_processor = multimodal_processor
        self._chain: Runnable[_IngestionInput, PreparedKnowledgeDocument] = (
            RunnableLambda(self._parse_stage)
            | RunnableLambda(self._multimodal_stage)
            | RunnableLambda(self._chunk_stage)
        )

    async def execute(
        self,
        command: KnowledgeJobCommand,
        source: DownloadedKnowledgeSource,
        context: KnowledgeExecutionContext,
        report_progress: ProgressCallback,
    ) -> KnowledgeTerminal:
        """所有处理只使用命令快照；不读取业务数据库或本地模型路由。"""
        if not isinstance(command.task, KnowledgeDocumentTask):
            return self._failed("INDEX_MISMATCH")
        processing = command.document_processing_snapshot
        if processing is None:
            return self._failed("INDEX_MISMATCH")
        if processing.processing_mode == "MULTIMODAL" and self.multimodal_processor is None:
            return self._failed("EXECUTOR_NOT_READY")
        source_name = command.task.source_name or f"document-{command.task.document_id}"
        try:
            prepared = await self._chain.ainvoke(
                _IngestionInput(command, source, source_name, report_progress)
            )
        except KnowledgeProcessingError as error:
            LOGGER.exception(
                "Knowledge ingestion processing failed: job_id=%s request_id=%s "
                "attempt_id=%s document_id=%s error_code=%s measured_tokens=%s "
                "planned_tokens=%s max_tokens=%s",
                command.job_id,
                command.request_id,
                command.attempt_id,
                command.task.document_id,
                error.error_code,
                error.measured_tokens,
                error.planned_tokens,
                error.max_tokens,
            )
            return self._failed(error.error_code, error.usage_items)
        except ModelConfigurationError:
            LOGGER.exception(
                "Knowledge ingestion model configuration failed: job_id=%s "
                "request_id=%s attempt_id=%s document_id=%s",
                command.job_id,
                command.request_id,
                command.attempt_id,
                command.task.document_id,
            )
            return self._failed("MODEL_UNAVAILABLE")
        except ModelProviderTimeoutError:
            LOGGER.exception(
                "Knowledge ingestion model request timed out: job_id=%s request_id=%s "
                "attempt_id=%s document_id=%s",
                command.job_id,
                command.request_id,
                command.attempt_id,
                command.task.document_id,
            )
            return self._failed("TIMEOUT")
        except ModelProviderError:
            LOGGER.exception(
                "Knowledge ingestion model request failed: job_id=%s request_id=%s "
                "attempt_id=%s document_id=%s",
                command.job_id,
                command.request_id,
                command.attempt_id,
                command.task.document_id,
            )
            return self._failed("MODEL_UNAVAILABLE")
        return await self.next_stage.execute(command, prepared, context, report_progress)

    async def _parse_stage(self, request: _IngestionInput) -> _ParsedState:
        """解析阶段保留原文件结构和来源位置。"""
        await request.report_progress(
            KnowledgeProgressEvent(eventType="PROGRESS", stage="PARSING", progress=15)
        )
        processing = request.command.document_processing_snapshot
        if processing is None:
            raise KnowledgeProcessingError("INDEX_MISMATCH")
        document = await self.parser.parse(
            request.source,
            request.source_name,
            request.command.index_snapshot.parser_revision,
            extract_images=processing.processing_mode == "MULTIMODAL",
        )
        await request.report_progress(
            KnowledgeProgressEvent(eventType="PROGRESS", stage="PARSING", progress=25)
        )
        return _ParsedState(request=request, document=document)

    async def _multimodal_stage(self, state: _ParsedState) -> _ParsedState:
        """仅多模态快照调用视觉模型；纯文本文档原样进入切片。"""
        processing = state.request.command.document_processing_snapshot
        if processing is None or processing.processing_mode != "MULTIMODAL":
            return state
        if self.multimodal_processor is None:
            raise KnowledgeProcessingError("INDEX_MISMATCH")
        document, usage_items = await self.multimodal_processor.process(
            state.request.command, state.document
        )
        return _ParsedState(state.request, document, usage_items)

    async def _chunk_stage(self, state: _ParsedState) -> PreparedKnowledgeDocument:
        """CPU 切片在线程中执行，随后转换为 LangChain Document。"""
        chunks = await asyncio.to_thread(
            self.chunker.split, state.request.command, state.document
        )
        require_budget(
            state.request.command,
            state.usage_items,
            planned_tokens=sum(chunk.token_count for chunk in chunks),
        )
        await state.request.report_progress(
            KnowledgeProgressEvent(eventType="PROGRESS", stage="PARSING", progress=35)
        )
        ocr_characters = sum(
            len(block.text) for block in state.document.blocks if block.modality == "IMAGE"
        )
        return PreparedKnowledgeDocument(
            chunks=chunks,
            images=state.document.images,
            usage_items=state.usage_items,
            documents=knowledge_chunks_to_documents(chunks),
            parse_stats=ParseStats.model_validate(
                {
                    "pageCount": state.document.page_count,
                    "blockCount": len(state.document.blocks),
                    "characterCount": state.document.character_count,
                    "imageCount": len(state.document.images),
                    "ocrCharacterCount": ocr_characters,
                    "skippedBlockCount": state.document.skipped_block_count,
                    "skippedImageCount": state.document.skipped_image_count,
                    "truncated": state.document.truncated,
                }
            ),
        )

    @staticmethod
    def _failed(
        error_code: str, usage_items: tuple[ExecutionUsageItem, ...] = ()
    ) -> KnowledgeFailedEvent:
        """将解析/视觉阶段的异常转化为 Kafka FAILED 事件，保留已发生的用量。

        usage_items 记录失败前已消耗的模型调用（如部分视觉处理已完成），
        用于 Java 端对账而非直接丢弃。"""
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


# ============================================================================
# 守卫流水线：阻止未接入下游阶段时伪造成功
# ============================================================================


class UnavailableKnowledgeChunkPipeline:
    """解析切片完成后明确阻止未实现阶段伪造成功。"""

    async def execute(
        self,
        command: KnowledgeJobCommand,
        prepared: PreparedKnowledgeDocument,
        context: KnowledgeExecutionContext,
        report_progress: ProgressCallback,
    ) -> KnowledgeTerminal:
        """始终返回 EXECUTOR_NOT_READY，防止未接入向量阶段时静默吞掉片段。"""
        return KnowledgeFailedEvent(
            eventType="FAILED", errorCode="EXECUTOR_NOT_READY", usageItems=[]
        )