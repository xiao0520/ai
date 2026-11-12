"""火山方舟多模态 Embedding 适配器。

职责：
  - 使用火山方舟异步 SDK 调用 /embeddings/multimodal 接口。
  - 同时支持文本向量化（EmbeddingText）和图片向量化（EmbeddingMedia）。
  - 按 Profile 校验向量维度，拒绝维度不符或非有限数的向量。

关键约束：
  - 凭证、模型名和 API Host 均来自 Java IndexSnapshot，不使用本地默认值。
  - 图片必须为 PNG/JPEG，转为 data URI 提交，拒绝非图片文件。
  - 每次调用创建临时 AsyncArk 客户端，结束后立即 close。
  - api_mode 必须为 "ARK_MULTIMODAL_EMBEDDINGS"。
"""

from __future__ import annotations

import asyncio
import base64
import math
from typing import cast

from arkruntime import AsyncArk
from arkruntime._exceptions import (
    ArkAPIConnectionError,
    ArkAPIError,
    ArkAPIStatusError,
    ArkAPITimeoutError,
)
from arkruntime.types.multimodal_embedding import (
    EmbeddingInputParam,
    EmbeddingInputType,
    EncodingFormat,
    MultiModalEmbeddingResponse,
)

from ai_service.contracts.rag_types import EmbeddingProfile
from ai_service.infra.settings import Settings
from ai_service.providers.embedding_protocol import (
    DenseEmbedding,
    EmbeddingBatch,
    EmbeddingMedia,
    EmbeddingText,
)
from ai_service.providers.errors import (
    ModelConfigurationError,
    ModelProviderAuthenticationError,
    ModelProviderError,
    ModelProviderRateLimitError,
    ModelProviderRequestError,
    ModelProviderTimeoutError,
)

ARK_MULTIMODAL_EMBEDDINGS = "ARK_MULTIMODAL_EMBEDDINGS"


# ============================================================================
# 方舟多模态 Embedding Provider
# ============================================================================


class ArkMultimodalEmbeddingProvider:
    """使用火山方舟异步 SDK 调用 `/embeddings/multimodal` 接口，同时支持文本和图片向量化。

    负责将 EmbeddingProfile（来自 Java IndexSnapshot）映射为方舟 SDK 的实际调用，
    包括凭证解析、输入格式转换、并发控制和异常归一化。所有错误统一转为
    ModelProviderError 语义，不向调用方泄露任何方舟 SDK 的原始异常类型。

    使用方式：
        provider = ArkMultimodalEmbeddingProvider(settings)
        batch = await provider.embed_texts(profile, inputs)
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def embed_texts(
        self, profile: EmbeddingProfile, inputs: tuple[EmbeddingText, ...]
    ) -> EmbeddingBatch:
        """将文本片段独立生成密集向量，按 Profile 校验维度。

        每个文本片段作为独立的 EmbeddingInputParam 提交，不使用批量拼接策略，
        因为方舟多模态接口不支持多段文本合并为单次调用。"""
        if not inputs or any(not item.text.strip() for item in inputs):
            raise ModelProviderRequestError("Embedding input must not be empty")
        request_inputs = tuple(
            EmbeddingInputParam(type=EmbeddingInputType.text, text=item.text) for item in inputs
        )
        return await self._execute(
            profile,
            tuple(item.input_id for item in inputs),
            request_inputs,
        )

    async def embed_images(
        self, profile: EmbeddingProfile, inputs: tuple[EmbeddingMedia, ...]
    ) -> EmbeddingBatch:
        """将 PNG/JPEG 图片转为 data URI 后逐图生成视觉向量。

        对于知识库中抽取的图片（如 DOCX 内嵌图），先读取磁盘文件二进制内容，
        Base64 编码后拼接为 RFC 2397 data URI，再通过 image_url 格式提交方舟。
        图片向量供视觉语义检索使用，独立于文本向量。"""
        if not inputs:
            raise ModelProviderRequestError("Embedding input must not be empty")
        request_inputs: list[EmbeddingInputParam] = []
        for item in inputs:
            if item.media_type not in {"image/png", "image/jpeg"} or not item.path.is_file():
                raise ModelProviderRequestError("Embedding image input is invalid")
            data = await asyncio.to_thread(item.path.read_bytes)
            encoded = base64.b64encode(data).decode("ascii")
            request_inputs.append(
                cast(
                    EmbeddingInputParam,
                    {
                        "type": EmbeddingInputType.image_url,
                        "image_url": {"url": f"data:{item.media_type};base64,{encoded}"},
                    },
                )
            )
        return await self._execute(
            profile,
            tuple(item.input_id for item in inputs),
            tuple(request_inputs),
        )

    async def _execute(
        self,
        profile: EmbeddingProfile,
        input_ids: tuple[str, ...],
        request_inputs: tuple[EmbeddingInputParam, ...],
    ) -> EmbeddingBatch:
        """并发调度多个独立 Embedding 请求，保持输入顺序并汇总结果。

        使用固定数量的 asyncio Task 作为 worker pool，通过共享的原子索引自增
        分发任务，无需预分配输入到特定 worker，负载自然均衡。

        SDK 的 max_retries 设为 0，重试由上层 Kafka at-least-once 语义兜底，
        避免 SDK 隐式重试与上游重试叠加产生雪崩。

        单次调用失败的 Task 会通过 gather 传播异常，此时取消所有未完成 Task
        并关闭 HTTP 连接池，保证不泄露资源。"""
        if profile.api_mode != ARK_MULTIMODAL_EMBEDDINGS:
            raise ModelConfigurationError("The embedding API mode is not supported by Ark")
        try:
            credential = self._settings.credential_for(profile.credential_key)
        except ValueError as error:
            raise ModelConfigurationError("The embedding credential is not configured") from error

        client = AsyncArk(
            base_url=str(profile.api_host).rstrip("/"),
            api_key=credential.get_secret_value(),
            timeout=min(self._settings.provider_timeout_seconds, profile.timeout_seconds),
            max_retries=0,
        )
        results: list[DenseEmbedding | None] = [None] * len(input_ids)
        usages: list[tuple[int, int] | None] = [None] * len(input_ids)
        next_index = 0
        index_lock = asyncio.Lock()

        async def run_worker() -> None:
            """领取下一个输入并把结果写回原位置，保持批次顺序。"""
            nonlocal next_index
            while True:
                async with index_lock:
                    if next_index >= len(input_ids):
                        return
                    index = next_index
                    next_index += 1
                response = await self._request(client, profile, request_inputs[index])
                results[index] = DenseEmbedding(
                    input_id=input_ids[index],
                    values=self._vector(response, profile.dimensions),
                )
                usages[index] = self._usage(response)

        tasks = [
            asyncio.create_task(run_worker())
            for _ in range(
                min(self._settings.rag_embedding_max_concurrency, len(request_inputs))
            )
        ]
        try:
            await asyncio.gather(*tasks)
        except BaseException:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            raise
        finally:
            await client.close()

        if any(result is None for result in results) or any(usage is None for usage in usages):
            raise ModelProviderError("The embedding provider returned an incomplete batch")
        complete_results = tuple(result for result in results if result is not None)
        complete_usages = tuple(usage for usage in usages if usage is not None)
        return EmbeddingBatch(
            embeddings=complete_results,
            request_count=len(complete_results),
            prompt_tokens=sum(item[0] for item in complete_usages),
            total_tokens=sum(item[1] for item in complete_usages),
        )

    async def _request(
        self,
        client: AsyncArk,
        profile: EmbeddingProfile,
        request_input: EmbeddingInputParam,
    ) -> MultiModalEmbeddingResponse:
        """调用方舟 API 并将所有异常统一映射为 ModelProviderError 语义。

        timeout 取全局配置和 Profile 指定值的最小值，确保单个请求不会因配置错误
        导致无限制等待。所有 Ark SDK 异常链通过 ``raise ... from error`` 保留
        原始调用栈，便于排查问题。"""
        try:
            return await client.multimodal_embeddings.create(
                model=profile.model_name,
                input=[request_input],
                encoding_format=EncodingFormat.float,
                dimensions=profile.dimensions,
                timeout=min(self._settings.provider_timeout_seconds, profile.timeout_seconds),
            )
        except ArkAPIStatusError as error:
            raise self._translate_status(error) from error
        except (ArkAPITimeoutError, TimeoutError) as error:
            raise ModelProviderTimeoutError("The embedding provider timed out") from error
        except ArkAPIConnectionError as error:
            raise ModelProviderError("The embedding provider connection failed") from error
        except ArkAPIError as error:
            raise ModelProviderError("The embedding provider request failed") from error
        except ModelProviderError:
            raise
        except Exception as error:
            raise ModelProviderError("The embedding provider request failed") from error

    @staticmethod
    def _vector(response: MultiModalEmbeddingResponse, dimensions: int) -> tuple[float, ...]:
        """从方舟响应中提取向量，校验维度是否与 Profile 一致。

        返回不可变 tuple，防止下游意外修改向量值。维度不符或数值非法
        （inf/NaN）直接拒绝，避免污染 Qdrant 索引。"""
        raw = response.data.embedding
        if isinstance(raw, str) or len(raw) != dimensions:
            raise ModelProviderError("The embedding provider returned an invalid dimension")
        values = tuple(float(value) for value in raw)
        if not values or any(not math.isfinite(value) for value in values):
            raise ModelProviderError("The embedding provider returned an invalid vector")
        return values

    @staticmethod
    def _usage(response: MultiModalEmbeddingResponse) -> tuple[int, int]:
        """提取 Token 用量 (prompt_tokens, total_tokens)，用于回传 Java 做额度结算。

        负值或 total < prompt 视为异常，直接拒绝而非容忍，防止结算数据失真。"""
        prompt_tokens = response.usage.prompt_tokens
        total_tokens = response.usage.total_tokens
        if prompt_tokens < 0 or total_tokens < prompt_tokens:
            raise ModelProviderError("The embedding provider returned invalid usage")
        return prompt_tokens, total_tokens

    @staticmethod
    def _translate_status(error: ArkAPIStatusError) -> ModelProviderError:
        """将方舟 HTTP 状态码映射为统一的 ModelProviderError 语义。

        映射规则：
        - 401/403 → 认证失败（凭证过期或无权访问模型）
        - 429     → 限流（并发过高或配额耗尽）
        - 408/504 → 超时（服务端或网关超时）
        - 400/404/409/422 → 请求参数错误（模型名、维度等）
        - 其他     → 通用错误"""
        status = error.status_code
        if status in {401, 403}:
            return ModelProviderAuthenticationError(
                "The embedding provider rejected authentication"
            )
        if status == 429:
            return ModelProviderRateLimitError(
                "The embedding provider rate limit was reached"
            )
        if status in {408, 504}:
            return ModelProviderTimeoutError("The embedding provider timed out")
        if status in {400, 404, 409, 422}:
            return ModelProviderRequestError("The embedding provider rejected the request")
        return ModelProviderError("The embedding provider request failed")