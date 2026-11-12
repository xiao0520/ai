"""Python 执行服务的静态部署配置。

职责：
  - 从环境变量和 .env 文件加载 Pydantic 模型验证的静态配置。
  - 提供进程级共享配置实例供 FastAPI 依赖注入复用。
  - 校验内部认证必备密钥、Java 基地址和 Qdrant URL 的合法性。
  - 按 Java 下发的凭证别名（credential_key）查找对应的供应商密钥。

关键约束：
  - 模型发布快照始终由 Java 按请求下发，不从这里读取。
  - 供应商密钥通过 credential_for(credential_key) 间接访问，拒绝模型直接携带密钥。
"""

from __future__ import annotations

import re
from functools import lru_cache
from urllib.parse import urlsplit

from pydantic import AliasChoices, BaseModel, ConfigDict, Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# ============================================================================
# 静态配置模型
# ============================================================================


class VolcengineAkSkCredential(BaseModel):
    """火山引擎 IAM 签名凭证；仅从部署环境按别名解析。"""

    model_config = ConfigDict(extra="forbid", frozen=True)

    access_key_id: SecretStr = Field(
        validation_alias=AliasChoices("accessKeyId", "accessKey", "ak")
    )
    secret_access_key: SecretStr = Field(
        validation_alias=AliasChoices("secretAccessKey", "secretKey", "sk")
    )


ProviderCredential = SecretStr | VolcengineAkSkCredential


class Settings(BaseSettings):
    """加载静态运行配置，模型发布快照始终由 Java 按请求下发。"""

    model_config = SettingsConfigDict(env_file=".env", env_prefix="AI_", extra="ignore")

    # ========================================================================
    # 环境与内部认证
    # ========================================================================
    environment: str = Field(default="dev")
    internal_service_token: SecretStr | None = None
    internal_signing_key: SecretStr | None = None
    internal_auth_max_age_seconds: int = Field(default=300, gt=0, le=900)

    # ========================================================================
    # 模型 Provider
    # ========================================================================
    provider_timeout_seconds: float = Field(default=60.0, gt=0, le=300)
    provider_keepalive_seconds: float = Field(default=10.0, ge=10, le=15)
    provider_credentials: dict[str, ProviderCredential] = Field(default_factory=dict)

    # ========================================================================
    # Java 内部通信
    # ========================================================================
    java_internal_base_url: str = "http://127.0.0.1:8080"
    artifact_connect_timeout_seconds: float = Field(default=10.0, gt=0, le=60)
    artifact_read_timeout_seconds: float = Field(default=120.0, gt=0, le=600)
    artifact_ticket_ready_timeout_seconds: float = Field(default=15.0, gt=0, le=120)
    artifact_ticket_retry_interval_seconds: float = Field(default=0.25, ge=0.05, le=5)
    artifact_download_chunk_bytes: int = Field(default=1_048_576, ge=4096, le=8_388_608)
    artifact_max_source_bytes: int = Field(default=1_073_741_824, ge=1, le=1_073_741_824)
    artifact_temp_directory: str | None = None

    # ========================================================================
    # RAG 解析限制
    # ========================================================================
    rag_parse_max_characters: int = Field(default=5_000_000, ge=1, le=100_000_000)
    rag_parse_max_blocks: int = Field(default=100_000, ge=1, le=1_000_000)
    rag_pdf_max_pages: int = Field(default=2_000, ge=1, le=100_000)
    rag_office_max_units: int = Field(default=2_000, ge=1, le=100_000)
    rag_spreadsheet_max_rows: int = Field(default=200_000, ge=1, le=2_000_000)
    rag_embedding_max_concurrency: int = Field(default=4, ge=1, le=32)
    rag_min_dense_score: float = Field(default=0.35, ge=-1.0, le=1.0)
    rag_min_rerank_score: float = Field(default=0.05, ge=0.0, le=1.0)
    rag_image_max_count: int = Field(default=500, ge=1, le=10_000)
    rag_image_max_bytes: int = Field(default=20_971_520, ge=1_024, le=104_857_600)
    rag_image_max_pixels: int = Field(default=40_000_000, ge=1, le=200_000_000)
    rag_vision_max_concurrency: int = Field(default=2, ge=1, le=16)
    rag_vision_max_output_tokens: int = Field(default=2_048, ge=1, le=32_768)

    # ========================================================================
    # Qdrant 向量库
    # ========================================================================
    qdrant_url: str = "http://127.0.0.1:6333"
    qdrant_api_key: SecretStr | None = None
    qdrant_timeout_seconds: int = Field(default=30, ge=1, le=300)
    rag_qdrant_batch_size: int = Field(default=64, ge=1, le=1000)

    @model_validator(mode="after")
    def validate_required_secrets(self) -> Settings:
        """启动时拒绝匿名内部调用及可造成票据 URL 歧义的 Java 地址。"""
        if (
            self.internal_service_token is None
            or not self.internal_service_token.get_secret_value().strip()
        ):
            raise ValueError("AI_INTERNAL_SERVICE_TOKEN must not be empty")
        if (
            self.internal_signing_key is None
            or not self.internal_signing_key.get_secret_value().strip()
        ):
            raise ValueError("AI_INTERNAL_SIGNING_KEY must not be empty")
        base_url = self.java_internal_base_url.strip().rstrip("/")
        parsed = urlsplit(base_url)
        if (
            parsed.scheme not in {"http", "https"}
            or parsed.hostname is None
            or parsed.username is not None
            or parsed.password is not None
            or parsed.query
            or parsed.fragment
            or (parsed.path and not re.fullmatch(r"(?:/[A-Za-z0-9_-]+)+", parsed.path))
        ):
            raise ValueError("AI_JAVA_INTERNAL_BASE_URL must be an unambiguous HTTP(S) base URL")
        self.java_internal_base_url = base_url
        qdrant_url = self.qdrant_url.strip().rstrip("/")
        qdrant = urlsplit(qdrant_url)
        if (
            qdrant.scheme not in {"http", "https"}
            or qdrant.hostname is None
            or qdrant.username is not None
            or qdrant.password is not None
            or qdrant.query
            or qdrant.fragment
        ):
            raise ValueError("AI_QDRANT_URL must be an unambiguous HTTP(S) URL")
        self.qdrant_url = qdrant_url
        return self

    def credential_for(self, credential_key: str) -> SecretStr:
        """按 Java 下发的凭证别名获取供应商密钥，不接受模型直接携带密钥。"""
        credential = self.provider_credentials.get(credential_key)
        if not isinstance(credential, SecretStr) or not credential.get_secret_value().strip():
            raise ValueError("The requested provider credential is not configured")
        return credential

    def volcengine_ak_sk_for(self, credential_key: str) -> VolcengineAkSkCredential:
        """按别名获取火山引擎 AK/SK，拒绝把方舟 API Key 当作 IAM 凭证。"""
        credential = self.provider_credentials.get(credential_key)
        if not isinstance(credential, VolcengineAkSkCredential):
            raise TypeError("The requested Volcengine AK/SK credential is not configured")
        if (
            not credential.access_key_id.get_secret_value().strip()
            or not credential.secret_access_key.get_secret_value().strip()
        ):
            raise ValueError("The requested Volcengine AK/SK credential is not configured")
        return credential

    def required_internal_service_token(self) -> str:
        """返回已完成启动校验的内部服务令牌。"""
        if self.internal_service_token is None:
            raise ValueError("The internal service token is not configured")
        return self.internal_service_token.get_secret_value()

    def required_internal_signing_key(self) -> str:
        """返回已完成启动校验的 HMAC 签名密钥。"""
        if self.internal_signing_key is None:
            raise ValueError("The internal signing key is not configured")
        return self.internal_signing_key.get_secret_value()


@lru_cache
def get_settings() -> Settings:
    """创建进程级共享配置实例，供依赖注入复用。"""
    return Settings()
