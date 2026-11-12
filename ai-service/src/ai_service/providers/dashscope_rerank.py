"""阿里云百炼文本重排序协议适配器。

支持百炼当前两类 HTTP 协议：
  - qwen3-rerank 使用 /compatible-api/v1/reranks。
  - qwen3.7-text-rerank、qwen3-vl-rerank 和 gte-rerank-v2 使用
    /api/v1/services/rerank/text-rerank/text-rerank。

执行快照只传递 Java 已审核并发布的模型配置；本模块负责把统一的重排序输入
转换为百炼请求，并将供应商响应恢复为与输入顺序一致的分数列表。
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

DASHSCOPE_TEXT_RERANK = "DASHSCOPE_TEXT_RERANK"
_QWEN3_COMPATIBLE_MODEL = "qwen3-rerank"
_MULTIMODAL_MODEL = "qwen3-vl-rerank"
_MAX_RESPONSE_BYTES = 2 * 1024 * 1024


class _DashScopeRerankResult(BaseModel):
    """百炼返回的单个候选分数。"""

    model_config = ConfigDict(extra="ignore", strict=True, allow_inf_nan=False)

    index: int = Field(ge=0)
    relevance_score: float


class _DashScopeRerankOutput(BaseModel):
    """百炼原生协议的 output 节点。"""

    model_config = ConfigDict(extra="ignore", strict=True)

    results: list[_DashScopeRerankResult]


class _DashScopeRerankUsage(BaseModel):
    """供应商实际计量；只有文档对计量时留空，由调用层估算文本。"""

    model_config = ConfigDict(extra="ignore", strict=True)
    total_tokens: int | None = Field(default=None, ge=0)
    prompt_tokens: int | None = Field(default=None, ge=0)


class _DashScopeRerankResponse(BaseModel):
    """兼容百炼原生协议和 qwen3-rerank 兼容协议的响应。"""

    model_config = ConfigDict(extra="ignore", strict=True)

    output: _DashScopeRerankOutput | None = None
    results: list[_DashScopeRerankResult] | None = None
    usage: _DashScopeRerankUsage | None = None


class DashScopeTextRerankProvider:
    """调用阿里云百炼文本重排序接口并归一化响应。"""

    def __init__(self, settings: Settings, http_client: httpx2.AsyncClient) -> None:
        self._settings = settings
        self._http_client = http_client

    async def rerank(
        self,
        profile: RerankProfile,
        query: str,
        documents: tuple[RerankDocument, ...],
    ) -> RerankBatch:
        """按模型选择百炼协议，并返回与输入文档顺序一致的分数。"""
        if profile.adapter_code != DASHSCOPE_TEXT_RERANK or profile.input_mode != "TEXT":
            raise ModelConfigurationError(
                "The rerank profile is not DashScope text rerank compatible"
            )
        if not query.strip() or not documents or any(not item.text.strip() for item in documents):
            raise ModelProviderRequestError("Rerank input must not be empty")
        max_documents = 100 if profile.model_name == _MULTIMODAL_MODEL else 500
        if len(documents) > max_documents:
            raise ModelProviderRequestError("Rerank document count exceeds the protocol limit")

        try:
            credential = self._settings.credential_for(profile.credential_key)
        except ValueError as error:
            raise ModelConfigurationError("The rerank credential is not configured") from error

        try:
            response = await self._http_client.post(
                self._endpoint(str(profile.api_host), profile.model_name),
                headers={
                    "Authorization": f"Bearer {credential.get_secret_value()}",
                    "Accept": "application/json",
                    "Content-Type": "application/json",
                },
                json=self._request_body(profile, query, documents),
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
            payload = _DashScopeRerankResponse.model_validate(raw)
        except (json.JSONDecodeError, ValidationError, UnicodeDecodeError) as error:
            raise ModelProviderError("The rerank provider returned an invalid response") from error
        results = (
            payload.results
            if payload.results is not None
            else payload.output.results
            if payload.output is not None
            else None
        )
        if results is None:
            raise ModelProviderError("The rerank provider returned an invalid response")

        # 两种协议都以 index 对齐输入，缺失或重复时不能返回部分结果。
        ordered: list[RerankScore | None] = [None] * len(documents)
        for result in results:
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
            input_tokens=(payload.usage.total_tokens or payload.usage.prompt_tokens)
            if payload.usage is not None
            else None,
        )

    @staticmethod
    def _request_body(
        profile: RerankProfile,
        query: str,
        documents: tuple[RerankDocument, ...],
    ) -> dict[str, object]:
        """按模型选择兼容协议或百炼原生协议的请求结构。"""
        texts = [item.text for item in documents]
        if profile.model_name == _QWEN3_COMPATIBLE_MODEL:
            return {
                "model": profile.model_name,
                "query": query,
                "documents": texts,
                "top_n": len(documents),
            }
        return {
            "model": profile.model_name,
            "input": {"query": query, "documents": texts},
            "parameters": {"top_n": len(documents)},
        }

    @staticmethod
    def _endpoint(api_host: str, model_name: str) -> str:
        """规范化模型专属路径，避免重复追加完整接口路径。"""
        parsed = urlsplit(api_host.rstrip("/"))
        path = parsed.path.rstrip("/")
        if model_name == _QWEN3_COMPATIBLE_MODEL:
            suffix = "/compatible-api/v1/reranks"
            if path.endswith(suffix):
                target_path = path
            elif path.endswith("/compatible-api/v1"):
                target_path = f"{path}/reranks"
            else:
                target_path = f"{path}{suffix}"
        else:
            suffix = "/api/v1/services/rerank/text-rerank/text-rerank"
            if path.endswith(suffix):
                target_path = path
            elif path.endswith("/api/v1"):
                target_path = f"{path}/services/rerank/text-rerank/text-rerank"
            else:
                target_path = f"{path}{suffix}"
        return urlunsplit((parsed.scheme, parsed.netloc, target_path, "", ""))

    @staticmethod
    def _status_error(status_code: int) -> ModelProviderError:
        """将 HTTP 错误映射为脱敏的认证、限流或请求异常。"""
        if status_code in {401, 403, 498}:
            return ModelProviderAuthenticationError("The rerank provider rejected authentication")
        if status_code == 429:
            return ModelProviderRateLimitError("The rerank provider rate limit was exceeded")
        if 400 <= status_code < 500:
            return ModelProviderRequestError("The rerank provider rejected the request")
        return ModelProviderError("The rerank provider failed")
