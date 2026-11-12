"""基于 LangChain OpenAI 兼容接口的模型 Provider 适配器。

职责：
  - 将 Java 已授权模型快照映射为 OpenAI 兼容的流式调用。
  - 通过 ChatOpenAI.astream 产出的 AIMessageChunk 提取文本和用量。
  - 按异常类型脱敏：APIStatusError 翻译为稳定 errorCode，其他异常统一为 ModelProviderError。

关键约束：
  - 模型、端点和凭证均来自 Java 下发的受控快照，不允许本地默认值。
  - max_retries=0，因为 Java 负责一次调用的预算与审计。
  - 共享 HTTP 客户端由 FastAPI lifespan 管理，单次请求不能关闭。
"""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import AsyncIterator, Mapping, Sequence
from typing import TypeVar

import httpx2
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI
from openai import APIStatusError, APITimeoutError
from pydantic import BaseModel

from ai_service.contracts.chat import (
    ChatMessage,
    ExecutionPolicy,
    InternalChatStreamRequest,
    ModelProfile,
    Usage,
)
from ai_service.infra.settings import Settings
from ai_service.providers.errors import (
    ModelConfigurationError,
    ModelProviderAuthenticationError,
    ModelProviderError,
    ModelProviderRateLimitError,
    ModelProviderRequestError,
    ModelProviderTimeoutError,
    translate_api_status_error,
)
from ai_service.providers.factory import (
    API_MODE_CHAT_COMPLETIONS,
    API_MODE_RESPONSES,
    OPENAI_COMPATIBLE_PROVIDER_CODES,
)
from ai_service.providers.metering import UsageReporter, estimate_tokens
from ai_service.providers.protocol import ProviderStreamChunk

LOGGER = logging.getLogger(__name__)
StructuredOutput = TypeVar("StructuredOutput", bound=BaseModel)


# ============================================================================
# LangChain OpenAI 聊天 Provider
# ============================================================================


class LangChainOpenAiChatProvider:
    """将 Java 已授权模型快照映射为 OpenAI 兼容的流式调用。"""

    def __init__(self, settings: Settings, http_async_client: httpx2.AsyncClient) -> None:
        self._settings = settings
        # 客户端由 FastAPI lifespan 所有，单次请求不能关闭它，否则会影响并发或后续对话。
        self._http_async_client = http_async_client

    def create_tool_model(self, request: InternalChatStreamRequest) -> ChatOpenAI:
        """为授权工具聊天构造模型，沿用 Java 快照、凭证别名和共享连接池。"""
        return self._create_model(request.model_profile, request.execution_policy)

    async def invoke_structured(
        self,
        messages: Sequence[BaseMessage],
        response_model: type[StructuredOutput],
        model_profile: ModelProfile,
        execution_policy: ExecutionPolicy,
        *,
        run_name: str,
        request_id: str,
        report_usage: UsageReporter,
        estimated_input_tokens: int,
    ) -> StructuredOutput:
        """使用工具调用模式返回经 Pydantic 校验的结构化结果。"""
        chat_model = self._create_model(model_profile, execution_policy)
        structured_model = chat_model.with_structured_output(
            response_model,
            method="function_calling",
            include_raw=True,
        )
        try:
            result = await structured_model.ainvoke(
                list(messages),
                config={"run_name": run_name, "metadata": {"request_id": request_id}},
            )
            if not isinstance(result, dict) or not isinstance(result.get("raw"), AIMessage):
                raise ModelProviderError("The model returned an invalid structured response")
            raw = result["raw"]
            usage = self._usage_from_metadata(raw.usage_metadata)
            if (
                usage is not None
                and usage.input_tokens is not None
                and usage.output_tokens is not None
                and usage.input_tokens + usage.output_tokens > 0
            ):
                report_usage(usage, "ACTUAL")
            else:
                output_text = json.dumps(
                    {
                        "content": raw.content,
                        "toolCalls": raw.tool_calls,
                        "invalidToolCalls": raw.invalid_tool_calls,
                    },
                    ensure_ascii=False,
                )
                output_tokens = await asyncio.to_thread(estimate_tokens, output_text)
                report_usage(
                    Usage(input_tokens=estimated_input_tokens, output_tokens=output_tokens),
                    "ESTIMATED",
                )
            parsed = result.get("parsed")
            if not isinstance(parsed, response_model):
                raise ModelProviderError("The model returned an invalid structured response")
            return parsed
        except APIStatusError as error:
            raise translate_api_status_error(error) from error
        except (APITimeoutError, TimeoutError) as error:
            raise ModelProviderTimeoutError("The model provider timed out") from error
        except (
            ModelProviderAuthenticationError,
            ModelProviderRateLimitError,
            ModelProviderRequestError,
            ModelProviderError,
        ):
            raise
        except Exception as error:
            raise ModelProviderError("The model provider request failed") from error

    def _create_model(
        self,
        profile: ModelProfile,
        execution_policy: ExecutionPolicy,
    ) -> ChatOpenAI:
        """根据 Java 模型快照创建请求级模型，不引入本地默认配置。"""
        code = profile.provider_code.strip().lower()
        if (
            code not in OPENAI_COMPATIBLE_PROVIDER_CODES
            or (profile.api_mode == API_MODE_RESPONSES and code != "doubao")
            or profile.api_mode not in (API_MODE_CHAT_COMPLETIONS, API_MODE_RESPONSES)
        ):
            raise ModelConfigurationError("The model provider is not supported")
        try:
            credential = self._settings.credential_for(profile.credential_key)
        except ValueError as error:
            raise ModelConfigurationError("The model credential is not configured") from error
        return ChatOpenAI(
            model=profile.model_name,
            api_key=credential,
            base_url=str(profile.api_host),
            timeout=min(self._settings.provider_timeout_seconds, execution_policy.timeout_seconds),
            http_async_client=self._http_async_client,
            http_socket_options=(),
            stream_usage=True,
            max_retries=0,
            use_responses_api=profile.api_mode == API_MODE_RESPONSES,
            max_completion_tokens=min(
                profile.max_output_tokens or execution_policy.max_output_tokens,
                execution_policy.max_output_tokens,
            ),
        )

    async def stream(
        self, request: InternalChatStreamRequest
    ) -> AsyncIterator[ProviderStreamChunk]:
        """执行单次 Provider 流并归一化文本、用量和脱敏错误。"""
        profile = request.model_profile
        try:
            credential = self._settings.credential_for(profile.credential_key)
        except ValueError as error:
            raise ModelConfigurationError("The model credential is not configured") from error

        # 模型、端点和凭证别名均来自受控快照；禁止以本地默认模型绕过 Java 发布流程。
        chat_model = ChatOpenAI(
            model=profile.model_name,
            api_key=credential,
            base_url=str(profile.api_host),
            timeout=min(
                self._settings.provider_timeout_seconds, request.execution_policy.timeout_seconds
            ),
            http_async_client=self._http_async_client,
            # 共享客户端已管理连接策略，禁止 LangChain 再注入 transport 并屏蔽系统代理。
            http_socket_options=(),
            # 自定义 base_url 或 HTTP 客户端时 LangChain 默认不请求流式 usage，必须显式开启。
            stream_usage=True,
            # Java 负责一次调用的预算与审计，Provider 不自动重试以免重复计费或重复副作用。
            max_retries=0,
        )
        try:
            async for chunk in chat_model.astream(
                self._to_langchain_messages(request.input.messages),
                max_tokens=profile.max_output_tokens or request.execution_policy.max_output_tokens,
                config={
                    "run_name": "internal-chat",
                    "metadata": {"request_id": request.request_id},
                },
            ):
                text = chunk.text
                usage = self._usage_from_metadata(chunk.usage_metadata)
                if text or usage is not None:
                    yield ProviderStreamChunk(text=text, usage=usage)
        except APIStatusError as error:
            self._log_provider_failure(request, error)
            raise translate_api_status_error(error) from error
        except (APITimeoutError, TimeoutError) as error:
            raise ModelProviderTimeoutError("The model provider timed out") from error
        except (
            ModelProviderAuthenticationError,
            ModelProviderRateLimitError,
            ModelProviderRequestError,
            ModelProviderError,
        ):
            raise
        except Exception as error:
            self._log_provider_failure(request, error)
            raise ModelProviderError("The model provider request failed") from error

    async def close(self) -> None:
        """保留 Provider 协议的释放入口；共享客户端仅能由应用生命周期关闭。"""

    @staticmethod
    def _to_langchain_messages(messages: list[ChatMessage]) -> list[BaseMessage]:
        """将内部角色消息映射为 LangChain 消息对象。"""
        result: list[BaseMessage] = []
        for message in messages:
            if message.role == "system":
                result.append(SystemMessage(content=message.content))
            elif message.role == "user":
                result.append(HumanMessage(content=message.content))
            else:
                result.append(AIMessage(content=message.content))
        return result

    @staticmethod
    def _usage_from_metadata(metadata: Mapping[str, object] | None) -> Usage | None:
        """从兼容接口的可选元数据提取可计费的归一化用量。"""
        if metadata is None:
            return None
        input_tokens = LangChainOpenAiChatProvider._token_value(metadata, "input_tokens")
        output_tokens = LangChainOpenAiChatProvider._token_value(metadata, "output_tokens")
        total_tokens = LangChainOpenAiChatProvider._token_value(metadata, "total_tokens")
        if input_tokens is None and output_tokens is None and total_tokens is None:
            return None
        return Usage(
            input_tokens=input_tokens, output_tokens=output_tokens, total_tokens=total_tokens
        )

    @staticmethod
    def _token_value(metadata: Mapping[str, object], key: str) -> int | None:
        """仅接受非负整数 Token 值，忽略供应商的非标准字段。

        部分 OpenAI 兼容厂商会在 usage_metadata 中注入额外字段（如 cache_hit_tokens），
        这些字段不是标准 int 类型，会被静默跳过而非抛异常中断流。"""
        value = metadata.get(key)
        return value if isinstance(value, int) and value >= 0 else None

    @staticmethod
    def _log_provider_failure(request: InternalChatStreamRequest, error: Exception) -> None:
        """记录定位连接问题所需的脱敏异常元数据，不写入提示词、密钥或上游正文。"""
        cause = error.__cause__ or error.__context__
        root_cause = cause.__cause__ or cause.__context__ if cause is not None else None
        status_code = error.status_code if isinstance(error, APIStatusError) else None
        provider_error_code = error.code if isinstance(error, APIStatusError) else None
        LOGGER.warning(
            "模型调用失败：request_id=%s, model_id=%s, revision=%s, error_type=%s, "
            "status_code=%s, provider_error_code=%s, cause_type=%s, root_cause_type=%s",
            request.request_id,
            request.model_profile.id,
            request.model_profile.revision,
            type(error).__name__,
            status_code,
            provider_error_code,
            type(cause).__name__ if cause is not None else None,
            type(root_cause).__name__ if root_cause is not None else None,
        )
