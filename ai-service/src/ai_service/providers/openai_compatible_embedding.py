"""OpenAI 兼容 `/embeddings` 文本向量协议适配器。

职责：
  - 按模型快照调用任意声明 OpenAI Embeddings 兼容的供应商 API。
  - 严格按响应 index 恢复输入顺序，拒绝索引错乱或不完整响应。
  - 校验向量维度与 Profile 声明一致、值均为有限数。

关键约束：
  - 每次调用创建临时 AsyncOpenAI 客户端，调用结束后立即 close。
  - 不缓存连接（Embedding 调用频次低，复用连接无显著收益）。
  - api_mode 必须为 "OPENAI_EMBEDDINGS"，其他模式由专门适配器处理。
"""

from __future__ import annotations

import math

from openai import APIConnectionError, APIStatusError, APITimeoutError, AsyncOpenAI

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
    ModelProviderError,
    ModelProviderRequestError,
    ModelProviderTimeoutError,
    translate_api_status_error,
)

OPENAI_EMBEDDINGS = "OPENAI_EMBEDDINGS"


# ============================================================================
# OpenAI 兼容 Embedding Provider
# ============================================================================


class OpenAiCompatibleEmbeddingProvider:
    """按模型快照调用任意声明 OpenAI Embeddings 兼容的供应商。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def embed_texts(
        self, profile: EmbeddingProfile, inputs: tuple[EmbeddingText, ...]
    ) -> EmbeddingBatch:
        """一次批量调用并严格按响应 index 恢复输入顺序。"""
        if profile.api_mode != OPENAI_EMBEDDINGS:
            raise ModelConfigurationError("The embedding API mode is not OpenAI compatible")
        if not inputs or any(not item.text.strip() for item in inputs):
            raise ModelProviderRequestError("Embedding input must not be empty")
        try:
            credential = self._settings.credential_for(profile.credential_key)
        except ValueError as error:
            raise ModelConfigurationError("The embedding credential is not configured") from error

        client = AsyncOpenAI(
            api_key=credential.get_secret_value(),
            base_url=str(profile.api_host).rstrip("/"),
            timeout=min(self._settings.provider_timeout_seconds, profile.timeout_seconds),
            max_retries=0,
        )
        try:
            response = await client.embeddings.create(
                model=profile.model_name,
                input=[item.text for item in inputs],
                encoding_format="float",
                dimensions=profile.dimensions,
            )
            # 供应商结果可能乱序；先按 index 回填，再拒绝重复或缺失项。
            ordered: list[DenseEmbedding | None] = [None] * len(inputs)
            for item in response.data:
                if item.index < 0 or item.index >= len(inputs) or ordered[item.index] is not None:
                    raise ModelProviderError("The embedding provider returned invalid indexes")
                raw = item.embedding
                if isinstance(raw, str) or len(raw) != profile.dimensions:
                    raise ModelProviderError(
                        "The embedding provider returned an invalid dimension"
                    )
                values = tuple(float(value) for value in raw)
                if any(not math.isfinite(value) for value in values):
                    raise ModelProviderError("The embedding provider returned an invalid vector")
                ordered[item.index] = DenseEmbedding(
                    input_id=inputs[item.index].input_id,
                    values=values,
                )
            if any(item is None for item in ordered):
                raise ModelProviderError("The embedding provider returned an incomplete batch")
            usage = response.usage
            prompt_tokens = usage.prompt_tokens if usage is not None else 0
            total_tokens = usage.total_tokens if usage is not None else 0
            if prompt_tokens < 0 or total_tokens < prompt_tokens:
                raise ModelProviderError("The embedding provider returned invalid usage")
            return EmbeddingBatch(
                embeddings=tuple(item for item in ordered if item is not None),
                request_count=1,
                prompt_tokens=prompt_tokens,
                total_tokens=total_tokens,
            )
        except APIStatusError as error:
            raise translate_api_status_error(error) from error
        except APITimeoutError as error:
            raise ModelProviderTimeoutError("The embedding provider timed out") from error
        except APIConnectionError as error:
            raise ModelProviderError("The embedding provider connection failed") from error
        except ModelProviderError:
            raise
        except Exception as error:
            raise ModelProviderError("The embedding provider request failed") from error
        finally:
            await client.close()

    async def embed_images(
        self, profile: EmbeddingProfile, inputs: tuple[EmbeddingMedia, ...]
    ) -> EmbeddingBatch:
        """标准 OpenAI /v1/embeddings 端点不接收图片，禁止静默降级。

        不允许将图片偷偷转为 OCR 文本再调用 embed_texts，因为这会绕过视觉模型
        的 token 计费和 Java 端的图片额度审计。图片必须走方舟多模态 Embedding。"""
        raise ModelConfigurationError(
            "The OpenAI embeddings protocol does not support image inputs"
        )