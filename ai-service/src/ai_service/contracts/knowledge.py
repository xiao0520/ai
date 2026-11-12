"""Java 任务与产物的内部 DTO；本模块不领取任务、不维护状态、不访问存储。

本模块定义了知识库入库流程中的核心契约模型：
  - ArtifactTicket — Java 签发的 OSS 短期访问票据，禁止传递全局密钥。
  - ParseStats — 解析验收数据，用于识别空文本、图片处理和截断。
  - IngestionResult — 入库产物，Java 验收后发布，向量写完不等于可检索。
  - ArtifactTicketRequest/Response — 刷新对象级短期票据的请求/响应。
  - ArtifactUploadResponse — Java 保存清单后返回的 OSS 对象信息。
"""

from typing import Annotated, Literal, Self

from pydantic import AwareDatetime, Field, model_validator

from ai_service.contracts.rag_types import (
    BusinessId,
    Count,
    Digest,
    ExecutionUsageItem,
    Label,
    RagModel,
    Ticket,
    VersionedRequest,
)


class ArtifactTicket(RagModel):
    """Java 签发的对象级短期能力；禁止传递 OSS 全局密钥。

    每个 ticket 绑定单个 OSS object_id 和单一操作权限，包含：
      - object_id / operation — 操作对象与权限类型
      - url / token — 实际访问 URL 和临时认证 Token
      - expires_at — 过期时间，过期后票据失效
      - content_hash / max_bytes — 可选的下载校验用哈希和大小上限
    """

    # 操作目标
    object_id: Label
    operation: Literal["READ_SOURCE", "WRITE_MANIFEST", "READ_MANIFEST"]

    # 访问凭证
    url: Annotated[str, Field(pattern=r"^https?://[^\s#]+$", max_length=8192)]
    token: Ticket
    expires_at: AwareDatetime

    # 校验信息（可选）
    content_hash: Digest | None
    max_bytes: Annotated[int, Field(ge=1, le=2_147_483_647)]


class ParseStats(RagModel):
    """解析验收数据用于识别空文本、图片处理和截断；不是质量达标声明。

    解析器完成文档处理后输出此统计，Java 据此判断是否需要重试或降级处理。
    """

    # 文档结构统计
    page_count: Count
    block_count: Count
    character_count: Count

    # 图片处理统计
    image_count: Count
    ocr_character_count: Count

    # 跳过的块和图片（因格式不支持等原因）
    skipped_block_count: Count
    skipped_image_count: Count

    # 是否因 Token 限制等原因被截断
    truncated: bool


class IngestionResult(RagModel):
    """入库产物只在 Java 验收后发布；向量写完不等于可检索。

    入库完成后 Python 上报此结果，Java 验证 manifest 后才激活 generation。
    字段分为三组：
      路由 — job_type + manifest 的定位和哈希
      向量统计 — 文本 dense/sparse 和图片视觉向量的数量与维度
      解析与用量 — 解析统计和模型调用用量
    """

    job_type: Literal["INGESTION"]

    # === Manifest 定位 ===
    manifest_object_id: Label
    manifest_hash: Digest

    # === 向量统计 ===
    chunk_count: Count
    dense_count: Count
    sparse_count: Count
    vision_count: Count
    vector_dimensions: Annotated[int, Field(ge=1, le=65_536)]
    vision_vector_dimensions: Annotated[int, Field(ge=1, le=65_536)] | None

    # === 解析与用量 ===
    parse_stats: ParseStats
    usage_items: Annotated[list[ExecutionUsageItem], Field(max_length=1000)]

    @model_validator(mode="after")
    def validate_counts(self) -> Self:
        """所有片段必须有文本 dense/sparse；图片片段还必须有视觉向量。

        校验规则：
          1. chunk_count >= 1，且 chunk_count == dense_count == sparse_count。
          2. vision_count <= chunk_count（图片片段是子集）。
          3. vision_count == 0 ↔ vision_vector_dimensions is None。
          4. 只有解析器发现图片时，才允许声明视觉向量。
          5. truncated == True 表示解析失败，不应接受。
        """
        # 规则 1：文本向量数量与 chunk 数量一致
        if (
            self.chunk_count < 1
            or self.chunk_count != self.dense_count
            or self.chunk_count != self.sparse_count
            or self.vision_count > self.chunk_count
        ):
            raise ValueError("INDEX_MISMATCH")

        # 规则 2-3：视觉向量存在性一致
        if (self.vision_count == 0) != (self.vision_vector_dimensions is None):
            raise ValueError("INDEX_MISMATCH")

        # 规则 4：没有来源图片时不得虚报视觉向量。
        if self.parse_stats.image_count == 0 and self.vision_count != 0:
            raise ValueError("INDEX_MISMATCH")

        # 规则 5：截断表示解析失败
        if self.parse_stats.truncated:
            raise ValueError("PARSE_FAILED")
        return self


class ArtifactTicketRequest(VersionedRequest):
    """请求刷新已绑定对象的短期票据，不接受任意 URL 或路径。

    Java 通过此请求为 Python 获取 OSS 对象的短期访问票据，
    operation 限定了票据的操作权限。
    """

    # 请求追踪
    attempt_id: Label
    generation_id: BusinessId
    worker_id: Label

    # 操作目标
    operation: Literal["READ_SOURCE", "WRITE_MANIFEST", "READ_MANIFEST"]
    object_id: Label


class ArtifactTicketResponse(RagModel):
    """只返回与当前任务关联的单对象票据。"""

    ticket: ArtifactTicket


class ArtifactUploadResponse(RagModel):
    """Java 保存清单原始字节后返回实际 OSS 对象及可复核摘要。

    Python 上传清单到 OSS 后，Java 返回此响应确认保存成功，
    包含对象 ID、内容哈希和实际大小，供 Python 在 IngestionResult 中引用。
    """

    object_id: Label
    content_hash: Digest
    size_bytes: Annotated[int, Field(ge=1, le=2_147_483_647)]