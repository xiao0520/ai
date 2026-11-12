"""Java 控制面与 LangChain 聊天模型路由器之间的内部契约。"""

from __future__ import annotations

from decimal import Decimal
from typing import Literal

from pydantic import Field, field_validator, model_validator

from ai_service.contracts.chat import ContractModel

type ChatRouteReasonCode = Literal[
    "SINGLE_CANDIDATE", "VISION_REQUIRED", "TOOL_REQUIRED", "LONG_CONTEXT",
    "CODE_TASK", "REASONING_TASK", "DEFAULT_FALLBACK",
]


class ChatRouteFeatures(ContractModel):
    """由 Java 从已授权请求中提取的路由特征。"""

    rag_enabled: bool = False
    has_image: bool = False
    tool_required: bool = False
    estimated_input_tokens: int = Field(ge=1, le=2_000_000)


class ChatRouteCandidate(ContractModel):
    """Java 已完成权限和配置过滤的单个精确模型修订。"""

    model_id: int = Field(gt=0)
    revision: int = Field(gt=0)
    capabilities: list[str] = Field(default_factory=list, max_length=20)
    max_input_tokens: int = Field(gt=0, le=2_000_000)
    input_cost_per_million: Decimal | None = Field(default=None, ge=0)
    output_cost_per_million: Decimal | None = Field(default=None, ge=0)
    quality_tier: int = Field(default=3, ge=1, le=5)
    latency_tier: int = Field(default=3, ge=1, le=5)
    priority: int = Field(default=0, ge=0, le=10_000)

    @field_validator("capabilities")
    @classmethod
    def normalize_capabilities(cls, value: list[str]) -> list[str]:
        """能力标签统一为大写并去重，避免路由结果受录入格式影响。"""
        return list(dict.fromkeys(item.strip().upper() for item in value if item.strip()))


class ChatRoutePolicy(ContractModel):
    """由 Java 发布的确定性路由策略。"""

    revision: int = Field(gt=0)
    strategy: Literal["RULES"] = "RULES"
    fallback_model_id: int = Field(gt=0)


class InternalChatRouteRequest(ContractModel):
    """AUTO 两阶段流程的第一阶段请求，不含任何供应商凭证。"""

    request_id: str = Field(min_length=6, max_length=128)
    candidate_snapshot_hash: str = Field(pattern=r"^[a-f0-9]{64}$")
    question: str = Field(min_length=1, max_length=20_000)
    features: ChatRouteFeatures
    candidates: list[ChatRouteCandidate] = Field(min_length=1, max_length=100)
    policy: ChatRoutePolicy

    @model_validator(mode="after")
    def validate_candidate_snapshot(self) -> InternalChatRouteRequest:
        """候选模型修订不得重复，且兜底模型必须属于 Java 已授权集合。"""
        keys = {(candidate.model_id, candidate.revision) for candidate in self.candidates}
        if len(keys) != len(self.candidates):
            raise ValueError("路由候选模型及修订不得重复")
        if self.policy.fallback_model_id not in {candidate.model_id for candidate in self.candidates}:
            raise ValueError("兜底模型必须属于路由候选")
        return self


class InternalChatRouteResponse(ContractModel):
    """确定性路由结果；Java 会再次校验模型、修订和候选摘要。"""

    request_id: str
    candidate_snapshot_hash: str
    selected_model_id: int
    selected_revision: int
    reason_code: ChatRouteReasonCode
    policy_revision: int
