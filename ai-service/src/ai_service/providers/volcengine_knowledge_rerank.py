"""火山引擎知识库 Doubao Seed Rerank 适配器。

该接口与方舟 API Key 接口、Cohere Rerank 协议都不兼容。请求发送到
/api/knowledge/service/rerank，并使用火山引擎 IAM AK/SK 的 SignerV4 签名。
"""

from __future__ import annotations

import json
import math
import re
from urllib.parse import urlsplit, urlunsplit

import httpx2
from pydantic import AliasChoices, BaseModel, ConfigDict, Field, ValidationError
from volcengine.auth.SignerV4 import SignerV4  # type: ignore[import-untyped]
from volcengine.base.Request import Request as VolcengineRequest  # type: ignore[import-untyped]
from volcengine.Credentials import Credentials  # type: ignore[import-untyped]

from ai_service.contracts.rag_types import RerankProfile
from ai_service.infra.settings import Settings, VolcengineAkSkCredential
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

VOLCENGINE_KNOWLEDGE_RERANK = "VOLCENGINE_KNOWLEDGE_RERANK"
_MAX_DOCUMENTS = 200
_MAX_RESPONSE_BYTES = 2 * 1024 * 1024
_SERVICE = "air"
_DEFAULT_REGION = "cn-beijing"
_ENDPOINT_PATH = "/api/knowledge/service/rerank"
_DOUBAO_SEED_ALIASES = {
    "doubao-seed-1.6-rerank",
    "doubao-seed-1-6-rerank",
    "doubao-seed-1.6-rerank-250715",
}


class _VolcengineRerankResult(BaseModel):
    """知识库重排接口的分数数组和可选用量字段。"""

    model_config = ConfigDict(extra="ignore", strict=True, allow_inf_nan=False)

    scores: list[float]
    token_usage: int | None = Field(
        default=None,
        ge=0,
        validation_alias=AliasChoices("tokenUsage", "token_usage"),
    )


class _VolcengineRerankResponse(BaseModel):
    """解析业务状态与数据，兼容接口字段的两种命名。"""

    model_config = ConfigDict(extra="ignore", strict=True)

    code: int
    message: str | None = None
    request_id: str | None = Field(
        default=None,
        validation_alias=AliasChoices("requestId", "request_id"),
    )
    data: _VolcengineRerankResult | None = Field(
        default=None,
        validation_alias=AliasChoices("data", "result"),
    )


class VolcengineKnowledgeRerankProvider:
    """调用火山引擎知识库 Rerank 接口并恢复为统一的输入顺序分数。"""

    def __init__(self, settings: Settings, http_client: httpx2.AsyncClient) -> None:
        self._settings = settings
        self._http_client = http_client

    async def rerank(
        self,
        profile: RerankProfile,
        query: str,
        documents: tuple[RerankDocument, ...],
    ) -> RerankBatch:
        """以 AK/SK 签名请求供应商，并校验全部候选分数。"""
        if profile.adapter_code != VOLCENGINE_KNOWLEDGE_RERANK or profile.input_mode != "TEXT":
            raise ModelConfigurationError(
                "The rerank profile is not Volcengine knowledge rerank compatible"
            )
        if not query.strip() or not documents or any(not item.text.strip() for item in documents):
            raise ModelProviderRequestError("Rerank input must not be empty")
        if len(documents) > _MAX_DOCUMENTS:
            raise ModelProviderRequestError("Rerank document count exceeds the protocol limit")
        try:
            credential = self._settings.volcengine_ak_sk_for(profile.credential_key)
        except (TypeError, ValueError) as error:
            raise ModelConfigurationError(
                "The Volcengine rerank AK/SK credential is not configured"
            ) from error

        endpoint = self._endpoint(str(profile.api_host))
        body = json.dumps(
            {
                "datas": [{"query": query, "content": document.text} for document in documents],
                "rerank_model": self._model_name(profile.model_name),
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
        try:
            response = await self._http_client.post(
                endpoint,
                headers=self._signed_headers(endpoint, body, credential),
                content=body.encode("utf-8"),
                timeout=min(
                    self._settings.provider_timeout_seconds,
                    profile.timeout_seconds,
                ),
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
            payload = _VolcengineRerankResponse.model_validate(raw)
        except (json.JSONDecodeError, ValidationError, UnicodeDecodeError) as error:
            raise ModelProviderError("The rerank provider returned an invalid response") from error
        if payload.code != 0:
            raise self._business_error(payload.code)
        if payload.data is None or len(payload.data.scores) != len(documents):
            raise ModelProviderError("The rerank provider returned an incomplete batch")
        if any(not math.isfinite(score) for score in payload.data.scores):
            raise ModelProviderError("The rerank provider returned a non-finite score")
        return RerankBatch(
            scores=tuple(
                RerankScore(input_id=document.input_id, score=score)
                for document, score in zip(documents, payload.data.scores, strict=True)
            ),
            request_count=1,
            document_pair_count=len(documents),
            input_tokens=payload.data.token_usage,
        )

    @staticmethod
    def _model_name(model_name: str) -> str:
        """把已知豆包模型别名映射为接口接受的统一名称。"""
        normalized = model_name.strip().lower()
        if normalized in _DOUBAO_SEED_ALIASES:
            return "doubao-seed-rerank"
        return model_name.strip()

    @staticmethod
    def _endpoint(api_host: str) -> str:
        """验证 HTTPS 主机并补齐知识库重排接口路径。"""
        parsed = urlsplit(api_host.rstrip("/"))
        if (
            parsed.scheme != "https"
            or parsed.hostname is None
            or parsed.username is not None
            or parsed.password is not None
            or parsed.query
            or parsed.fragment
        ):
            raise ModelConfigurationError(
                "The Volcengine rerank API host must be an unambiguous HTTPS URL"
            )
        path = parsed.path.rstrip("/")
        target_path = path if path.endswith(_ENDPOINT_PATH) else f"{path}{_ENDPOINT_PATH}"
        return urlunsplit((parsed.scheme, parsed.netloc, target_path, "", ""))

    @staticmethod
    def _region(endpoint: str) -> str:
        """从端点主机识别签名地域，无法识别时使用北京地域。"""
        hostname = urlsplit(endpoint).hostname or ""
        matched = re.search(
            r"(cn-(?:beijing|guangzhou|shanghai)|ap-southeast-1)",
            hostname,
        )
        return matched.group(1) if matched is not None else _DEFAULT_REGION

    @classmethod
    def _signed_headers(
        cls,
        endpoint: str,
        body: str,
        credential: VolcengineAkSkCredential,
    ) -> dict[str, str]:
        """用当前请求体和 AK/SK 生成火山 SignerV4 请求头。"""
        parsed = urlsplit(endpoint)
        request = VolcengineRequest()
        request.set_shema(parsed.scheme)
        request.set_method("POST")
        request.set_host(parsed.netloc)
        request.set_path(parsed.path)
        request.set_headers(
            {
                "Accept": "application/json",
                "Content-Type": "application/json",
                "Host": parsed.netloc,
            }
        )
        request.set_body(body)
        credentials = Credentials(
            credential.access_key_id.get_secret_value(),
            credential.secret_access_key.get_secret_value(),
            _SERVICE,
            cls._region(endpoint),
        )
        SignerV4.sign(request, credentials)
        return {str(key): str(value) for key, value in request.headers.items()}

    @staticmethod
    def _business_error(code: int) -> ModelProviderError:
        """将 HTTP 200 中的业务错误码映射为统一模型异常。"""
        if code == 1000001:
            return ModelProviderAuthenticationError(
                "The rerank provider rejected AK/SK authentication"
            )
        if code == 300004:
            return ModelProviderRateLimitError("The rerank provider rate limit was reached")
        if code == 1000002:
            return ModelProviderRequestError("The rerank provider rejected the request")
        return ModelProviderError("The rerank provider request failed")

    @staticmethod
    def _status_error(status_code: int) -> ModelProviderError:
        """将 HTTP 状态映射为脱敏的模型异常。"""
        if status_code in {401, 403}:
            return ModelProviderAuthenticationError(
                "The rerank provider rejected AK/SK authentication"
            )
        if status_code == 429:
            return ModelProviderRateLimitError("The rerank provider rate limit was reached")
        if status_code in {408, 504}:
            return ModelProviderTimeoutError("The rerank provider timed out")
        if 400 <= status_code < 500:
            return ModelProviderRequestError("The rerank provider rejected the request")
        return ModelProviderError("The rerank provider request failed")
