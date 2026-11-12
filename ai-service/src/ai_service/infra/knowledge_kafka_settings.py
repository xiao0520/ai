"""Kafka 静态部署配置；模型配置与业务状态仍由 Java 持有。

职责：
  - KnowledgeKafkaSettings — 显式启用知识库 Kafka Worker 的部署配置。
  - 不启用时普通聊天不依赖 Kafka，进程只运行 HTTP 服务。
  - 校验 Bootstrap 地址、SASL 凭证和 Topic 合法性，拒绝未配置的启动。
  - 所有 Topic 必须互不相同，防止命令/结果/控制/DLQ 路由混淆。

关键约束：
  - 模型发布快照始终由 Java 按请求下发，不在 Kafka 配置中管理。
  - SASL 模式下必须提供完整的用户名和密码，PLAINTEXT 下允许不配置。
"""

from typing import Literal, Self

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class KnowledgeKafkaSettings(BaseSettings):
    """显式启用知识库队列；不启用时普通聊天不依赖 Kafka。"""

    model_config = SettingsConfigDict(env_file=".env", env_prefix="AI_KAFKA_", extra="ignore")

    enabled: bool = False
    bootstrap_servers: str = "localhost:9092"
    command_topic: str = "knowledge.ingestion.command.v1"
    result_topic: str = "knowledge.job.result.v1"
    control_topic: str = "knowledge.job.control.v1"
    dlq_topic: str = "knowledge.dlq.v1"
    group_id: str = "ai-service-knowledge-consumer"
    worker_id: str = Field(default="ai-service-local", min_length=1, max_length=80)
    security_protocol: Literal["PLAINTEXT", "SASL_PLAINTEXT", "SSL", "SASL_SSL"] = "PLAINTEXT"
    sasl_mechanism: Literal["PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512"] = "PLAIN"
    sasl_username: str | None = None
    sasl_password: SecretStr | None = None
    ssl_ca_file: str | None = None
    processing_timeout_seconds: int = Field(default=600, ge=10, le=1800)
    send_timeout_seconds: int = Field(default=30, ge=5, le=120)
    heartbeat_seconds: int = Field(default=10, ge=5, le=30)
    max_message_bytes: int = Field(default=1_048_576, ge=4096, le=2_097_152)

    @model_validator(mode="after")
    def validate_transport(self) -> Self:
        """拒绝空地址、重复 Topic 和缺少凭证的 SASL 配置。"""
        if not self.enabled:
            return self
        topics = (self.command_topic, self.result_topic, self.control_topic, self.dlq_topic)
        if not self.bootstrap_servers.strip() or any(not topic.strip() for topic in topics):
            raise ValueError("Kafka 地址与 Topic 不能为空")
        if len(set(topics)) != len(topics):
            raise ValueError("Kafka 命令、结果、取消与 DLQ Topic 必须不同")
        if self.security_protocol.startswith("SASL") and (
            not self.sasl_username or self.sasl_password is None
        ):
            raise ValueError("Kafka SASL 凭证配置不完整")
        return self