"""可持久化的入库产物清单；Java 验收前不能作为在线索引成员。

职责：
  - ArtifactManifestEntry — 单个入库点的完整信息（pointId、来源定位、模态）。
  - ArtifactManifest — 单文档单批次的不可变清单，包含全链路路由标识和条目列表。
  - 清单写入受控对象存储后由 Java 验证，激活对应 generation 为可检索状态。

清单写入流程：
  1. Python 完成向量写入 Qdrant 后生成 ArtifactManifest。
  2. 清单包含所有 chunk 的 pointId、来源定位和向量维度元信息。
  3. 清单上传 OSS，由 Java 验证后激活对应 generation 为可检索状态。
"""

from typing import Annotated, Literal, Self

from pydantic import AwareDatetime, Field, model_validator

from ai_service.contracts.rag_types import BusinessId, Count, Digest, Label, RagModel, Revision
from ai_service.contracts.retrieval import SourceLocation

# ============================================================================
# 产物清单条目
# ============================================================================


class ArtifactManifestEntry(RagModel):
    """pointId 与稳定来源一起验收，图片片段额外绑定原始媒体摘要。

    每个 entry 对应 Qdrant 中的一个 point，记录其 ID、序号、模态和来源。
    """

    # Qdrant point UUID
    point_id: Annotated[
        str, Field(pattern=r"^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$")
    ]

    # 片段序号与 Token 数
    chunk_ordinal: Count
    token_count: Revision

    # 模态：TEXT 或 IMAGE
    modality: Literal["TEXT", "IMAGE"]

    # 图片片段必须提供原始媒体哈希（TEXT 条目为 None）
    media_hash: Digest | None

    # 可持久化来源定位
    location: SourceLocation

    @model_validator(mode="after")
    def validate_media(self) -> Self:
        """只有图片片段携带媒体摘要，防止文本点伪装视觉产物。

        校验逻辑：modality==IMAGE ↔ media_hash is not None，
        二者必须严格一致，不允许 IMAGE 缺哈希或 TEXT 携带哈希。
        """
        if (self.modality == "IMAGE") != (self.media_hash is not None):
            raise ValueError("INDEX_MISMATCH")
        return self


# ============================================================================
# 产物清单
# ============================================================================


class ArtifactManifest(RagModel):
    """单文档单批次不可变清单；文件哈希由 Java 按收到的原始字节计算。

    清单是入库产物验收的核心凭证，包含：
      - 路由标识 — job/attempt/generation/knowledge 的全链路追踪
      - 索引配置 — index_version / config_hash / 向量维度
      - 条目列表 — 每个 chunk 的完整定位和模态信息
    """

    contract_version: Literal["rag-v1"]

    # === 路由标识 ===
    job_id: BusinessId
    attempt_id: Label
    generation_id: BusinessId
    knowledge_id: BusinessId

    # === 索引与文档标识 ===
    index_version: BusinessId
    document_id: BusinessId
    document_version_id: BusinessId

    # === 配置与内容校验 ===
    config_hash: Digest
    source_hash: Digest

    # === 向量维度 ===
    vector_dimensions: Annotated[int, Field(ge=1, le=65_536)]
    vision_vector_dimensions: Annotated[int, Field(ge=1, le=65_536)] | None

    # === 时间戳 ===
    created_at: AwareDatetime

    # === 条目列表 ===
    entries: Annotated[list[ArtifactManifestEntry], Field(min_length=1, max_length=100_000)]

    @model_validator(mode="after")
    def validate_vision_layout(self) -> Self:
        """存在图片片段时清单必须声明视觉向量维度。

        校验逻辑：只要 entries 中存在任意 IMAGE 条目，vision_vector_dimensions
        就不能为 None；全部为 TEXT 条目时 vision_vector_dimensions 必须为 None。
        """
        has_images = any(entry.modality == "IMAGE" for entry in self.entries)
        if has_images != (self.vision_vector_dimensions is not None):
            raise ValueError("INDEX_MISMATCH")
        return self