"""把项目 Provider 适配到 LangChain 标准接口和 Runnable。

职责：
  - ProfileBoundEmbeddings — 将 EmbeddingProvider 包装为 LangChain Embeddings 接口。
  - 提供 RunnableLambda 包装器供检索/索引链使用。
  - 按输入位置生成稳定标识，保存供应商用量供业务预算结算。

关键设计：
  - 同步方法（embed_documents/embed_query）仅在线程外调用时通过 asyncio.run 启动。
  - 服务主链必须使用 aembed_documents/aembed_query 异步方法。
  - take_batches() 供调用方在检索结束后取走并清空用量数据。
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass

from langchain_core.embeddings import Embeddings
from langchain_core.runnables import Runnable, RunnableLambda

from ai_service.contracts.chat import InternalChatStreamRequest, Usage
from ai_service.contracts.rag_types import EmbeddingProfile
from ai_service.providers.embedding_protocol import (
    EmbeddingBatch,
    EmbeddingMedia,
    EmbeddingProviderFactory,
    EmbeddingText,
)
from ai_service.providers.errors import (
    ModelConfigurationError,
    ModelProviderAuthenticationError,
    ModelProviderRateLimitError,
    ModelProviderRequestError,
)
from ai_service.providers.metering import ExecutionUsageTracker, estimate_tokens
from ai_service.providers.protocol import ChatStreamProvider

# ============================================================================
# Profile 绑定 Embeddings
# ============================================================================


class ProfileBoundEmbeddings(Embeddings):
    """请求级 Embeddings；模型发布快照仍由 Java 模型管理下发。"""

    def __init__(
        self,
        providers: EmbeddingProviderFactory,
        profile: EmbeddingProfile,
        input_ids: tuple[str, ...] | None = None,
        usage_tracker: ExecutionUsageTracker | None = None,
    ) -> None:
        self._provider = providers.provider_for(profile)
        self._profile = profile
        self._input_ids = input_ids
        self._batches: list[EmbeddingBatch] = []
        self._usage_tracker = usage_tracker

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        """同步调用只允许在线程外启动；服务主链始终使用原生异步方法。"""
        try:
            asyncio.get_running_loop()
        except RuntimeError:
            return asyncio.run(self.aembed_documents(texts))
        raise RuntimeError("异步执行链中必须调用 aembed_documents")

    async def aembed_documents(self, texts: list[str]) -> list[list[float]]:
        """按输入位置生成稳定标识，并保存本次供应商用量供业务预算结算。"""
        input_ids = self._input_ids or tuple(str(index) for index in range(len(texts)))
        if len(input_ids) != len(texts):
            raise ValueError("INDEX_MISMATCH")
        batch = await self._embed_texts(
            tuple(
                EmbeddingText(input_id, text)
                for input_id, text in zip(input_ids, texts, strict=True)
            ),
        )
        self._validate_batch(batch, list(input_ids))
        self._batches.append(batch)
        return [list(item.values) for item in batch.embeddings]

    def embed_query(self, text: str) -> list[float]:
        """同步查询向量仅用于无事件循环的工具调用。"""
        try:
            asyncio.get_running_loop()
        except RuntimeError:
            return asyncio.run(self.aembed_query(text))
        raise RuntimeError("异步执行链中必须调用 aembed_query")

    async def aembed_query(self, text: str) -> list[float]:
        """使用模型快照声明的 query 输入模式生成单条查询向量。"""
        batch = await self._embed_texts(
            (EmbeddingText("query", text),),
        )
        self._validate_batch(batch, ["query"])
        self._batches.append(batch)
        return list(batch.embeddings[0].values)

    async def _embed_texts(self, inputs: tuple[EmbeddingText, ...]) -> EmbeddingBatch:
        """查询模型在网络调用前占预算，响应校验失败也不能丢弃已发生用量。"""
        tracker = self._usage_tracker
        if tracker is None:
            return await self._provider.embed_texts(self._profile, inputs)
        input_tokens = await asyncio.to_thread(
            estimate_tokens, "\n".join(item.text for item in inputs)
        )
        call_id = tracker.begin("EMBEDDING", self._profile.profile, input_tokens)
        try:
            batch = await self._provider.embed_texts(self._profile, inputs)
            if batch.prompt_tokens > 0:
                tracker.finish(
                    call_id,
                    Usage(
                        input_tokens=batch.prompt_tokens,
                        output_tokens=max(0, batch.total_tokens - batch.prompt_tokens),
                    ),
                    "ACTUAL",
                )
            else:
                tracker.finish(
                    call_id, Usage(input_tokens=input_tokens, output_tokens=0), "ESTIMATED"
                )
            return batch
        except (
            ModelConfigurationError,
            ModelProviderAuthenticationError,
            ModelProviderRequestError,
            ModelProviderRateLimitError,
        ):
            tracker.reject(call_id)
            raise

    def take_batches(self) -> tuple[EmbeddingBatch, ...]:
        """取走并清空当前请求的用量批次，供 Java 结算使用。

        采用"取走即清空"模式而非直接返回内部列表引用，确保：
        - 并发请求之间的用量不会互相污染
        - 已结算的用量不会被重复上报"""
        batches = tuple(self._batches)
        self._batches.clear()
        return batches

    def _validate_batch(self, batch: EmbeddingBatch, expected_ids: list[str]) -> None:
        """校验向量批次的数量、顺序和维度与 Profile 一致，不一致直接拒绝。

        这层校验是 LangChain Embeddings 接口和内部 Provider 之间的安全边界，
        防止供应商返回的乱序或截断结果进入下游 Embedding 消费者。"""
        if len(batch.embeddings) != len(expected_ids):
            raise ValueError("INDEX_MISMATCH")
        if [item.input_id for item in batch.embeddings] != expected_ids:
            raise ValueError("INDEX_MISMATCH")
        if any(len(item.values) != self._profile.dimensions for item in batch.embeddings):
            raise ValueError("INDEX_MISMATCH") from None


# ============================================================================
# 视觉 Embedding Runnable
# ============================================================================


@dataclass(frozen=True, slots=True)
class VisionEmbeddingInput:
    """视觉向量 Runnable 的强类型输入。"""

    profile: EmbeddingProfile
    media: tuple[EmbeddingMedia, ...]


class VisionEmbeddingRunnable:
    """LangChain 暂无多模态 Embeddings 标准接口，使用 Runnable 保持统一编排。"""

    def __init__(self, providers: EmbeddingProviderFactory) -> None:
        self._providers = providers

    def as_runnable(self) -> Runnable[VisionEmbeddingInput, EmbeddingBatch]:
        """返回可组合的异步 Runnable，不泄漏供应商 SDK。"""
        return RunnableLambda(self._invoke)

    async def _invoke(self, request: VisionEmbeddingInput) -> EmbeddingBatch:
        """从已注册适配器生成图片向量并保留供应商用量。"""
        provider = self._providers.provider_for(request.profile)
        return await provider.embed_images(request.profile, request.media)


# ============================================================================
# 聊天 Provider Runnable
# ============================================================================


@dataclass(frozen=True, slots=True)
class ProviderChatResult:
    """将流式 Provider 汇总为 Runnable 可组合的文本和用量。"""

    text: str
    usage: Usage | None


class ProviderChatRunnable:
    """为 Responses 等项目 Provider 提供 LangChain Runnable 边界。"""

    def __init__(self, provider: ChatStreamProvider) -> None:
        self._provider = provider

    def as_runnable(self) -> Runnable[InternalChatStreamRequest, ProviderChatResult]:
        """适用于问题改写等非增量步骤；普通聊天 SSE 继续直接消费流。"""
        return RunnableLambda(self._invoke)

    async def _invoke(self, request: InternalChatStreamRequest) -> ProviderChatResult:
        """汇总非流式链所需的完整文本及最后一次用量事件。"""
        text_parts: list[str] = []
        usage: Usage | None = None
        async for chunk in self._provider.stream(request):
            if chunk.text:
                text_parts.append(chunk.text)
            if chunk.usage is not None:
                usage = chunk.usage
        return ProviderChatResult(text="".join(text_parts), usage=usage)
