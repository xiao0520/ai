"""基于 OpenAI 兼容 Responses API 的模型 Provider 适配器。

职责：
  - 通过 AsyncOpenAI.responses.create 执行流式调用。
  - 归一化 ResponseTextDeltaEvent（增量文本）和 Usage（完成/不完整事件中的用量）。
  - 按异常类型脱敏，日志只写脱敏元数据（request_id, model_id, status_code）。

关键约束：
  - 仅已验证的厂商（当前仅 doubao）注册此适配器。
  - max_retries=0，复用 FastAPI lifespan 管理的共享 HTTP 客户端。
  - ResponseIncompleteEvent 视为正常结束（已达 max_output_tokens 上限）。
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator

import httpx2
from openai import APIStatusError, APITimeoutError, AsyncOpenAI
from openai.types.responses import (
    EasyInputMessageParam,
    ResponseCompletedEvent,
    ResponseErrorEvent,
    ResponseFailedEvent,
    ResponseIncompleteEvent,
    ResponseInputParam,
    ResponseTextDeltaEvent,
)

from ai_service.contracts.chat import InternalChatStreamRequest, Usage
from ai_service.infra.settings import Settings
from ai_service.providers.errors import (
    ModelConfigurationError,
    ModelProviderError,
    ModelProviderTimeoutError,
    translate_api_status_error,
)
from ai_service.providers.protocol import ProviderStreamChunk

LOGGER = logging.getLogger(__name__)


# ============================================================================
# OpenAI Responses 聊天 Provider
# ============================================================================


class OpenAiResponsesChatProvider:
    """调用方舟等已验证的 Responses API，并归一化文本与用量事件。"""

    def __init__(self, settings: Settings, http_async_client: httpx2.AsyncClient) -> None:
        self._settings = settings
        self._http_async_client = http_async_client

    async def stream(
        self, request: InternalChatStreamRequest
    ) -> AsyncIterator[ProviderStreamChunk]:
        """执行单次 Responses 流，异常只保留稳定分类和脱敏元数据。"""
        profile = request.model_profile
        try:
            credential = self._settings.credential_for(profile.credential_key)
        except ValueError as error:
            raise ModelConfigurationError("The model credential is not configured") from error

        client = AsyncOpenAI(
            api_key=credential.get_secret_value(),
            base_url=str(profile.api_host),
            timeout=min(
                self._settings.provider_timeout_seconds,
                request.execution_policy.timeout_seconds,
            ),
            max_retries=0,
            http_client=self._http_async_client,
        )
        try:
            response_stream = await client.responses.create(
                model=profile.model_name,
                input=self._to_responses_input(request),
                max_output_tokens=(
                    profile.max_output_tokens or request.execution_policy.max_output_tokens
                ),
                stream=True,
            )
            # 文本增量与最终用量分开上报，由聊天执行层统一汇总。
            async for event in response_stream:
                if isinstance(event, ResponseTextDeltaEvent) and event.delta:
                    yield ProviderStreamChunk(text=event.delta)
                elif isinstance(event, (ResponseCompletedEvent, ResponseIncompleteEvent)):
                    usage = event.response.usage
                    if usage is not None:
                        yield ProviderStreamChunk(
                            usage=Usage(
                                input_tokens=usage.input_tokens,
                                output_tokens=usage.output_tokens,
                                total_tokens=usage.total_tokens,
                            )
                        )
                    # 达到最大输出长度会产生 incomplete，但已生成文本与用量仍是有效结果。
                    if isinstance(event, ResponseIncompleteEvent):
                        return
                elif isinstance(event, (ResponseErrorEvent, ResponseFailedEvent)):
                    raise ModelProviderError("The model provider response did not complete")
        except APIStatusError as error:
            self._log_provider_failure(request, error)
            raise translate_api_status_error(error) from error
        except APITimeoutError as error:
            raise ModelProviderTimeoutError("The model provider timed out") from error
        except ModelProviderError:
            raise
        except Exception as error:
            self._log_provider_failure(request, error)
            raise ModelProviderError("The model provider request failed") from error

    async def close(self) -> None:
        """共享 HTTP 客户端由 FastAPI lifespan 关闭，请求结束不释放连接池。

        ChatStreamProvider 统一要求 close() 方法，但 SharedHttpClient 由应用
        生命周期管理，单次请求关闭会断开并发或后续对话的复用连接。"""

    @staticmethod
    def _to_responses_input(request: InternalChatStreamRequest) -> ResponseInputParam:
        """将 Java 已裁剪的会话历史映射为 Responses API 的 EasyInputMessageParam。

        Java 端已完成权限过滤和 Token 裁剪，Python 侧不再二次修改消息内容，
        仅做角色到 EasyInputMessageParam.role 的一对一映射。"""
        return [
            EasyInputMessageParam(role=message.role, content=message.content)
            for message in request.input.messages
        ]

    @staticmethod
    def _log_provider_failure(request: InternalChatStreamRequest, error: Exception) -> None:
        """记录方舟请求定位所需的状态码和模型标识，不写入响应正文或凭证。

        与 LangChain OpenAI 适配器中的同名方法输出格式一致，便于统一日志检索。"""
        status_code = error.status_code if isinstance(error, APIStatusError) else None
        provider_error_code = error.code if isinstance(error, APIStatusError) else None
        LOGGER.warning(
            "Responses 模型调用失败：request_id=%s, model_id=%s, revision=%s, "
            "error_type=%s, status_code=%s, provider_error_code=%s",
            request.request_id,
            request.model_profile.id,
            request.model_profile.revision,
            type(error).__name__,
            status_code,
            provider_error_code,
        )