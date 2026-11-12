"""Java 与 Python 内部 HTTP 请求共用的签名规范。

本模块是所有内部呼叫（Java ↔ Python）的唯一签名标准，确保：
  - 签名方案版本统一（v2），防止版本升级导致的签名歧义。
  - 正则验证所有签名组件，拒绝会造成哈希歧义的输入。
  - HMAC-SHA256(签名密钥, 规范化请求行) 的标准签名算法。
  - 生成的签名头可被对端以相同逻辑验证。
"""

from __future__ import annotations

import hashlib
import hmac
import re
import time
import uuid
from collections.abc import Mapping

# ============================================================================
# 签名常量
# ============================================================================

SIGNATURE_VERSION = "2"
_NONCE = re.compile(r"^[A-Za-z0-9_-]{16,128}$")
_PATH = re.compile(r"^/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+$")
_SIGNATURE = re.compile(r"^[a-f0-9]{64}$")


# ============================================================================
# 签名验证与生成
# ============================================================================


def validate_signature_components(
    method: str,
    path: str,
    timestamp: str,
    nonce: str,
    signature: str,
) -> bool:
    """校验统一签名输入，拒绝会造成签名歧义的路径和头部写法。"""
    return (
        method == "POST"
        and bool(_PATH.fullmatch(path))
        and timestamp.isdecimal()
        and bool(_NONCE.fullmatch(nonce))
        and bool(_SIGNATURE.fullmatch(signature))
    )


def create_signature(
    signing_key: bytes,
    method: str,
    path: str,
    timestamp: str,
    nonce: str,
    body: bytes,
) -> str:
    """对原始正文按统一规则签名；调用方必须发送同一份 body 字节。"""
    digest = hashlib.sha256(body).hexdigest()
    canonical = (f"{SIGNATURE_VERSION}\n{method}\n{path}\n{timestamp}\n{nonce}\n{digest}").encode()
    return hmac.new(signing_key, canonical, hashlib.sha256).hexdigest()


def signed_headers(
    service_token: str,
    signing_key: bytes,
    method: str,
    path: str,
    body: bytes,
    *,
    timestamp: str | None = None,
    nonce: str | None = None,
) -> Mapping[str, str]:
    """生成 Java 与 Python 内部 HTTP 调用共用的认证请求头。"""
    if not service_token:
        raise ValueError("service_token 不能为空")
    request_timestamp = timestamp or str(int(time.time()))
    request_nonce = nonce or str(uuid.uuid4())
    signature = create_signature(signing_key, method, path, request_timestamp, request_nonce, body)
    if not validate_signature_components(method, path, request_timestamp, request_nonce, signature):
        raise ValueError("内部签名请求组件不符合契约")
    return {
        "X-AI-Service-Token": service_token,
        "X-AI-Signature-Version": SIGNATURE_VERSION,
        "X-AI-Timestamp": request_timestamp,
        "X-AI-Nonce": request_nonce,
        "X-AI-Signature": signature,
    }