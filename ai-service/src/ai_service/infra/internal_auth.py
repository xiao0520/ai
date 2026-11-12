"""Java 到 Python 内部请求的令牌、统一签名与重放防护。

职责：
  - 验证 X-AI-Service-Token（服务令牌）与配置一致。
  - 验证 X-AI-Signature（HMAC-SHA256 签名）未被篡改。
  - 校验 X-AI-Timestamp 不超过 max_age_seconds（默认 5 分钟）。
  - 通过 X-AI-Nonce 防重放攻击，同一 nonce 在有效窗口内只允许一次。
  - 定时清理过期的 nonce 记录，防止内存无限增长。

认证流程：
  1. 读取 5 个签名头（version / token / timestamp / nonce / signature）。
  2. 校验版本号、token 与配置一致。
  3. 校验时间戳未过期。
  4. 对原始请求体计算期望签名，并与请求头中的签名 hmac.compare_digest 比对。
  5. 在锁保护下检查 nonce 是否已使用，已使用者统一返回 403。
"""

from __future__ import annotations

import asyncio
import hmac
import time
from typing import NoReturn

from fastapi import HTTPException, Request, status

from ai_service.infra.internal_signature import (
    SIGNATURE_VERSION,
    create_signature,
    validate_signature_components,
)
from ai_service.infra.settings import Settings

# ============================================================================
# 内部请求认证器
# ============================================================================


class InternalRequestAuthenticator:
    """校验 Java 内部请求并拒绝有效窗口内的重放请求。"""

    def __init__(self, settings: Settings) -> None:
        self._service_token = settings.required_internal_service_token()
        self._signing_key = settings.required_internal_signing_key().encode("utf-8")
        self._max_age_seconds = settings.internal_auth_max_age_seconds
        self._nonces: dict[str, float] = {}
        self._lock = asyncio.Lock()

    async def verify(self, request: Request) -> None:
        """验证服务令牌、统一 HMAC、时戳和 nonce。"""
        version = request.headers.get("X-AI-Signature-Version")
        token = request.headers.get("X-AI-Service-Token")
        timestamp = request.headers.get("X-AI-Timestamp")
        nonce = request.headers.get("X-AI-Nonce")
        signature = request.headers.get("X-AI-Signature")
        path = request.scope.get("path", "")
        if (
            version != SIGNATURE_VERSION
            or request.url.query
            or token is None
            or timestamp is None
            or nonce is None
            or signature is None
            or not validate_signature_components(request.method, path, timestamp, nonce, signature)
            or not hmac.compare_digest(token, self._service_token)
        ):
            self._deny()

        parsed_timestamp = self._parse_timestamp(timestamp)
        if abs(time.time() - parsed_timestamp) > self._max_age_seconds:
            self._deny()

        # 必须对未解析的原始字节验签，避免 JSON 格式差异导致签名语义不一致。
        request_body = await request.body()
        expected = create_signature(
            self._signing_key,
            request.method,
            path,
            timestamp,
            nonce,
            request_body,
        )
        if not hmac.compare_digest(signature, expected):
            self._deny()
        await self._claim_nonce(nonce, parsed_timestamp)

    def _parse_timestamp(self, value: str) -> float:
        """只接收十进制 Unix 秒，拒绝浮点或指数形式。"""
        if not value.isdecimal():
            self._deny()
        try:
            return float(int(value))
        except ValueError, OverflowError:
            self._deny()
        raise AssertionError("unreachable")

    async def _claim_nonce(self, nonce: str, timestamp: float) -> None:
        """在验签成功后独占 nonce，防止同一已签名请求在有效期内重放。"""
        now = time.time()
        async with self._lock:
            self._nonces = {key: expiry for key, expiry in self._nonces.items() if expiry > now}
            if nonce in self._nonces:
                self._deny()
            self._nonces[nonce] = timestamp + self._max_age_seconds

    @staticmethod
    def _deny() -> NoReturn:
        """返回不含签名、时戳或上游细节的稳定认证错误。"""
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={
                "code": "AUTHORIZATION",
                "message": "Internal request authorization failed",
            },
        )