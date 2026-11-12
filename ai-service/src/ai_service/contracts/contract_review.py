"""合同审查内部请求、SSE 事件和结构化结果契约。"""

from __future__ import annotations

from typing import Annotated, Literal, Self

from pydantic import ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel

from ai_service.contracts.chat import ContractModel, ExecutionPolicy, ModelProfile
from ai_service.contracts.rag_types import ExecutionUsageItem, VisionProfile
from ai_service.contracts.retrieval import RagExecutionScope, SourceLocation

ContractReviewErrorCode = Literal[
    "VALIDATION",
    "MODEL_UNAVAILABLE",
    "RATE_LIMITED",
    "TIMEOUT",
    "INTERNAL",
    "CONTRACT_FILE_UNSUPPORTED",
    "CONTRACT_PARSE_FAILED",
    "CONTRACT_TEXT_EMPTY",
    "KNOWLEDGE_ACCESS_DENIED",
    "KNOWLEDGE_NOT_READY",
    "BUDGET_EXCEEDED",
    "CONTRACT_OCR_REQUIRED",
    "CONTRACT_OCR_FAILED",
]


class ContractReviewModel(ContractModel):
    """合同审查协议拒绝未知字段，避免 Java/Python 契约静默漂移。"""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class ContractReviewExecutionPolicy(ExecutionPolicy):
    """Java 账户已批准的整次合同审查 Token 硬上限。"""

    max_total_tokens: int = Field(gt=0, le=2_147_483_647)


class ContractReviewExecutionRequest(ContractReviewModel):
    """Java 已完成权限裁定后的合同审查执行快照。"""

    schema_version: Literal["1.0"]
    request_id: Annotated[str, Field(min_length=6, max_length=128)]
    task_id: Annotated[str, Field(pattern=r"^[1-9][0-9]{0,18}$")]
    user_id: Annotated[str, Field(pattern=r"^[1-9][0-9]{0,18}$")]
    file_name: Annotated[str, Field(min_length=1, max_length=255)]
    media_type: Literal[
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "image/png",
        "image/jpeg",
    ]
    contract_type: Literal["PURCHASE", "SALES", "SERVICE", "OTHER"]
    party_position: Literal["PARTY_A", "PARTY_B", "NEUTRAL"]
    # 仅来自 Java 读取的 Agent 配置，不接受浏览器直接指定审查提示词。
    system_prompt: str | None = Field(default=None, max_length=20_000)
    model_profile: ModelProfile
    execution_policy: ContractReviewExecutionPolicy
    rag: RagExecutionScope | None = None
    operation: Literal["REVIEW", "EXTRACT"] = "REVIEW"
    vision_profile: VisionProfile | None = None
    vision_max_output_tokens: int = Field(default=2048, ge=1, le=32768)

    @model_validator(mode="after")
    def validate_operation(self) -> Self:
        """独立提取不依赖知识库；图片只进入提取模式，存量审查请求保持兼容。"""
        if self.operation == "REVIEW" and (
            self.rag is None or self.media_type.startswith("image/")
        ):
            raise ValueError("VALIDATION")
        if self.operation == "EXTRACT" and self.rag is not None:
            raise ValueError("VALIDATION")
        return self


class ContractInformation(ContractReviewModel):
    """第一版固定提取的合同关键信息。"""

    title: str | None = Field(default=None, max_length=500)
    party_a: str | None = Field(default=None, max_length=1000)
    party_b: str | None = Field(default=None, max_length=1000)
    other_parties: list[str] = Field(default_factory=list, max_length=20)
    amount: str | None = Field(default=None, max_length=100)
    currency: str | None = Field(default=None, max_length=32)
    effective_date: str | None = Field(default=None, max_length=100)
    start_date: str | None = Field(default=None, max_length=100)
    end_date: str | None = Field(default=None, max_length=100)
    term: str | None = Field(default=None, max_length=2000)
    payment_terms: str | None = Field(default=None, max_length=4000)
    delivery_and_acceptance: str | None = Field(default=None, max_length=4000)
    breach_liability: str | None = Field(default=None, max_length=4000)
    termination_terms: str | None = Field(default=None, max_length=4000)
    dispute_resolution: str | None = Field(default=None, max_length=4000)


class ContractLocation(ContractReviewModel):
    """风险原文在上传合同中的稳定位置。"""

    page_number: int | None = Field(default=None, ge=1)
    clause_number: str | None = Field(default=None, max_length=128)
    block_index: int = Field(ge=0)
    start_offset: int = Field(ge=0)
    end_offset: int = Field(ge=1)

    @model_validator(mode="after")
    def validate_range(self) -> Self:
        """合同偏移区间采用左闭右开，拒绝空区间和倒置区间。"""
        if self.end_offset <= self.start_offset:
            raise ValueError("VALIDATION")
        return self


class KnowledgeEvidence(ContractReviewModel):
    """经授权检索链返回并由风险项实际引用的知识证据。"""

    evidence_id: Annotated[str, Field(min_length=1, max_length=256)]
    source_title: Annotated[str, Field(min_length=1, max_length=512)]
    quote: Annotated[str, Field(min_length=1, max_length=40000)]
    location: SourceLocation


class ContractFinding(ContractReviewModel):
    """一条可回溯到合同原文的风险结果。"""

    finding_id: Annotated[str, Field(pattern=r"^F-[0-9]{3}$")]
    category: Annotated[str, Field(pattern=r"^[A-Z][A-Z0-9_]{1,31}$")]
    severity: Literal["HIGH", "MEDIUM", "LOW"]
    title: Annotated[str, Field(min_length=1, max_length=255)]
    description: Annotated[str, Field(min_length=1, max_length=4000)]
    quote: Annotated[str, Field(min_length=1, max_length=2000)]
    location: ContractLocation
    suggestion: Annotated[str, Field(min_length=1, max_length=4000)]
    basis: Annotated[str, Field(min_length=1, max_length=4000)]
    evidences: list[KnowledgeEvidence] = Field(default_factory=list, max_length=20)


class ContractRiskSummary(ContractReviewModel):
    """按风险等级汇总数量。"""

    high: int = Field(ge=0)
    medium: int = Field(ge=0)
    low: int = Field(ge=0)


class ContractReviewResult(ContractReviewModel):
    """Python 交给 Java 持久化的完整合同审查结果。"""

    schema_version: Literal["1.0"] = "1.0"
    task_id: Annotated[str, Field(pattern=r"^[1-9][0-9]{0,18}$")]
    information: ContractInformation
    findings: list[ContractFinding] = Field(max_length=100)
    summary: ContractRiskSummary
    warnings: list[str] = Field(default_factory=list, max_length=100)
    engine_version: Literal["contract-review-v1"] = "contract-review-v1"

    @model_validator(mode="after")
    def validate_summary(self) -> Self:
        """汇总从明细计算，拒绝重复编号和互相矛盾的结果。"""
        expected = ContractRiskSummary(
            high=sum(item.severity == "HIGH" for item in self.findings),
            medium=sum(item.severity == "MEDIUM" for item in self.findings),
            low=sum(item.severity == "LOW" for item in self.findings),
        )
        if self.summary != expected or len({item.finding_id for item in self.findings}) != len(
            self.findings
        ):
            raise ValueError("VALIDATION")
        return self


class ContractReviewProgressPayload(ContractReviewModel):
    """合同审查阶段进度，仅用于当前 SSE 展示。"""

    request_id: str
    stage: Literal["PARSING", "EXTRACTING", "RETRIEVING", "REVIEWING", "NORMALIZING"]
    progress: int = Field(ge=0, le=100)
    message: str = Field(min_length=1, max_length=100)


class ContractReviewCompletedPayload(ContractReviewModel):
    """合同审查成功终态。"""

    request_id: str
    result: ContractReviewResult
    execution_usage: list[ExecutionUsageItem] = Field(default_factory=list, max_length=1000)


class ContractReviewFailedPayload(ContractReviewModel):
    """合同审查失败终态，只暴露稳定错误码。"""

    request_id: str
    error_code: ContractReviewErrorCode
    execution_usage: list[ExecutionUsageItem] = Field(default_factory=list, max_length=1000)


class ContractReviewUsagePayload(ContractReviewModel):
    """Java 在阶段间保存累计计量，终态断连时仍能结算此前已发生的调用。"""

    request_id: str
    execution_usage: list[ExecutionUsageItem] = Field(max_length=1000)


class ContractInformationDraft(ContractInformation):
    """模型提取阶段使用的结构化输出。"""


class ContractFindingDraft(ContractReviewModel):
    """模型审查草稿；位置与证据在归一化节点重新绑定。"""

    category: Annotated[str, Field(pattern=r"^[A-Z][A-Z0-9_]{1,31}$")]
    severity: Literal["HIGH", "MEDIUM", "LOW"]
    title: Annotated[str, Field(min_length=1, max_length=255)]
    description: Annotated[str, Field(min_length=1, max_length=4000)]
    quote: Annotated[str, Field(min_length=1, max_length=2000)]
    suggestion: Annotated[str, Field(min_length=1, max_length=4000)]
    basis: Annotated[str, Field(min_length=1, max_length=4000)]
    evidence_ids: list[str] = Field(default_factory=list, max_length=20)


class ContractReviewDraft(ContractReviewModel):
    """模型风险审查阶段的结构化输出。"""

    findings: list[ContractFindingDraft] = Field(default_factory=list, max_length=100)
