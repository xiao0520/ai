"""RAG 内部协议基础类型；业务 ID 保持字符串，权限由 Java 裁定。

本模块按职责分为以下层次：
  - 类型别名 — 统一约束字符串/数值字段，避免各模块重复定义。
  - 基类 — RagModel 定义全局序列化与严格校验策略。
  - 模型配置快照 — Embedding / Rerank / Vision / Chat 的已发布配置。
  - 索引与文档 — 索引不可变快照、文档处理路由和重排策略。
  - 用量计量 — Token/请求/未知三种计量单元，由 Java 去重计费。
  - 错误契约 — HTTP 非成功响应的稳定错误码。
"""

from typing import Annotated, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel

# ============================================================================
# 类型别名：统一字段约束，禁止各模块重复定义 validator
# ============================================================================

# 禁止数值 ID，避免 JavaScript 精度丢失；不允许前导零或零 ID。
BusinessId = Annotated[str, Field(pattern=r"^[1-9][0-9]{0,18}$")]

# 正整数版本/数量/序号
Revision = Annotated[int, Field(ge=1, le=2_147_483_647)]

# 非负计数
Count = Annotated[int, Field(ge=0, le=2_147_483_647)]

# 通用短标签，用于 worker_id / collection_name / vector_name 等
Label = Annotated[str, Field(min_length=1, max_length=128)]

# SHA-256 十六进制摘要
Digest = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]

# 临时凭证票据，不承载永久密钥
Ticket = Annotated[str, Field(min_length=16, max_length=4096)]

# HTTP/HTTPS 服务端点
Endpoint = Annotated[str, Field(pattern=r"^https?://[^\s?#]+$", max_length=2048)]

# 凭证引用别名
CredentialKey = Annotated[str, Field(pattern=r"^[A-Za-z0-9_.-]{1,100}$")]

# RAG 统一错误码：Java-Python 契约，变更时必须同步 Java 映射
ErrorCode = Literal[
    "VALIDATION",
    "AUTHORIZATION",
    "PROVIDER_AUTHORIZATION",
    "MODEL_UNAVAILABLE",
    "RATE_LIMITED",
    "CANCELLED",
    "TIMEOUT",
    "INTERNAL",
    "KNOWLEDGE_NOT_READY",
    "SCOPE_EXPIRED",
    "VECTOR_UNAVAILABLE",
    "EMBEDDING_UNAVAILABLE",
    "RERANK_UNAVAILABLE",
    "DOCUMENT_UNSUPPORTED",
    "PARSE_FAILED",
    "INDEX_MISMATCH",
    "LEASE_EXPIRED",
    "STALE_ATTEMPT",
    "BUDGET_EXCEEDED",
    "CITATION_INVALID",
    "TOOL_REJECTED",
    "TOOL_CREDENTIAL_MISSING",
    "TOOL_TIMEOUT",
]


# ============================================================================
# 基类与通用请求
# ============================================================================


class RagModel(BaseModel):
    """新增契约拒绝未知字段和隐式标量转换；线上仅使用 camelCase。"""

    model_config = ConfigDict(
        alias_generator=to_camel, extra="forbid", strict=True, allow_inf_nan=False
    )


class VersionedRequest(RagModel):
    """每个独立请求显式声明版本及追踪标识，拒绝静默降级。"""

    contract_version: Literal["rag-v1"]
    request_id: Annotated[str, Field(min_length=6, max_length=128)]


# ============================================================================
# 模型配置快照：Embedding / Rerank / Vision / Chat
# ============================================================================


class ProfileRef(RagModel):
    """引用 Java 已发布配置；执行请求还需携带对应完整快照。"""

    # 配置主键与发布修订号
    id: BusinessId
    revision: Revision


class EmbeddingProfile(RagModel):
    """模型管理发布的向量执行快照；供应商能力由 Python 适配器注册表裁定。

    字段分为三组：
      标识 — 配置引用 + 模型定位
      连接 — API 端点与凭证
      能力 — 向量维度、输入模式、归一化、Token 与超时限制
    """

    # === 标识 ===
    profile: ProfileRef
    provider_code: Label
    model_name: Annotated[str, Field(min_length=1, max_length=255)]

    # === 连接 ===
    api_host: Endpoint
    api_mode: Label
    credential_key: CredentialKey

    # === 能力 ===
    dimensions: Annotated[int, Field(ge=1, le=65_536)]
    document_input_mode: Literal["TEXT_SINGLE", "MULTIMODAL_SINGLE"]
    query_input_mode: Literal["TEXT_SINGLE"]
    normalized: bool
    max_input_tokens: Revision
    timeout_seconds: Annotated[int, Field(ge=1, le=300)]


class RerankProfile(RagModel):
    """发布后的重排适配契约；不指定尚未选择的供应商或模型。"""

    # === 标识 ===
    profile: ProfileRef
    provider_code: Label
    model_name: Annotated[str, Field(min_length=1, max_length=255)]

    # === 连接 ===
    api_host: Endpoint
    adapter_code: Label
    credential_key: CredentialKey

    # === 能力 ===
    input_mode: Literal["TEXT", "MULTIMODAL"]
    max_document_tokens: Revision
    timeout_seconds: Annotated[int, Field(ge=1, le=300)]


class VisionProfile(RagModel):
    """文档图片/OCR 处理模型快照；只保存凭证别名和精确发布修订。"""

    # === 标识 ===
    profile: ProfileRef
    provider_code: Label
    model_name: Annotated[str, Field(min_length=1, max_length=255)]

    # === 连接 ===
    api_host: Endpoint
    api_mode: Label
    credential_key: CredentialKey

    # === 能力 ===
    max_input_tokens: Revision
    timeout_seconds: Annotated[int, Field(ge=1, le=300)]


# ============================================================================
# 文档处理与索引快照
# ============================================================================


class DocumentRerankPolicy(RagModel):
    """保留旧文档处理快照的 Rerank 字段，仅用于历史契约兼容。

    新查询不读取该字段，运行时只使用 KnowledgeScope.retrievalSettings。
    NONE — 旧快照关闭重排
    PROFILE — 旧快照曾绑定重排模型
    """

    mode: Literal["NONE", "PROFILE"]

    # 重排模型配置（mode=NONE 时均为 None）
    text_profile: RerankProfile | None
    multimodal_profile: RerankProfile | None

    @model_validator(mode="after")
    def validate_profiles(self) -> Self:
        """显式关闭时不携带模型；启用时至少需要文本重排模型。"""
        # NONE：不得携带任何模型配置
        if self.mode == "NONE":
            if self.text_profile is not None or self.multimodal_profile is not None:
                raise ValueError("VALIDATION")
            return self
        # PROFILE：至少需要文本重排模型
        if self.text_profile is None:
            raise ValueError("VALIDATION")
        return self


class DocumentProcessingSnapshot(RagModel):
    """Java 为一个物理文档版本固化的处理与查询路由配置。

    字段分为三组：
      模式 — TEXT 或 MULTIMODAL，决定需要哪些模型
      向量模型 — 文本（必选）和视觉（多模态必选）的 Embedding 配置
      辅助模型 — 视觉处理和重排策略
    """

    configuration_revision: Revision

    # === 处理模式 ===
    processing_mode: Literal["TEXT", "MULTIMODAL"]

    # === 向量模型 ===
    text_embedding: EmbeddingProfile
    multimodal_embedding: EmbeddingProfile | None

    # === 辅助模型 ===
    vision_profile: VisionProfile | None
    rerank_policy: DocumentRerankPolicy

    @model_validator(mode="after")
    def validate_processing_mode(self) -> Self:
        """多模态文档必须具备视觉向量模型；纯文档不得携带视觉处理模型。"""
        # TEXT 模式：不得携带多模态相关配置
        if self.processing_mode == "TEXT":
            if self.multimodal_embedding is not None or self.vision_profile is not None:
                raise ValueError("VALIDATION")
            if self.rerank_policy.multimodal_profile is not None:
                raise ValueError("VALIDATION")
            return self
        # MULTIMODAL 模式：必须同时具备多模态向量和视觉处理模型
        if self.multimodal_embedding is None or self.vision_profile is None:
            raise ValueError("VALIDATION")
        # 若启用重排，也必须配置多模态重排模型
        if (
            self.rerank_policy.mode == "PROFILE"
            and self.rerank_policy.multimodal_profile is None
        ):
            raise ValueError("VALIDATION")
        return self


class RagChatModelProfile(RagModel):
    """仅用于 RAG 改写的模型快照；普通聊天原 DTO 的数值 ID 不变。"""

    # === 标识 ===
    profile: ProfileRef
    model_name: Annotated[str, Field(min_length=1, max_length=255)]
    provider_code: Label

    # === 连接 ===
    api_host: Endpoint
    api_mode: Literal["CHAT_COMPLETIONS", "RESPONSES"]
    credential_key: CredentialKey

    # === 能力 ===
    max_input_tokens: Revision
    max_output_tokens: Revision
    timeout_seconds: Annotated[int, Field(ge=1, le=300)]


class IndexSnapshot(RagModel):
    """不可变索引配置；collection 与全部编码版本必须与 Java 发布记录一致。

    字段分为三组：
      集合定义 — 知识库归属、索引版本、collection 名称和距离度量
      向量定义 — 文本 dense + 图片 dense + 文本 sparse 的名称和模型配置
      处理链 — 解析、分割、分词、稀疏编码和清单的版本号
    """

    # === 集合定义 ===
    knowledge_id: BusinessId
    index_version: BusinessId
    collection_name: Annotated[str, Field(pattern=r"^[A-Za-z0-9_-]{1,128}$")]
    distance: Literal["COSINE", "DOT", "EUCLID"]

    # === 向量定义 ===
    dense_vector_name: Label
    dense_vision_vector_name: Label | None
    sparse_vector_name: Label
    embedding: EmbeddingProfile
    multimodal_embedding: EmbeddingProfile | None

    # === 处理链版本 ===
    parser_revision: Label
    splitter_revision: Label
    tokenizer_revision: Label
    sparse_revision: Label
    sparse_corpus_revision: Label
    chunk_size_tokens: Revision
    chunk_overlap_tokens: Count
    config_hash: Digest
    manifest_revision: Revision

    @model_validator(mode="after")
    def validate_layout(self) -> Self:
        """同一集合的向量名称不得冲突，重叠长度必须小于片段长度。"""
        # 向量名称集合内必须唯一
        names = [self.dense_vector_name, self.sparse_vector_name]
        if self.dense_vision_vector_name is not None:
            names.append(self.dense_vision_vector_name)
        if len(names) != len(set(names)):
            raise ValueError("INDEX_MISMATCH")

        # 视觉向量名称与多模态模型必须同时存在或同时不存在
        if (self.dense_vision_vector_name is None) != (self.multimodal_embedding is None):
            raise ValueError("INDEX_MISMATCH")

        # 重叠 Token 数不能 >= 片段 Token 数
        if self.chunk_overlap_tokens >= self.chunk_size_tokens:
            raise ValueError("VALIDATION")
        return self


# ============================================================================
# 用量计量：Token / 请求 / 未知
# ============================================================================


class TokenMeasurement(RagModel):
    """有实际或估算 Token 用量时才发送数量。"""

    unit: Literal["TOKENS"]
    quality: Literal["ACTUAL", "ESTIMATED"]
    input_tokens: Count
    output_tokens: Count


class RequestMeasurement(RagModel):
    """按请求或文档对计量，不伪装为 Token。"""

    unit: Literal["REQUESTS", "DOCUMENT_PAIRS"]
    quality: Literal["ACTUAL", "ESTIMATED"]
    quantity: Count


class UnknownMeasurement(RagModel):
    """供应商未报告用量时不使用零占位。"""

    unit: Literal["UNKNOWN"]
    reason: Literal["NOT_REPORTED", "INTERRUPTED"]


class ExecutionUsageItem(RagModel):
    """Java 按调用标识去重计费；Python 不计算价格。

    每条用量记录绑定唯一的 (stage, call_id, attempt_id, profile) 四元组，
    Java 端按此去重。measurement 通过 discriminator 按 unit 分发到具体计量类型。
    """

    # 调用阶段
    stage: Literal["VISION", "EMBEDDING", "RERANK", "CHAT"]

    # 去重键
    call_id: Label
    attempt_id: Label
    profile: ProfileRef

    # 用量体（discriminator 按 unit 区分 Token/请求/未知）
    measurement: Annotated[
        TokenMeasurement | RequestMeasurement | UnknownMeasurement, Field(discriminator="unit")
    ]


# ============================================================================
# 错误契约
# ============================================================================


class InternalError(RagModel):
    """HTTP 非成功响应只返回稳定错误，不携带上游正文或密钥。"""

    request_id: Label
    error_code: ErrorCode
    retryable: bool
