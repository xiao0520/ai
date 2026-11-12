"""Python AI 执行服务的 FastAPI 组合根。

本模块是服务的入口点，负责：
  - 装配 — FastAPI lifespan 中初始化所有共享客户端和 Provider 注册表。
  - 路由 — 注册 chat、knowledge_retrieval、rag_chat 三个内部 API 路由。
  - Kafka — 可选启动知识库 Kafka Worker，消费命令 Topic。
  - 停机 — lifespan finally 块中先停止 Worker，再关闭 HTTP/Qdrant 连接。

装配顺序：
  1. 内部认证器 — InternalRequestAuthenticator，校验 Java HMAC 签名。
  2. 模型 Provider — Chat / Embedding / Vision / Rerank 注册表和工厂。
  3. 基础设施客户端 — KnowledgeScopeClient（内部 HTTP）、Qdrant 向量库。
  4. Kafka 组件（可选） — ArtifactClient、Parser、Chunker、Pipeline、Executor、Worker。
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx2
from fastapi import FastAPI, HTTPException
from qdrant_client import AsyncQdrantClient

from ai_service.agents.mcp_chat import McpChatAgent
from ai_service.api.chat import router as chat_router
from ai_service.api.contract_review import router as contract_review_router
from ai_service.api.knowledge_retrieval import router as knowledge_retrieval_router
from ai_service.api.rag_chat import router as rag_chat_router
from ai_service.consumers.knowledge_kafka import KnowledgeKafkaWorker
from ai_service.domain.contract_parsing import ContractSourceParser
from ai_service.domain.knowledge_execution import (
    KnowledgeIngestionExecutor,
    KnowledgeMaintenanceExecutor,
    KnowledgeRoutingExecutor,
)
from ai_service.infra.internal_auth import InternalRequestAuthenticator
from ai_service.infra.knowledge_artifact_client import JavaKnowledgeArtifactClient
from ai_service.infra.knowledge_kafka_settings import KnowledgeKafkaSettings
from ai_service.infra.knowledge_scope_client import JavaKnowledgeScopeClient
from ai_service.infra.settings import get_settings
from ai_service.infra.vector_store import QdrantKnowledgeVectorStore
from ai_service.providers.ark_multimodal_embedding import (
    ARK_MULTIMODAL_EMBEDDINGS,
    ArkMultimodalEmbeddingProvider,
)
from ai_service.providers.cohere_rerank import COHERE_RERANK_V2, CohereV2RerankProvider
from ai_service.providers.dashscope_rerank import (
    DASHSCOPE_TEXT_RERANK,
    DashScopeTextRerankProvider,
)
from ai_service.providers.embedding_protocol import EmbeddingProviderFactory
from ai_service.providers.factory import (
    API_MODE_CHAT_COMPLETIONS,
    API_MODE_RESPONSES,
    OPENAI_COMPATIBLE_PROVIDER_CODES,
    ChatProviderFactory,
    RoutedChatProvider,
)
from ai_service.providers.langchain_openai import LangChainOpenAiChatProvider
from ai_service.providers.openai_compatible_embedding import (
    OPENAI_EMBEDDINGS,
    OpenAiCompatibleEmbeddingProvider,
)
from ai_service.providers.openai_compatible_vision import OpenAiCompatibleVisionProvider
from ai_service.providers.openai_responses import OpenAiResponsesChatProvider
from ai_service.providers.protocol import ChatStreamProvider
from ai_service.providers.rerank_protocol import RerankProviderFactory
from ai_service.providers.vision_protocol import VisionProviderFactory
from ai_service.providers.volcengine_knowledge_rerank import (
    VOLCENGINE_KNOWLEDGE_RERANK,
    VolcengineKnowledgeRerankProvider,
)
from ai_service.rag.chunking import StructureAwareChunker
from ai_service.rag.ingestion import (
    ParsingKnowledgeIngestionPipeline,
)
from ai_service.rag.multimodal import MultimodalDocumentProcessor
from ai_service.rag.parser_limits import ParserLimits
from ai_service.rag.parsers import KnowledgeDocumentParser
from ai_service.rag.vector_ingestion import VectorKnowledgeChunkPipeline

logger = logging.getLogger("uvicorn.error")


# ============================================================================
# FastAPI 生命周期
# ============================================================================


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """装配共享 HTTP/Kafka 客户端，并在停机时先停止任务再释放连接。

    启动阶段按顺序初始化：
      1. 内部认证器 — HMAC 验签 + nonce 防重放。
      2. 模型 Provider 注册表 — Chat / Embedding / Vision / Rerank。
      3. 基础设施客户端 — ScopeClient（内部 HTTP） / Qdrant / Kafka（可选）。

    停机阶段按逆序释放：
      1. Kafka Worker.stop() — 停止消费，不 commit 未完成消息。
      2. HTTP 客户端关闭 — Artifact > Qdrant > ScopeClient > Provider。
    """
    settings = get_settings()

    # ========================================================================
    # 1. 内部认证器
    # ========================================================================
    app.state.internal_request_authenticator = InternalRequestAuthenticator(settings)

    # ========================================================================
    # 2. 模型 Provider 注册表
    # ========================================================================
    # 流式模型请求会跨越多个增量；复用连接池避免每条消息重复建连。
    provider_http_client = httpx2.AsyncClient(
        timeout=httpx2.Timeout(settings.provider_timeout_seconds),
    )
    app.state.provider_http_client = provider_http_client

    # --- Chat Provider ---
    # OpenAI 兼容协议适配器（豆包 / 百炼 / Kimi / 智谱 / DeepSeek）
    openai_compatible_provider = LangChainOpenAiChatProvider(settings, provider_http_client)
    app.state.structured_model_provider = openai_compatible_provider
    # OpenAI Responses 协议适配器（当前仅 doubao 完成验证）
    responses_provider = OpenAiResponsesChatProvider(settings, provider_http_client)
    # 注册策略表：(provider_code, api_mode) → ChatStreamProvider
    provider_strategies: dict[tuple[str, str], ChatStreamProvider] = {
        (provider_code, API_MODE_CHAT_COMPLETIONS): openai_compatible_provider
        for provider_code in OPENAI_COMPATIBLE_PROVIDER_CODES
    }
    # 当前仅方舟完成 Responses 兼容验证，其他厂商不做未经验证的协议透传。
    provider_strategies[("doubao", API_MODE_RESPONSES)] = responses_provider
    provider_factory = ChatProviderFactory(provider_strategies)
    app.state.chat_provider = RoutedChatProvider(provider_factory)
    app.state.mcp_chat_agent = McpChatAgent(settings, provider_http_client)

    # --- Embedding Provider ---
    # 模型管理决定供应商和 API 模式；注册表只声明当前 Python 已实现的适配器。
    ark_embedding_provider = ArkMultimodalEmbeddingProvider(settings)
    openai_embedding_provider = OpenAiCompatibleEmbeddingProvider(settings)
    embedding_provider_factory = EmbeddingProviderFactory(
        {
            ("doubao", ARK_MULTIMODAL_EMBEDDINGS): ark_embedding_provider,
            ("*", OPENAI_EMBEDDINGS): openai_embedding_provider,
        }
    )

    # --- Vision Provider ---
    vision_provider = OpenAiCompatibleVisionProvider(settings)
    vision_provider_factory = VisionProviderFactory(
        {
            ("*", API_MODE_RESPONSES): vision_provider,
            ("*", API_MODE_CHAT_COMPLETIONS): vision_provider,
        }
    )

    # --- Rerank Provider ---
    rerank_provider_factory = RerankProviderFactory(
        {
            ("qianwen", DASHSCOPE_TEXT_RERANK): DashScopeTextRerankProvider(
                settings, provider_http_client
            ),
            ("doubao", VOLCENGINE_KNOWLEDGE_RERANK): VolcengineKnowledgeRerankProvider(
                settings, provider_http_client
            ),
            ("*", COHERE_RERANK_V2): CohereV2RerankProvider(settings, provider_http_client),
        }
    )

    app.state.embedding_provider_factory = embedding_provider_factory
    app.state.vision_provider_factory = vision_provider_factory
    app.state.rerank_provider_factory = rerank_provider_factory

    # ========================================================================
    # 3. 基础设施客户端
    # ========================================================================
    # 检索链始终需要 Java 复核和 Qdrant；客户端构造不会在启动时发起网络连接。
    scope_http_client = httpx2.AsyncClient(
        timeout=httpx2.Timeout(settings.artifact_read_timeout_seconds),
        follow_redirects=False,
        trust_env=False,
    )
    app.state.knowledge_scope_client = JavaKnowledgeScopeClient(settings, scope_http_client)

    # --- Qdrant 向量库 ---
    qdrant_client = AsyncQdrantClient(
        url=settings.qdrant_url,
        api_key=(
            settings.qdrant_api_key.get_secret_value()
            if settings.qdrant_api_key is not None
            else None
        ),
        timeout=settings.qdrant_timeout_seconds,
    )
    app.state.qdrant_client = qdrant_client

    # 合同审查与知识入库共用同一套受限文档解析器。
    parser = KnowledgeDocumentParser(
        ParserLimits(
            max_characters=settings.rag_parse_max_characters,
            max_blocks=settings.rag_parse_max_blocks,
            max_pdf_pages=settings.rag_pdf_max_pages,
            max_office_units=settings.rag_office_max_units,
            max_spreadsheet_rows=settings.rag_spreadsheet_max_rows,
            max_images=settings.rag_image_max_count,
            max_image_bytes=settings.rag_image_max_bytes,
            max_image_pixels=settings.rag_image_max_pixels,
        )
    )
    app.state.document_parser = parser
    app.state.contract_source_parser = ContractSourceParser(parser.limits, vision_provider_factory)

    # --- Kafka 知识库入库组件（可选）---
    kafka_settings = KnowledgeKafkaSettings()
    artifact_http_client: httpx2.AsyncClient | None = None
    worker: KnowledgeKafkaWorker | None = None
    if kafka_settings.enabled:
        # Artifact HTTP 客户端：用于下载源文件和上传产物清单
        artifact_http_client = httpx2.AsyncClient(
            timeout=httpx2.Timeout(
                settings.artifact_read_timeout_seconds,
                connect=settings.artifact_connect_timeout_seconds,
            ),
            follow_redirects=False,
            trust_env=False,
        )
        artifact_client = JavaKnowledgeArtifactClient(settings, artifact_http_client)

        # Qdrant 向量写入适配器
        vector_store = QdrantKnowledgeVectorStore(
            qdrant_client,
            batch_size=settings.rag_qdrant_batch_size,
            timeout_seconds=settings.qdrant_timeout_seconds,
        )

        # 入库流水线：解析 → 切片 → 向量/图片 → 产物清单
        pipeline = ParsingKnowledgeIngestionPipeline(
            parser,
            StructureAwareChunker(),
            VectorKnowledgeChunkPipeline(
                embedding_provider_factory,
                vector_store,
                artifact_client,
            ),
            MultimodalDocumentProcessor(vision_provider_factory),
        )

        # 执行器：按任务类型路由到入库或维护
        executor = KnowledgeRoutingExecutor(
            KnowledgeIngestionExecutor(artifact_client, pipeline),
            KnowledgeMaintenanceExecutor(vector_store),
        )

        # Kafka Worker：消费命令 → 执行 → 提交 offset
        worker = KnowledgeKafkaWorker(kafka_settings, executor)

    app.state.artifact_http_client = artifact_http_client
    app.state.knowledge_worker = worker

    # ========================================================================
    # 启动 —— 所有客户端就绪后启动 Kafka Worker
    # ========================================================================
    try:
        if worker is not None:
            await worker.start()
        logger.info("MJN-AI Python服务启动成功")
        yield
    finally:
        # ========================================================================
        # 停机 —— 先停 Worker 再关闭连接（逆序释放）
        # ========================================================================
        if worker is not None:
            await worker.stop()
        if artifact_http_client is not None:
            await artifact_http_client.aclose()
        await qdrant_client.close()
        await scope_http_client.aclose()
        await provider_http_client.aclose()


# ============================================================================
# FastAPI 应用与路由注册
# ============================================================================

app = FastAPI(title="RuoYi-AI Service", version="0.2.0", lifespan=lifespan)
app.include_router(chat_router)
app.include_router(contract_review_router)
app.include_router(knowledge_retrieval_router)
app.include_router(rag_chat_router)


# ============================================================================
# 健康检查
# ============================================================================


@app.get("/health", tags=["health"])
async def health() -> dict[str, str]:
    """返回不暴露配置和依赖细节的存活状态。"""
    return {"status": "ok"}


@app.get("/ready", tags=["health"])
async def ready() -> dict[str, str]:
    """启用 Kafka 时检查消费循环；异常退出必须返回不可就绪。"""
    worker: KnowledgeKafkaWorker | None = getattr(app.state, "knowledge_worker", None)
    if worker is not None and not worker.healthy:
        raise HTTPException(status_code=503, detail="DEPENDENCY_UNAVAILABLE")
    return {"status": "ready"}
