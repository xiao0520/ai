"""检索范围、候选复核及来源定位契约；Python 不计算资源授权。

本模块按职责分为以下层次：
  - 来源定位 — 多种文件格式内位置的联合类型（PDF/DOCX/表格/图片/文本区间）。
  - 来源引用 — SourceRef + SourceLocation 的不可变引用，用于持久化存储。
  - 检索配置 — KnowledgeScope / RetrievalProfile / RagExecutionScope 的执行参数。
  - 清单分页 — 索引成员的分页查询请求与响应。
  - 候选复核 — Java 签发访问票据前的候选校验。
  - 检索测试 — 生产检索链的诊断请求与响应。
"""

from typing import Annotated, Literal, Self

from pydantic import AwareDatetime, Field, model_validator

from ai_service.contracts.rag_types import (
    BusinessId,
    Count,
    Digest,
    ExecutionUsageItem,
    IndexSnapshot,
    Label,
    ProfileRef,
    RagModel,
    RerankProfile,
    Revision,
    Ticket,
    VersionedRequest,
)

# ============================================================================
# 来源定位器：多种文件格式内位置的联合类型
# ============================================================================


class PdfPageLocator(RagModel):
    """PDF 页码从 1 开始，不以预览页排序替代原文件位置。"""

    kind: Literal["PDF_PAGE"]
    page_number: Revision


class TextRangeLocator(RagModel):
    """文本偏移按 Unicode 码点，区间为左闭右开。"""

    kind: Literal["TEXT_RANGE"]
    start_offset: Count
    end_offset: Revision

    @model_validator(mode="after")
    def validate_range(self) -> Self:
        """拒绝空区间和倒置区间。"""
        if self.end_offset <= self.start_offset:
            raise ValueError("VALIDATION")
        return self


class DocxBlockLocator(RagModel):
    """DOCX 块序号从 0 开始，标题路径保存在来源对象上。"""

    kind: Literal["DOCX_BLOCK"]
    block_index: Count


class PresentationBlockLocator(RagModel):
    """PPTX 内容定位到幻灯片、形状及可选表格行。"""

    kind: Literal["PRESENTATION_BLOCK"]
    container: Literal["PPTX"]
    slide_number: Revision
    shape_index: Count
    row_index: Count | None


class SpreadsheetRangeLocator(RagModel):
    """CSV/XLSX 单行内容的工作表和单元格范围，坐标从 1 开始。"""

    kind: Literal["SPREADSHEET_RANGE"]
    container: Literal["CSV", "XLSX"]
    sheet_name: Label
    row_start: Revision
    row_end: Revision
    column_start: Revision
    column_end: Revision

    @model_validator(mode="after")
    def validate_range(self) -> Self:
        """结束行列使用不包含边界，必须严格大于开始行列。"""
        if self.row_end <= self.row_start or self.column_end <= self.column_start:
            raise ValueError("VALIDATION")
        return self


class TableRangeLocator(RagModel):
    """表格与行列序号从 0 开始，结束行列为不包含的边界。"""

    kind: Literal["TABLE_RANGE"]

    # 表格标识
    table_index: Count

    # 行区间 [row_start, row_end)
    row_start: Count
    row_end: Revision

    # 列区间 [column_start, column_end)
    column_start: Count
    column_end: Revision

    @model_validator(mode="after")
    def validate_range(self) -> Self:
        """表格区间边界必须严格递增。"""
        if self.row_end <= self.row_start or self.column_end <= self.column_start:
            raise ValueError("VALIDATION")
        return self


class ImageLocator(RagModel):
    """图片来源定位到原文件容器；媒体摘要用于防止位置复用到不同内容。"""

    kind: Literal["IMAGE"]

    # 容器类型与在容器中的索引
    container: Literal["PDF", "DOCX", "MARKDOWN", "PPTX", "XLSX"]
    image_index: Count

    # 容器相关的定位字段（不同容器有不同合法组合，由 validate_container_location 校验）
    page_number: Revision | None
    slide_number: Revision | None
    start_offset: Count | None
    end_offset: Revision | None
    sheet_name: Label | None
    row_number: Revision | None
    column_number: Revision | None

    # 媒体完整性校验
    content_hash: Digest
    media_type: Annotated[str, Field(pattern=r"^image/(?:png|jpeg)$", max_length=32)]

    @model_validator(mode="after")
    def validate_container_location(self) -> Self:
        """校验每类容器允许的来源定位字段。

        PDF      — 仅需 page_number。
        PPTX     — 仅需 slide_number。
        MARKDOWN — 仅需 start_offset/end_offset。
        XLSX     — 必须包含 sheet_name，单元格锚点要么成对提供、要么都为空。
        DOCX     — 只使用 image_index。
        """
        if self.container == "PDF":
            if (
                self.page_number is None
                or self.slide_number is not None
                or self.start_offset is not None
                or self.end_offset is not None
                or self.sheet_name is not None
                or self.row_number is not None
                or self.column_number is not None
            ):
                raise ValueError("VALIDATION")
        elif self.container == "PPTX":
            if (
                self.page_number is not None
                or self.slide_number is None
                or self.start_offset is not None
                or self.end_offset is not None
                or self.sheet_name is not None
                or self.row_number is not None
                or self.column_number is not None
            ):
                raise ValueError("VALIDATION")
        elif self.container == "MARKDOWN":
            if (
                self.page_number is not None
                or self.slide_number is not None
                or self.start_offset is None
                or self.end_offset is None
                or self.end_offset <= self.start_offset
                or self.sheet_name is not None
                or self.row_number is not None
                or self.column_number is not None
            ):
                raise ValueError("VALIDATION")
        elif self.container == "XLSX":
            if (
                self.page_number is not None
                or self.slide_number is not None
                or self.start_offset is not None
                or self.end_offset is not None
                or self.sheet_name is None
                or (self.row_number is None) != (self.column_number is None)
            ):
                raise ValueError("VALIDATION")
        elif (
            self.page_number is not None
            or self.slide_number is not None
            or self.start_offset is not None
            or self.end_offset is not None
            or self.sheet_name is not None
            or self.row_number is not None
            or self.column_number is not None
        ):
            raise ValueError("VALIDATION")
        return self


# ============================================================================
# 来源引用：不可变引用元组，用于持久化与候选复核
# ============================================================================


class SourceRef(RagModel):
    """候选复核使用完整不可变来源元组，禁止只按 chunkId 授权。

    六元组唯一标识一个 chunk：(knowledge_id, index_version, document_id,
    document_version_id, generation_id, chunk_id)，加上 content_hash
    确保指向的内容未被篡改。
    """

    # 知识库与索引
    knowledge_id: BusinessId
    index_version: BusinessId

    # 文档与版本
    document_id: BusinessId
    document_version_id: BusinessId

    # generation 与 chunk
    generation_id: BusinessId
    chunk_id: Label

    # 内容完整性校验
    content_hash: Digest


class SourceLocation(RagModel):
    """可持久化引用位置；不包含对象存储下载链接。

    三个组成部分：
      source  — 不可变的来源元组引用
      title   — 文档标题与标题路径（面包屑）
      locator — 文件内具体位置（按 kind 区分定位器类型）
    """

    # 不可变来源
    source: SourceRef

    # 展示用元信息
    title: Annotated[str, Field(min_length=1, max_length=512)]
    title_path: Annotated[list[Label], Field(max_length=32)]

    # 文件内定位（discriminator 按 kind 分发）
    locator: Annotated[
        PdfPageLocator
        | TextRangeLocator
        | DocxBlockLocator
        | PresentationBlockLocator
        | SpreadsheetRangeLocator
        | TableRangeLocator
        | ImageLocator,
        Field(discriminator="kind"),
    ]


# ============================================================================
# 检索配置：范围声明、检索策略与执行快照
# ============================================================================


class KnowledgeRetrievalSettings(RagModel):
    """单个知识库在本次请求中冻结的召回、重排和命中阈值。"""

    dense_enabled: bool
    sparse_enabled: bool
    recall_count: Annotated[int, Field(ge=1, le=200)]
    rrf_k: Annotated[int, Field(ge=1, le=1000)]
    rerank_enabled: bool
    rerank_profile: RerankProfile | None
    rerank_input_count: Annotated[int, Field(ge=1, le=200)]
    rerank_output_count: Annotated[int, Field(ge=1, le=100)]
    relevance_threshold: Annotated[float, Field(ge=0, le=1)] | None

    @model_validator(mode="after")
    def validate_strategy(self) -> Self:
        """至少开启一种召回；重排、阈值和候选数量必须与所选策略匹配。"""
        if not (self.dense_enabled or self.sparse_enabled):
            raise ValueError("VALIDATION")
        if self.rerank_enabled and self.rerank_input_count > self.recall_count:
            raise ValueError("VALIDATION")
        if self.rerank_enabled and self.rerank_output_count > self.rerank_input_count:
            raise ValueError("VALIDATION")
        if (
            self.relevance_threshold is not None
            and not self.dense_enabled
            and not self.rerank_enabled
        ):
            raise ValueError("VALIDATION")
        if self.rerank_enabled != (self.rerank_profile is not None):
            raise ValueError("VALIDATION")
        return self


class KnowledgeScope(RagModel):
    """Java 授权后固定库、索引和修订号，不能独立笛卡尔积过滤。

    每个 scope 绑定一个 (knowledge_id, index_version)，permission_revision
    和 manifest_revision 用于 Java 端判断快照是否过期。
    """

    knowledge_id: BusinessId
    index_version: BusinessId
    permission_revision: Revision
    manifest_revision: Revision
    retrieval_settings: KnowledgeRetrievalSettings


class RetrievalProfile(RagModel):
    """生产与检索测试共用的候选数量和上下文参数。

    两阶段检索流水线：
      DENSE_BM25_RRF_RERANK — 生产模式，dense + sparse → RRF 融合 → Rerank → 最终 Top-K
      DEVELOPMENT_NO_RERANK — 开发模式，跳过 Rerank 阶段
    """

    profile: ProfileRef
    mode: Literal["DENSE_BM25_RRF_RERANK", "DEVELOPMENT_NO_RERANK"]

    # === 第一阶段：Dense + Sparse 召回 ===
    dense_top_k: Annotated[int, Field(ge=1, le=200)]
    sparse_top_k: Annotated[int, Field(ge=1, le=200)]

    # === 第二阶段：RRF 融合 ===
    rrf_k: Annotated[int, Field(ge=1, le=1000)]

    # === 第三阶段：Rerank 精排 ===
    rerank_top_k: Annotated[int, Field(ge=1, le=200)]

    # === 第四阶段：最终裁剪 ===
    final_top_k: Annotated[int, Field(ge=1, le=100)]
    neighbor_window: Annotated[int, Field(ge=0, le=3)]
    max_context_chunks: Annotated[int, Field(ge=1, le=100)]

    @model_validator(mode="after")
    def validate_limits(self) -> Self:
        """最终证据不能超过候选或上下文数量上限。

        final_top_k 受 rerank_top_k 和 max_context_chunks 双重约束：
        最终输出的 chunk 数不能超过精排后的候选数，也不能超过上下文窗口容量。
        """
        if self.final_top_k > self.rerank_top_k or self.final_top_k > self.max_context_chunks:
            raise ValueError("VALIDATION")
        return self


class RagExecutionScope(RagModel):
    """Java 为单次调用绑定授权和执行配置；空范围禁止执行检索。

    字段分为四层：
      授权 — 快照 ID + 过期时间 + 知识库范围列表
      检索 — 检索策略引用与完整快照
      索引 — 每个知识库的完整索引配置快照
      限额 — 模型可使用的知识上下文预算
    """

    # === 授权 ===
    contract_version: Literal["rag-v1"]
    mode: Literal["KNOWLEDGE_QA"]
    scope_snapshot_id: BusinessId
    expires_at: AwareDatetime
    knowledge_scopes: Annotated[list[KnowledgeScope], Field(min_length=1, max_length=100)]

    # === 检索策略 ===
    retrieval_profile: ProfileRef
    retrieval_snapshot: RetrievalProfile

    # === 索引快照 ===
    index_snapshots: Annotated[list[IndexSnapshot], Field(min_length=1, max_length=100)]

    # === 上下文限额 ===
    context_budget_tokens: Revision

    @model_validator(mode="after")
    def validate_snapshots(self) -> Self:
        """只检查快照结构一致；实际 ACL、发布状态与期限仍由 Java 校验。

        校验规则：
          1. retrieval_profile 引用必须与快照内的 profile 一致。
          2. 每个知识库必须携带知识库级查询配置。
          3. knowledge_scopes 与 index_snapshots 的 (knowledge_id, index_version,
             manifest_revision) 三元组必须一一对应且内部无重复。
        """
        profile = self.retrieval_snapshot

        # 规则 1：检索策略引用与快照一致
        if self.retrieval_profile != profile.profile:
            raise ValueError("INDEX_MISMATCH")

        # 规则 2：查询策略只有知识库级配置一个权威来源。
        if any(scope.retrieval_settings is None for scope in self.knowledge_scopes):
            raise ValueError("VALIDATION")

        # 规则 3：scopes 与 indexes 三元组对应且内部去重
        scopes = {
            (item.knowledge_id, item.index_version, item.manifest_revision)
            for item in self.knowledge_scopes
        }
        indexes = {
            (item.knowledge_id, item.index_version, item.manifest_revision)
            for item in self.index_snapshots
        }
        if len(scopes) != len(self.knowledge_scopes) or len(indexes) != len(self.index_snapshots):
            raise ValueError("INDEX_MISMATCH")
        if scopes != indexes or len({item.knowledge_id for item in self.knowledge_scopes}) != len(
            scopes
        ):
            raise ValueError("INDEX_MISMATCH")
        return self


# ============================================================================
# 清单分页：索引成员的分页查询
# ============================================================================


class ManifestPageRequest(VersionedRequest):
    """snapshotId 位于路径；游标绑定同一快照和 manifestRevision。

    首次请求 cursor 传 null，后续翻页使用上一页返回的 nextCursor。
    """

    knowledge_id: BusinessId
    index_version: BusinessId
    manifest_revision: Revision

    # 分页游标：null 表示首页
    cursor: Ticket | None
    page_size: Annotated[int, Field(ge=1, le=1000)]


class ManifestMember(RagModel):
    """每个成员绑定已发布的文档版本与 generation。"""

    document_id: BusinessId
    document_version_id: BusinessId
    generation_id: BusinessId


class ManifestPageResponse(RagModel):
    """nextCursor 为 null 才是完整分页结束；空成员不得变成无过滤查询。"""

    scope_snapshot_id: BusinessId
    knowledge_id: BusinessId
    index_version: BusinessId
    manifest_revision: Revision

    # 当前页成员列表
    members: Annotated[list[ManifestMember], Field(max_length=1000)]

    # 下一页游标：null 表示最后一页
    next_cursor: Ticket | None

    # 响应过期时间，过期后游标失效
    expires_at: AwareDatetime


# ============================================================================
# 候选复核：Java 签发访问票据前的校验
# ============================================================================


class ValidateCandidatesRequest(VersionedRequest):
    """仅复核当前请求快照的候选；空集合用于签发无命中的受限票据。

    purpose 说明复核目的：
      MODEL_CONTEXT  — 生成模型上下文，需要可读文本
      COMPLETION     — 补全，仅验证有效性
      CHUNK_PREVIEW  — 管理后台预览，允许更宽松的校验
    """

    candidates: Annotated[list[SourceRef], Field(max_length=500)]
    purpose: Literal["MODEL_CONTEXT", "COMPLETION", "CHUNK_PREVIEW"]


class CandidateValidationResponse(RagModel):
    """票据绑定允许集合及期限，不能凭此扩大原快照的范围。

    allowed_candidates 是 candidates 的子集，仅包含通过复核的引用。
    validation_ticket 是 Java 后续访问 OSS 的凭证。
    """

    scope_snapshot_id: BusinessId
    allowed_candidates: Annotated[list[SourceRef], Field(max_length=500)]
    validation_ticket: Ticket
    expires_at: AwareDatetime


# ============================================================================
# 检索测试：生产检索链的诊断请求与响应
# ============================================================================


class RetrievalTestRequest(VersionedRequest):
    """Java 先完成权限和预算检查，Python 执行相同的生产检索链。"""

    query: Annotated[str, Field(min_length=1, max_length=20_000)]
    rag: RagExecutionScope


class RetrievedChunk(RagModel):
    """阶段分数为 null 表示未执行该阶段；Java 仍需做候选复核。"""

    # 来源定位与文本
    location: SourceLocation
    text: Annotated[str, Field(min_length=1, max_length=40_000)]

    # 各阶段分数（开发模式下 Rerank 分数为 None）
    dense_score: Annotated[float, Field(ge=-1e308, le=1e308)] | None
    sparse_score: Annotated[float, Field(ge=-1e308, le=1e308)] | None
    rrf_score: Annotated[float, Field(ge=-1e308, le=1e308)] | None
    rerank_score: Annotated[float, Field(ge=-1e308, le=1e308)] | None


class RetrievalStageStats(RagModel):
    """只提供脱敏阶段耗时和数量，跳过阶段不能伪装为已完成。"""

    # 阶段标识
    stage: Literal["DENSE", "SPARSE", "RRF", "RERANK", "CONTEXT"]

    # 执行状态
    status: Literal["COMPLETED", "SKIPPED"]

    # 耗时（毫秒）和出入数量
    duration_ms: Count
    input_count: Count
    output_count: Count


class RetrievalTestResponse(RagModel):
    """返回受控诊断与完整来源，不返回向量库或供应商原始响应。"""

    request_id: Label
    scope_snapshot_id: BusinessId

    # 检索结果
    chunks: Annotated[list[RetrievedChunk], Field(max_length=100)]
    validation_ticket: Ticket

    # 用量与阶段统计
    execution_usage: Annotated[list[ExecutionUsageItem], Field(max_length=1000)]
    stages: Annotated[list[RetrievalStageStats], Field(max_length=6)]
