"""仅供 Java 控制面调用的内部流式聊天路由。

本模块是 Python 执行面的 HTTP 入口，处理 Java 转发的基础聊天请求：
  - 认证 — InternalRequestAuthenticator 校验 Java 签发的 HMAC 签名。
  - 契约 — InternalChatStreamRequest 为 Java 已鉴权的完整请求体。
  - 流式 — 返回标准 SSE（Server-Sent Events）事件流。
  - 容错 — 异常映射为稳定错误码，Keepalive 防止检索和调用期间断连。
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncGenerator
from typing import Annotated, cast

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse

from ai_service.contracts.chat import (
    CompletedPayload,
    DeltaPayload,
    FailedPayload,
    InternalChatStreamRequest,
    KeepalivePayload,
    SseErrorCode,
    Usage,
)
from ai_service.contracts.chat_routing import (
    InternalChatRouteRequest,
    InternalChatRouteResponse,
)
from ai_service.domain.chat_execution import ChatExecutionUseCase
from ai_service.domain.chat_routing import AuthorizedChatModelRouter
from ai_service.infra.internal_auth import InternalRequestAuthenticator
from ai_service.infra.settings import Settings, get_settings
from ai_service.mcp.authorized_tools import (
    McpCredentialMissing,
    McpToolRejected,
    McpToolTimeout,
)
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

# ============================================================================
# 路由与常量定义
# ============================================================================

router = APIRouter(prefix="/internal/ai/v1", tags=["internal-ai"])
LOGGER = logging.getLogger(__name__)

# SSE 事件流中可能出现的所有负载类型联合，用于 _encode_event 的类型限定
SsePayload = DeltaPayload | Usage | CompletedPayload | FailedPayload | KeepalivePayload

# 异常类型到稳定错误码的映射表；按优先级从具体到通用排列
_STREAM_ERROR_CODES: tuple[tuple[type[Exception], SseErrorCode], ...] = (
    (McpCredentialMissing, "TOOL_CREDENTIAL_MISSING"),
    (McpToolTimeout, "TOOL_TIMEOUT"),
    (McpToolRejected, "TOOL_REJECTED"),
    (ModelConfigurationError, "MODEL_UNAVAILABLE"),
    (ModelProviderBudgetError, "BUDGET_EXCEEDED"),
    (ModelProviderTimeoutError, "TIMEOUT"),
    (ModelProviderAuthenticationError, "PROVIDER_AUTHORIZATION"),
    (ModelProviderRateLimitError, "RATE_LIMITED"),
    (ModelProviderRequestError, "VALIDATION"),
    (ModelProviderError, "INTERNAL"),
)


# ============================================================================
# FastAPI 依赖注入
# ============================================================================


async def require_internal_request(request: Request) -> None:
    """仅允许通过 Java 内部签名认证的请求进入聊天执行层。

    认证器从 app.state 获取（启动时注入），验证通过后放行，
    失败时 FastAPI 的依赖注入机制会返回 401。
    """
    authenticator: InternalRequestAuthenticator = request.app.state.internal_request_authenticator
    await authenticator.verify(request)


def get_chat_use_case(
    request: Request,
) -> ChatExecutionUseCase:
    """组装本次调用的用例，并注入应用生命周期持有的厂商路由策略。

    从 app.state 中获取共享的 ChatStreamProvider（启动时初始化），
    创建无状态的 ChatExecutionUseCase 实例，每次请求独立创建。
    """
    provider = cast(ChatStreamProvider, request.app.state.chat_provider)
    tool_provider = cast(ChatStreamProvider, request.app.state.mcp_chat_agent)
    return ChatExecutionUseCase(provider, tool_provider)


def get_chat_model_router() -> AuthorizedChatModelRouter:
    """创建无外部状态的确定性 LangChain 模型路由器。"""
    return AuthorizedChatModelRouter()


# ============================================================================
# HTTP 端点
# ============================================================================


@router.post(
    "/chat/route", response_model=InternalChatRouteResponse, summary="Route within Java candidates"
)
async def route_chat_model(
    body: InternalChatRouteRequest,
    _: Annotated[None, Depends(require_internal_request)],
    model_router: Annotated[AuthorizedChatModelRouter, Depends(get_chat_model_router)],
) -> InternalChatRouteResponse:
    """只在 Java 已授权的候选快照中选择模型，不访问供应商或扩展权限。"""
    return await model_router.route(body)


@router.post("/chat/stream", summary="Run one Java-authorized chat request as SSE")
async def stream_chat(
    body: InternalChatStreamRequest,
    request: Request,
    _: Annotated[None, Depends(require_internal_request)],
    use_case: Annotated[ChatExecutionUseCase, Depends(get_chat_use_case)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> StreamingResponse:
    """执行 Java 已鉴权的聊天请求并返回标准 SSE 事件流。

    Java 负责会话、模型权限与额度；本路由只负责认证、契约映射和取消收束。
    返回 StreamingResponse，流式输出 event: delta/completed/failed/keepalive。
    """
    return StreamingResponse(
        _event_stream(body, request, use_case, settings),
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
    body: InternalChatStreamRequest,
    request: Request,
    use_case: ChatExecutionUseCase,
    settings: Settings,
) -> AsyncGenerator[str]:
    """在 Provider 等待期间发送 keepalive，并保证流最终只有一个终态事件。

    流处理流程：
      1. 创建异步任务拉取下一个事件（pending_item）。
      2. 如果客户端断连（request.is_disconnected()），立即退出。
      3. 带超时等待事件（provider_keepalive_seconds），超时发送 keepalive。
      4. 收到 completed 事件后正常结束。
      5. 任何异常映射为 failed 事件并返回稳定错误码。

    关键设计：
      - asyncio.shield 保护 pending_item 不被 keepalive 超时取消。
      - finally 块确保清理 pending_item、关闭流和 Provider 连接。
    """
    stream = use_case.stream(body)
    pending_item: asyncio.Task[tuple[str, SsePayload]] | None = None
    try:
        while True:
            # 客户端断连 → 立即停止，不再生成事件
            if await request.is_disconnected():
                return

            # 惰性创建取流任务；keepalive 超时不能触发重复的上游读取。
            if pending_item is None:
                pending_item = asyncio.create_task(anext(stream))

            try:
                # shield 防止 keepalive 超时取消仍在进行的上游取流任务。
                event_name, payload = await asyncio.wait_for(
                    asyncio.shield(pending_item), timeout=settings.provider_keepalive_seconds
                )
                pending_item = None
                yield _encode_event(event_name, payload)
                if event_name == "completed":
                    return
            except TimeoutError:
                # 上游仍未返回 → 发送 keepalive 防止网关/代理断连
                yield _encode_event("keepalive", KeepalivePayload(request_id=body.request_id))

    except asyncio.CancelledError:
        # FastAPI 取消 → 向上传播，不发送 failed 事件
        raise

    except (McpToolRejected, RuntimeError, TypeError, ValueError) as error:
        # 可预期的业务异常 → 映射为稳定错误码
        error_code = _stream_error_code(error)
        _log_stream_failure(body.request_id, error_code, error)
        yield _encode_event("failed", FailedPayload(error_code=error_code))

    finally:
        # 浏览器或 Java 断连时必须停止等待本次模型流；共享连接池由应用停机时统一关闭。
        if pending_item is not None:
            if not pending_item.done():
                pending_item.cancel()
            # gather 会取走取消或失败结果，避免遗留未观察异常的后台任务。
            await asyncio.gather(pending_item, return_exceptions=True)

        # 关闭流迭代器，释放底层资源
        try:
            await stream.aclose()
        except Exception:
            LOGGER.exception("模型流关闭失败：request_id=%s", body.request_id)
        finally:
            # 关闭 Provider 连接，归还到连接池
            try:
                await use_case.provider.close()
            except Exception:
                LOGGER.exception("模型 Provider 关闭失败：request_id=%s", body.request_id)


# ============================================================================
# 错误映射与日志
# ============================================================================


def _stream_error_code(error: Exception) -> SseErrorCode:
    """将模型执行异常转换为 Java 消费方约定的稳定错误码。

    遍历 _STREAM_ERROR_CODES 映射表，按优先级从具体到通用匹配，
    未匹配到的异常统一返回 "INTERNAL"。
    """
    for exception_type, error_code in _STREAM_ERROR_CODES:
        if isinstance(error, exception_type):
            return error_code
    return "INTERNAL"


def _log_stream_failure(request_id: str, error_code: SseErrorCode, error: Exception) -> None:
    """记录脱敏后的流失败上下文，并为未知异常保留堆栈。

    已知异常类型（ModelConfigurationError / ModelProviderError）仅记录 warning
    和异常类型名，不打印堆栈以降低日志量；未分类异常记录完整堆栈供排查。
    """
    if isinstance(error, (McpToolRejected, ModelConfigurationError, ModelProviderError)):
        LOGGER.warning(
            "模型流执行失败：request_id=%s, error_code=%s, error_type=%s",
            request_id,
            error_code,
            type(error).__name__,
        )
        return
    LOGGER.exception(
        "模型流发生未分类异常：request_id=%s, error_code=%s, error_type=%s",
        request_id,
        error_code,
        type(error).__name__,
    )


# ============================================================================
# SSE 编码
# ============================================================================


def _encode_event(event_name: str, payload: SsePayload) -> str:
    """编码符合 Java 消费方约定的单行 JSON SSE 事件。

    格式为 event: {name}\ndata: {json}\n\n，其中 json 使用 camelCase 别名序列化。
    """
    return f"event: {event_name}\ndata: {payload.model_dump_json(by_alias=True)}\n\n"
