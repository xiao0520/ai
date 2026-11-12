"""Java 授权检索范围、成员清单和候选复核的签名客户端。

职责：
  - 通过统一 HMAC 签名调用 Java 内部授权接口。
  - page_manifest — 分页读取绑定同一 scope_snapshot_id 的发布成员。
  - validate_candidates — 让 Java 使用当前权限和内容状态复核候选来源。
  - 区分超时可重试（TIMEOUT/INTERNAL）和结构错误不可重试（INDEX_MISMATCH）。

关键约束：
  - Python 只消费 Java 已裁定的请求级授权结果，不做本地权限判定。
  - 响应超 2MB 直接拒绝，防止 Java 错误返回超大载荷。
"""

from __future__ import annotations

from typing import Protocol, TypeVar, runtime_checkable
from urllib.parse import urlsplit

import httpx2
from pydantic import BaseModel, ValidationError

from ai_service.contracts.rag_types import InternalError
from ai_service.contracts.retrieval import (
    CandidateValidationResponse,
    ManifestPageRequest,
    ManifestPageResponse,
    ValidateCandidatesRequest,
)
from ai_service.infra.internal_signature import signed_headers
from ai_service.infra.settings import Settings

_MAX_RESPONSE_BYTES = 2 * 1024 * 1024
ResponseT = TypeVar("ResponseT", bound=BaseModel)


# ============================================================================
# 异常与协议
# ============================================================================


class KnowledgeScopeClientError(RuntimeError):
    """Java 拒绝范围或返回无效契约时使用稳定错误码。"""

    def __init__(self, error_code: str, *, retryable: bool) -> None:
        super().__init__(error_code)
        self.error_code = error_code
        self.retryable = retryable


@runtime_checkable
class KnowledgeScopeClient(Protocol):
    """Python 只消费 Java 已裁定的请求级授权结果。"""

    async def page_manifest(
        self, scope_snapshot_id: str, request: ManifestPageRequest
    ) -> ManifestPageResponse:
        """读取绑定同一范围快照的发布成员分页。"""
        ...

    async def validate_candidates(
        self, scope_snapshot_id: str, request: ValidateCandidatesRequest
    ) -> CandidateValidationResponse:
        """让 Java 使用当前权限和内容状态复核完整来源元组。"""
        ...


class JavaKnowledgeScopeClient:
    """通过统一 HMAC 签名调用 Java 内部授权接口。"""

    def __init__(self, settings: Settings, http_client: httpx2.AsyncClient) -> None:
        self._settings = settings
        self._http_client = http_client

    async def page_manifest(
        self, scope_snapshot_id: str, request: ManifestPageRequest
    ) -> ManifestPageResponse:
        """委托 _post 查询指定 scope_snapshot_id 下已发布成员分页。"""
        route = f"/internal/knowledge/scopes/{scope_snapshot_id}/manifest-pages"
        return await self._post(route, request, ManifestPageResponse)

    async def validate_candidates(
        self, scope_snapshot_id: str, request: ValidateCandidatesRequest
    ) -> CandidateValidationResponse:
        """委托 _post 让 Java 复核候选来源的权限和内容版本。"""
        route = f"/internal/knowledge/scopes/{scope_snapshot_id}/validate-candidates"
        return await self._post(route, request, CandidateValidationResponse)

    async def _post(
        self,
        route: str,
        request: BaseModel,
        response_type: type[ResponseT],
    ) -> ResponseT:
        """统一的 POST → Java 调用：签名、发送、解析响应，区分超时可重试和结构错误不可重试。

        响应超 2MB 直接拒绝（正常分页/候选复核不应超过此上限）。"""
        url = self._settings.java_internal_base_url + route
        body = request.model_dump_json(by_alias=True).encode("utf-8")
        headers = dict(
            signed_headers(
                self._settings.required_internal_service_token(),
                self._settings.required_internal_signing_key().encode("utf-8"),
                "POST",
                urlsplit(url).path,
                body,
            )
        )
        headers["Content-Type"] = "application/json"
        headers["Accept"] = "application/json"
        try:
            response = await self._http_client.post(url, content=body, headers=headers)
        except httpx2.TimeoutException as error:
            raise KnowledgeScopeClientError("TIMEOUT", retryable=True) from error
        except httpx2.RequestError as error:
            raise KnowledgeScopeClientError("INTERNAL", retryable=True) from error
        if len(response.content) > _MAX_RESPONSE_BYTES:
            raise KnowledgeScopeClientError("INTERNAL", retryable=True)
        if response.status_code != 200:
            raise self._remote_error(response.content)
        try:
            return response_type.model_validate_json(response.content)
        except ValidationError as error:
            raise KnowledgeScopeClientError("INDEX_MISMATCH", retryable=False) from error

    @staticmethod
    def _remote_error(body: bytes) -> KnowledgeScopeClientError:
        """解析 Java InternalError，空体或格式错误视为 INTERNAL 可重试。"""
        try:
            error = InternalError.model_validate_json(body)
        except ValidationError:
            return KnowledgeScopeClientError("INTERNAL", retryable=True)
        return KnowledgeScopeClientError(error.error_code, retryable=error.retryable)