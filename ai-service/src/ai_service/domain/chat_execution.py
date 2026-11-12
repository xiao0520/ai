"""不持有业务状态的单次聊天执行用例。

职责：
  - 接收 Java 已授权的 InternalChatStreamRequest 快照。
  - 通过 Provider 协议执行流式模型调用。
  - 归一化顶部 SSE 事件（delta / usage / completed）。
  - 提供 provider 属性供 HTTP 层在取消或终态后释放请求级资源。

关键边界：
  - 不读取业务数据库，不使用本地模型路由。
  - 一请求一实例，内部收束不跨请求泄露。
  - 成功流结束后由执行层唯一产生 completed，避免 Java 收到多个终态。
"""

from __future__ import annotations

from collections.abc import AsyncGenerator

from ai_service.contracts.chat import (
    CompletedPayload,
    DeltaPayload,
    InternalChatStreamRequest,
    Usage,
)
from ai_service.providers.protocol import ChatStreamProvider

# ============================================================================
# 聊天执行用例
# ============================================================================


class ChatExecutionUseCase:
    """编排单次已授权模型调用，不写入会话、权限或额度业务状态。"""

    def __init__(
        self, provider: ChatStreamProvider, tool_provider: ChatStreamProvider | None = None
    ) -> None:
        self._provider = provider
        self._tool_provider = tool_provider

    @property
    def provider(self) -> ChatStreamProvider:
        """暴露本次调用的 Provider，供入口执行请求级资源收束。"""
        return self._provider

    async def stream(
        self, request: InternalChatStreamRequest
    ) -> AsyncGenerator[tuple[str, DeltaPayload | Usage | CompletedPayload]]:
        """将 Provider 片段转换为标准 SSE 事件，并只生成一个成功终态。

        处理流程：
          1. 遍历 Provider 流，累积文本片段和最新用量。
          2. 按标准 SSE 契约产出 delta / usage。
          3. 正常结束时产出 completed（含完整内容和汇总用量）。

        注：异常不在此层捕获；上游普通聊天 SSE 生成器统一映射为 FailedPayload。
        """
        content_parts: list[str] = []
        latest_usage: Usage | None = None
        source = self._tool_provider if request.tools else self._provider
        if source is None:
            raise ValueError("The tool provider is not configured")
        async for chunk in source.stream(request):
            if chunk.text:
                content_parts.append(chunk.text)
                yield "delta", DeltaPayload(text=chunk.text)
            if chunk.usage is not None:
                latest_usage = chunk.usage
                yield "usage", chunk.usage
        # 成功流结束后由执行层唯一产生 completed，避免 Java 收到多个终态。
        yield (
            "completed",
            CompletedPayload(
                request_id=request.request_id,
                content="".join(content_parts),
                usage=latest_usage,
            ),
        )
