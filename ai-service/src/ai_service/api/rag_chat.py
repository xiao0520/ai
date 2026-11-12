"""Java 控制面调用的 RAG 流式聊天入口。

本模块在基础聊天路由（chat.py）上扩展 RAG 能力：
  检索 — Embedding + Qdrant → 召回候选文档。
  重排 — RerankProvider → 按相关度精排候选。
  引用 — Citation 绑定不可变 SourceLocation，供 Java 候选复核。
  流式 — SSE 事件流扩展了 citation/completed_rag 事件类型。

与基础聊天的差异：
  - 请求体增加 RagExecutionScope（检索范围 + 索引快照）。
  - 流中包含 RagCitationPayload 事件（检索后、生成前）。
  - 异常处理携带 execution_usage（已发生的模型用量）。
  - 请求体上限 4MB（基础聊天无此限制，因为 RAG 内含索引快照）。
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncGenerator
from typing import Annotated, cast

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, ValidationError
from qdrant_client import AsyncQdrantClient

from ai_service.api.chat import require_internal_request
from ai_service.contracts.chat import DeltaPayload, KeepalivePayload, Usage
from ai_service.contracts.rag_chat import (
    RagChatStreamRequest,
    RagCitationPayload,
    RagCompletedPayload,
    RagFailedPayload,
)
from ai_service.contracts.rag_types import ErrorCode, InternalError
from ai_service.infra.knowledge_scope_client import (
    KnowledgeScopeClient,
    KnowledgeScopeClientError,
)
from ai_service.infra.settings import Settings, get_settings
from ai_service.mcp.authorized_tools import (
    McpCredentialMissing,
    McpToolRejected,
    McpToolTimeout,
)
from ai_service.providers.embedding_protocol import EmbeddingProviderFactory
from ai_service.providers.errors import (
    ModelConfigurationError,
    ModelProviderAuthenticationError,
    ModelProviderBudgetError,
    ModelProviderError,
    ModelProviderRateLimitError,
    ModelProviderRequestError,
    ModelProviderTimeoutError,
)
from ai_service.providers.protocol import ChatStreamProvider
from ai_service.providers.rerank_protocol import RerankProviderFactory
from ai_service.rag.context import ContextSelectionError
from ai_service.rag.execution import AuthorizedRetrievalChainError
from ai_service.rag.pipeline import LangChainRagPipeline, RagPipelineError
from ai_service.rag.reranking import GroupedRerankError
from ai_service.rag.retrieval import AuthorizedRetrievalError

# ============================================================================
# 路由与常量定义
# ============================================================================

router = APIRouter(prefix="/internal/ai/v1", tags=["internal-rag"])
LOGGER = logging.getLogger(__name__)

# RAG SSE 事件流所有负载类型的联合，扩展了基础聊天的 citation / completed_rag / failed_rag
RagSsePayload = (
    DeltaPayload
    | Usage
    | KeepalivePayload
    | RagCitationPayload
    | RagCompletedPayload
    | RagFailedPayload
)


# ============================================================================
# FastAPI 依赖注入
# ============================================================================


def get_rag_pipeline(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
) -> LangChainRagPipeline:
    """使用生命周期级客户端装配单次无状态 RAG 流。

    依赖组件均从 app.state 获取（启动时注入）：
      - KnowledgeScopeClient — 查询 Java 知识库范围。
      - EmbeddingProviderFactory / RerankProviderFactory — 向量化和重排模型工厂。
      - ChatStreamProvider — 生成模型流。
      - AsyncQdrantClient — Qdrant 向量库异步客户端。
    timeout_seconds / max_concurrency 从 Settings 读取，控制检索和向量化并发度。
    """
    return LangChainRagPipeline(
        cast(KnowledgeScopeClient, request.app.state.knowledge_scope_client),
        cast(EmbeddingProviderFactory, request.app.state.embedding_provider_factory),
        cast(RerankProviderFactory, request.app.state.rerank_provider_factory),
        cast(ChatStreamProvider, request.app.state.chat_provider),
        cast(AsyncQdrantClient, request.app.state.qdrant_client),
        timeout_seconds=settings.qdrant_timeout_seconds,
        max_concurrency=settings.rag_embedding_max_concurrency,
        min_dense_score=settings.rag_min_dense_score,
        min_rerank_score=settings.rag_min_rerank_score,
        tool_provider=cast(ChatStreamProvider, request.app.state.mcp_chat_agent),
    )


# ============================================================================
# HTTP 端点
# ============================================================================


@router.post("/chat/rag/stream", response_model=None)
async def stream_rag_chat(
    request: Request,
    _: Annotated[None, Depends(require_internal_request)],
    pipeline: Annotated[LangChainRagPipeline, Depends(get_rag_pipeline)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> StreamingResponse | JSONResponse:
    """执行 Java 已授权的检索、生成和引用复核，并返回标准 SSE。

    处理流程：
      1. 解析原始请求体 → RagChatStreamRequest（大小上限 4MB，含索引快照）。
      2. 返回 StreamingResponse，流式输出 RAG SSE 事件。
      3. 异常在 _event_stream 内映射为 RagFailedPayload（含 execution_usage）。

    与基础聊天的关键差异：
      - RagChatStreamRequest 包含 RagExecutionScope（检索范围与索引快照）。
      - 流中包含 RagCitationPayload（引用事件，携带候选复核元信息）。
      - 异常终态返回 RagFailedPayload（携带 execution_usage 供 Java 结算）。
    """
    # --- 请求体解析与大小校验 ---
    raw_body = await request.body()
    if not raw_body or len(raw_body) > 4 * 1024 * 1024:
        return _error_response("unknown", "VALIDATION", False, 422)
    try:
        body = RagChatStreamRequest.model_validate_json(raw_body)
    except ValidationError:
        return _error_response("unknown", "VALIDATION", False, 422)

    return StreamingResponse(
        _event_stream(body, request, pipeline, settings),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",  # 禁止中间代理缓存 SSE 流
            "X-Accel-Buffering": "no",  # 禁止 Nginx 缓冲
        },
    )


# ============================================================================
# SSE 事件流生成器
# ============================================================================


async def _event_stream(
    body: RagChatStreamRequest,
    request: Request,
    pipeline: LangChainRagPipeline,
    settings: Settings,
) -> AsyncGenerator[str]:
    """检索等待阶段发送 keepalive，异常终态带回已经发生的用量。

    与 chat.py 的 _event_stream 设计类似，差异在于：
      - 载荷类型为 BaseModel（扩展了引用和完成事件）。
      - 异常终态使用 RagFailedPayload（携带 execution_usage）。
      - 流关闭时 pipeline.provider.close() 归还 Provider 连接。

    流处理流程：
      1. 创建异步任务拉取下一个事件。
      2. 客户端断连时立即退出。
      3. 用 shield 保护进行中的取流任务，等待超时只发送 keepalive。
      4. 收到 completed 事件后正常结束。
      5. 异常映射为 failed 事件，附带 pipeline.usage（已发生的用量）。
    """
    stream = pipeline.stream(body)
    pending_item: asyncio.Task[tuple[str, object]] | None = None
    try:
        while True:
            # 客户端断连 → 立即停止
            if await request.is_disconnected():
                return

            # 惰化：第一个事件出来前不创建 task
            if pending_item is None:
                pending_item = asyncio.create_task(anext(stream))

            try:
                event_name, payload = await asyncio.wait_for(
                    asyncio.shield(pending_item),
                    timeout=settings.provider_keepalive_seconds,
                )
                pending_item = None

                # 类型守卫：非 BaseModel 的载荷视为流水线内部错误
                if not isinstance(payload, BaseModel):
                    raise RagPipelineError("INTERNAL")

                yield _encode_event(event_name, payload)
                if event_name == "completed":
                    return

            except TimeoutError:
                # 检索/模型调用未完成 → 发送 keepalive
                yield _encode_event("keepalive", KeepalivePayload(request_id=body.request_id))

    except asyncio.CancelledError:
        raise

    except Exception as error:
        # 映射异常为稳定错误码，携带已发生的用量
        error_code = _error_code(error)
        # INTERNAL 异常打印完整堆栈，已知异常仅记录摘要
        if error_code == "INTERNAL":
            LOGGER.exception(
                "RAG 聊天发生未分类异常：request_id=%s, error_type=%s",
                body.request_id,
                type(error).__name__,
            )
        else:
            LOGGER.warning(
                "RAG 聊天失败：request_id=%s, error_code=%s, error_type=%s",
                body.request_id,
                error_code,
                type(error).__name__,
            )

        # failed 事件携带 execution_usage，Java 据此结算已发生的模型调用费用
        yield _encode_event(
            "failed",
            RagFailedPayload.model_validate(
                {
                    "requestId": body.request_id,
                    "errorCode": error_code,
                    "executionUsage": list(pipeline.usage),
                }
            ),
        )

    finally:
        # 清理 pending_item，避免遗留未观察异常的后台任务
        if pending_item is not None:
            if not pending_item.done():
                pending_item.cancel()
            await asyncio.gather(pending_item, return_exceptions=True)

        # 关闭流迭代器
        try:
            await stream.aclose()
        except Exception:
            LOGGER.exception("RAG 流关闭失败：request_id=%s", body.request_id)
        finally:
            # 关闭 Provider 连接，归还到连接池
            try:
                await pipeline.provider.close()
            except Exception:
                LOGGER.exception("RAG Provider 关闭失败：request_id=%s", body.request_id)


# ============================================================================
# 错误映射与响应
# ============================================================================


def _error_code(error: Exception) -> ErrorCode:
    """将权限、检索、模型与引用异常映射为稳定机器错误码。

    映射按异常来源分为三组：
      - Java 服务异常 — KnowledgeScopeClientError，透传其错误码。
      - 模型 Provider 异常 — 配置/认证/限流/超时/参数错误，各自映射。
      - RAG 流水线异常 — 检索、重排、上下文、引用校验的领域错误。
    未匹配异常统一返回 "INTERNAL"。
    """
    # --- Java 服务异常 ---
    if isinstance(error, KnowledgeScopeClientError):
        return cast(ErrorCode, error.error_code)

    if isinstance(error, McpCredentialMissing):
        return "TOOL_CREDENTIAL_MISSING"
    if isinstance(error, McpToolTimeout):
        return "TOOL_TIMEOUT"
    if isinstance(error, McpToolRejected):
        return "TOOL_REJECTED"

    # --- 模型 Provider 异常 ---
    if isinstance(error, ModelConfigurationError):
        return "MODEL_UNAVAILABLE"
    if isinstance(error, ModelProviderAuthenticationError):
        return "PROVIDER_AUTHORIZATION"
    if isinstance(error, ModelProviderRateLimitError):
        return "RATE_LIMITED"
    if isinstance(error, ModelProviderTimeoutError):
        return "TIMEOUT"
    if isinstance(error, ModelProviderBudgetError):
        return "BUDGET_EXCEEDED"
    if isinstance(error, ModelProviderRequestError):
        return "VALIDATION"
    if isinstance(error, ModelProviderError):
        return "INTERNAL"

    # --- RAG 流水线异常 ---
    if isinstance(
        error,
        (
            AuthorizedRetrievalChainError,
            AuthorizedRetrievalError,
            ContextSelectionError,
            GroupedRerankError,
            RagPipelineError,
        ),
    ):
        code = str(error)
        if code in {
            "VALIDATION",
            "AUTHORIZATION",
            "SCOPE_EXPIRED",
            "INDEX_MISMATCH",
            "VECTOR_UNAVAILABLE",
            "CITATION_INVALID",
            "MODEL_UNAVAILABLE",
        }:
            return cast(ErrorCode, code)

    return "INTERNAL"


def _error_response(
    request_id: str,
    error_code: ErrorCode,
    retryable: bool,
    status: int,
) -> JSONResponse:
    """构造安全的错误响应，不暴露内部解析细节和上游正文。

    仅用于请求体校验失败（VALIDATION / 422），
    正常的流内异常由 RagFailedPayload 通过 SSE 事件返回。
    """
    payload = InternalError.model_validate(
        {
            "requestId": request_id,
            "errorCode": error_code,
            "retryable": retryable,
        }
    )
    return JSONResponse(
        status_code=status,
        content=payload.model_dump(by_alias=True, mode="json"),
    )


# ============================================================================
# SSE 编码
# ============================================================================


def _encode_event(event_name: str, payload: BaseModel) -> str:
    """编码 Java SSE 客户端已支持的单行 JSON 事件。

    格式为 event: {name}\ndata: {json}\n\n，使用 camelCase 别名序列化。
    与 chat.py 的 _encode_event 类似，但接受更宽泛的 BaseModel 类型。
    """
    return f"event: {event_name}\ndata: {payload.model_dump_json(by_alias=True)}\n\n"
