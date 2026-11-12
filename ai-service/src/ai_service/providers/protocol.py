"""隔离具体模型 SDK 的流式 Provider 协议。

职责：
  - ChatStreamProvider 协议 — 执行单次已授权模型流的通用边界。
  - ProviderStreamChunk — 归一化文本片段和用量的数据类。
  - 所有具体适配器通过此协议注入，隔离第三方 SDK 变更风险。
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Protocol

from ai_service.contracts.chat import InternalChatStreamRequest, Usage

# ============================================================================
# 流式 Provider 协议
# ============================================================================


class ProviderStreamChunk:
    """聊天用例消费的归一化供应商流片段；tool_succeeded 表示授权工具已成功返回。"""

    def __init__(
        self, text: str = "", usage: Usage | None = None, tool_succeeded: bool = False
    ) -> None:
        self.text = text
        self.usage = usage
        self.tool_succeeded = tool_succeeded


class ChatStreamProvider(Protocol):
    """执行单次已授权模型流的 Provider 边界。"""

    def stream(self, request: InternalChatStreamRequest) -> AsyncIterator[ProviderStreamChunk]:
        """执行一次模型调用并产出归一化文本和用量片段。"""

    async def close(self) -> None:
        """在调用完成或取消后释放 Provider 的请求级资源。"""
