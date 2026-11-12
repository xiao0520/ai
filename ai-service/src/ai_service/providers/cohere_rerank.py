"""Cohere Rerank v2 兼容协议适配器。

职责：
  - 按 Profile 调用 Cohere v2 兼容文本重排接口（/v2/rerank）。
  - 使用 httpx2 而非 LangChain/OpenAI SDK，因为 Cohere Rerank 请求体不兼容 OpenAI 客户端。
  - 归一化响应为 RerankScore 列表，校验结果数量与输入一致。

关键约束：
  - adapter_code 必须为 "COHERE_RERANK_V2" 且 input_mode 必须为 "TEXT"。
  - 多模态 Rerank 必须由其他已注册适配器处理。
  - 复用 FastAPI 生命周期管理的 HTTP 客户端，单次请求不关闭连接池。
  - 单次最大文档数 1000。
"""

from __future__ import annotations

import json
import math
from urllib.parse import urlsplit, urlunsplit

import httpx2
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from ai_service.contracts.rag_types import RerankProfile
from ai_service.infra.settings import Settings
from ai_service.providers.errors import (
    ModelConfigurationError,
    ModelProviderAuthenticationError,
    ModelProviderError,
    ModelProviderRateLimitError,
    ModelProviderRequestError,
    ModelProviderTimeoutError,
)
from ai_service.providers.rerank_protocol import (
    RerankBatch,
    RerankDocument,
    RerankScore,
)

COHERE_RERANK_V2 = "COHERE_RERANK_V2"
_MAX_RESPONSE_BYTES = 2 * 1024 * 1024


# ============================================================================
# 响应模型
# ============================================================================


class _CohereRerankResult(BaseModel):
    """供应商返回的候选原始位置及相关性分数。"""
    model_config = ConfigDict(extra="ignore", strict=True, allow_inf_nan=False)

    index: int = Field(ge=0)
    relevance_score: float


class _CohereRerankResponse(BaseModel):
    """只解析重排结果；无关响应字段由 Pydantic 忽略。"""
    model_config = ConfigDict(extra="ignore", strict=True)

    results: list[_CohereRerankResult]


# ============================================================================
# Cohere V2 重排 Provider
# ============================================================================


class CohereV2RerankProvider:
    """按 Profile 调用 Cohere v2 兼容文本重排接口并归一化响应。

    使用 httpx2 而非 LangChain/OpenAI SDK，因为 Cohere Rerank v2 的请求体
    （documents 数组、top_n、max_tokens_per_doc）与 OpenAI 客户端不兼容，
    且本项目只需要 POST + JSON 的简单调用，不需要 SDK 的额外抽象。

    Profile 通过 adapter_code == "COHERE_RERANK_V2" 和 input_mode == "TEXT"
    进行双锁定，多模态 Rerank 必须由其他已注册适配器处理。"""

    def __init__(
        self,
        settings: Settings,
        http_client: httpx2.AsyncClient,
    ) -> None:
        self._settings = settings
        self._http_client = http_client

    async def rerank(
        self,
        profile: RerankProfile,
        query: str,
        documents: tuple[RerankDocument, ...],
    ) -> RerankBatch:
        """调用 /v2/rerank；多模态 Profile 必须由其他已注册适配器处理。"""
        if profile.adapter_code != COHERE_RERANK_V2 or profile.input_mode != "TEXT":
            raise ModelConfigurationError("The rerank profile is not Cohere v2 text compatible")
        if not query.strip() or not documents or any(not item.text.strip() for item in documents):
            raise ModelProviderRequestError("Rerank input must not be empty")
        if len(documents) > 1000:
            raise ModelProviderRequestError("Rerank document count exceeds the protocol limit")
        try:
            credential = self._settings.credential_for(profile.credential_key)
        except ValueError as error:
            raise ModelConfigurationError("The rerank credential is not configured") from error
        try:
            response = await self._http_client.post(
                self._endpoint(str(profile.api_host)),
                headers={
                    "Authorization": f"Bearer {credential.get_secret_value()}",
                    "Accept": "application/json",
                    "Content-Type": "application/json",
                },
                json={
                    "model": profile.model_name,
                    "query": query,
                    "documents": [item.text for item in documents],
                    "top_n": len(documents),
                    "max_tokens_per_doc": profile.max_document_tokens,
                },
                timeout=min(self._settings.provider_timeout_seconds, profile.timeout_seconds),
            )
        except httpx2.TimeoutException as error:
            raise ModelProviderTimeoutError("The rerank provider timed out") from error
        except httpx2.RequestError as error:
            raise ModelProviderError("The rerank provider connection failed") from error
        if response.status_code != 200:
            raise self._status_error(response.status_code)
        if len(response.content) > _MAX_RESPONSE_BYTES:
            raise ModelProviderError("The rerank provider response is too large")
        try:
            raw: object = json.loads(response.content)
            payload = _CohereRerankResponse.model_validate(raw)
        except (json.JSONDecodeError, ValidationError, UnicodeDecodeError) as error:
            raise ModelProviderError("The rerank provider returned an invalid response") from error
        # 按供应商 index 恢复原始候选顺序，禁止重复或遗漏结果。
        ordered: list[RerankScore | None] = [None] * len(documents)
        for result in payload.results:
            if result.index >= len(documents) or ordered[result.index] is not None:
                raise ModelProviderError("The rerank provider returned invalid indexes")
            if not math.isfinite(result.relevance_score):
                raise ModelProviderError("The rerank provider returned a non-finite score")
            ordered[result.index] = RerankScore(
                input_id=documents[result.index].input_id,
                score=result.relevance_score,
            )
        if any(item is None for item in ordered):
            raise ModelProviderError("The rerank provider returned an incomplete batch")
        return RerankBatch(
            scores=tuple(item for item in ordered if item is not None),
            request_count=1,
            document_pair_count=len(documents),
        )

    @staticmethod
    def _endpoint(api_host: str) -> str:
        """从 Profile 的 api_host 构造完整的 /v2/rerank 端点 URL。

        兼容三种 Java 下发的 api_host 格式：
        - 完整路径：   https://api.xxx.com/v2/rerank → 直接使用
        - 只到版本号： https://api.xxx.com/v2        → 追加 /rerank
        - 纯域名：     https://api.xxx.com            → 追加 /v2/rerank"""
        parsed = urlsplit(api_host.rstrip("/"))
        path = parsed.path.rstrip("/")
        if path.endswith("/v2/rerank"):
            target_path = path
        elif path.endswith("/v2"):
            target_path = f"{path}/rerank"
        else:
            target_path = f"{path}/v2/rerank"
        return urlunsplit((parsed.scheme, parsed.netloc, target_path, "", ""))

    @staticmethod
    def _status_error(status_code: int) -> ModelProviderError:
        """将 Cohere HTTP 状态码映射为统一的 ModelProviderError 语义。

        与 OpenAI 状态码映射规则一致，额外加入 498 覆盖部分反向代理集群
        使用的 Token 无效/过期检测码。"""
        if status_code in {401, 403, 498}:
            return ModelProviderAuthenticationError(
                "The rerank provider rejected authentication"
            )
        if status_code == 429:
            return ModelProviderRateLimitError(
                "The rerank provider rate limit was reached"
            )
        if status_code in {408, 504}:
            return ModelProviderTimeoutError("The rerank provider timed out")
        if status_code in {400, 404, 409, 422}:
            return ModelProviderRequestError("The rerank provider rejected the request")
        return ModelProviderError("The rerank provider request failed")