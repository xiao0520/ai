"""普通聊天的 LangChain MCP 工具执行分支。"""

from __future__ import annotations

from collections.abc import AsyncIterator
from typing import cast

import httpx2
from langchain.agents import create_agent
from langchain.agents.middleware.types import InputAgentState
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, ToolMessage
from langchain_core.runnables import RunnableConfig
from openai import APIStatusError, APITimeoutError

from ai_service.contracts.chat import InternalChatStreamRequest, Usage
from ai_service.infra.settings import Settings
from ai_service.mcp.authorized_tools import McpToolRejected, authorized_mcp_tools
from ai_service.providers.errors import (
    ModelConfigurationError,
    ModelProviderBudgetError,
    ModelProviderError,
    ModelProviderTimeoutError,
    translate_api_status_error,
)
from ai_service.providers.langchain_openai import LangChainOpenAiChatProvider
from ai_service.providers.protocol import ProviderStreamChunk


class McpChatAgent:
    """以 Java 本轮下发的授权工具和预算运行受限聊天 Agent。"""

    def __init__(self, settings: Settings, http_async_client: httpx2.AsyncClient) -> None:
        self._settings = settings
        self._model_provider = LangChainOpenAiChatProvider(settings, http_async_client)

    def create_model(self, request: InternalChatStreamRequest) -> BaseChatModel:
        """向厂商适配器取得本次 Java 授权模型，不在 Agent 内解析凭证或 API 模式。"""
        return self._model_provider.create_tool_model(request)

    async def stream(
        self, request: InternalChatStreamRequest
    ) -> AsyncIterator[ProviderStreamChunk]:
        """逐轮累计模型用量，并且只向会话输出工具执行后的最终回答。"""
        if not request.tools:
            raise ModelConfigurationError("The tool grant list is empty")
        if request.execution_policy.max_total_tokens is None:
            raise ModelConfigurationError("The tool budget is missing")
        model = self.create_model(request)
        input_total = 0
        output_total = 0
        final_text: str | None = None
        tool_succeeded = False
        try:
            async with authorized_mcp_tools(request.tools, self._settings) as tools:
                agent = create_agent(model, tools)
                messages = LangChainOpenAiChatProvider._to_langchain_messages(
                    request.input.messages
                )
                # LangGraph 的输入类型是 TypedDict；消息已在边界转换为 LangChain 对象。
                state = cast(InputAgentState, {"messages": messages})
                config = RunnableConfig(
                    recursion_limit=10,
                    run_name="internal-chat-mcp",
                    metadata={"request_id": request.request_id},
                )
                async for update in agent.astream(
                    state, config=config, stream_mode="updates", version="v1"
                ):
                    # 仅成功返回的工具结果可证明本轮实际使用了 MCP。
                    tool_update = update.get("tools")
                    if isinstance(tool_update, dict):
                        tool_messages = tool_update.get("messages")
                        if isinstance(tool_messages, list):
                            tool_succeeded |= any(
                                isinstance(message, ToolMessage) and message.status == "success"
                                for message in tool_messages
                            )
                        continue
                    # 工具节点更新不含模型用量；只处理模型轮次并在工具执行前结算已发生用量。
                    model_update = update.get("model")
                    if not isinstance(model_update, dict):
                        continue
                    step_messages = model_update.get("messages")
                    if not isinstance(step_messages, list) or len(step_messages) != 1:
                        raise ModelProviderError("Invalid model step")
                    answer = step_messages[0]
                    if not isinstance(answer, AIMessage) or answer.usage_metadata is None:
                        raise ModelProviderError("Model usage is unavailable")
                    usage = LangChainOpenAiChatProvider._usage_from_metadata(answer.usage_metadata)
                    if usage is None or usage.input_tokens is None or usage.output_tokens is None:
                        raise ModelProviderError("Model usage is unavailable")
                    input_total += usage.input_tokens
                    output_total += usage.output_tokens
                    if input_total + output_total > request.execution_policy.max_total_tokens:
                        # 超额轮次已经由供应商执行，先交回真实累计用量供 Java 失败结算。
                        yield ProviderStreamChunk(
                            usage=Usage(
                                input_tokens=input_total,
                                output_tokens=output_total,
                                total_tokens=input_total + output_total,
                            )
                        )
                        raise ModelProviderBudgetError("The tool budget is exhausted")
                    if answer.invalid_tool_calls:
                        raise McpToolRejected
                    if answer.tool_calls:
                        if len(answer.tool_calls) > 3:
                            raise McpToolRejected
                        # 工具执行前先报告本轮累计用量，后续失败也可计入 RAG 用量审计。
                        yield ProviderStreamChunk(
                            usage=Usage(
                                input_tokens=input_total,
                                output_tokens=output_total,
                                total_tokens=input_total + output_total,
                            )
                        )
                        continue
                    final_text = answer.text
                if final_text is None:
                    raise ModelProviderError("The model returned no final answer")
                # Agent 更新仅在模型轮次完成时确认没有后续工具调用；此时再输出正文，
                # 防止工具调用前的推理文本或未完成回答进入用户会话。
                for offset in range(0, len(final_text), 256):
                    yield ProviderStreamChunk(text=final_text[offset : offset + 256])
                yield ProviderStreamChunk(
                    usage=Usage(
                        input_tokens=input_total,
                        output_tokens=output_total,
                        total_tokens=input_total + output_total,
                    ),
                    tool_succeeded=tool_succeeded,
                )
        except McpToolRejected, ModelConfigurationError, ModelProviderError:
            raise
        except APIStatusError as error:
            raise translate_api_status_error(error) from error
        except (APITimeoutError, TimeoutError) as error:
            raise ModelProviderTimeoutError("The model provider timed out") from error
        except Exception as error:
            raise ModelProviderError("The tool-assisted model request failed") from error

    async def close(self) -> None:
        """共享 HTTP 客户端由应用生命周期释放。"""
