"""RAG 聊天、流式事件、引用与用量数据模型。

本模块在普通聊天协议（chat.py）基础上扩展 RAG 能力：
  - 请求 — RagChatStreamRequest 增加 RagExecutionScope 检索范围。
  - 引用 — Citation 绑定不可变 SourceLocation，供候选复核后使用。
  - 事件 — RagCitationPayload / RagCompletedPayload / RagFailedPayload
           扩展 SSE 事件体，增加引用、答案状态和执行用量。
"""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from ai_service.contracts.chat import (
    ChatActor,
    ChatMessage,
    ExecutionPolicy,
    ModelProfile,
    Usage,
)
from ai_service.contracts.mcp import McpToolGrant
from ai_service.contracts.rag_types import ErrorCode, ExecutionUsageItem, Label, RagModel, Ticket
from ai_service.contracts.retrieval import RagExecutionScope, SourceLocation

# ============================================================================
# 请求模型：RAG 聊天的严格校验变体
# ============================================================================


class RagChatActor(ChatActor, RagModel):
    """RAG 调用主体使用严格字段，原普通聊天模型保持兼容。

    通过多重继承，在 ChatActor 字段基础上叠加 RagModel 的严格校验策略
    （extra="forbid", strict=True），防止 Java 误传未知字段。
    """


class RagChatMessage(ChatMessage, RagModel):
    """RAG 上下文消息拒绝未知字段与隐式类型转换。

    同样通过多重继承叠加严格校验，确保 RAG 场景下消息结构完全受控。
    """


class RagChatInput(RagModel):
    """严格校验每条上下文消息，不改变原聊天输入协议。"""

    messages: Annotated[list[RagChatMessage], Field(min_length=1, max_length=50)]


class RagGenerationProfile(ModelProfile, RagModel):
    """复用既有生成模型字段和默认值，仅收紧 RAG 边界的结构校验。"""


class RagExecutionPolicy(ExecutionPolicy, RagModel):
    """RAG 生成预算由 Java 核定；工具多轮执行另带总 Token 上限。"""

    max_total_tokens: int | None = Field(default=None, gt=0)


class RagGenerationUsage(Usage, RagModel):
    """完成事件保留原聊天用量字段；未知用量仍可为 null。"""


class RagChatStreamRequest(RagModel):
    """启用 RAG 时的请求变体；普通聊天继续使用原 InternalChatStreamRequest。

    与 InternalChatStreamRequest 的区别：
      - actor/input/model_profile/execution_policy 使用 Rag 严格变体
      - 新增 rag 字段携带检索范围与执行配置
      - 最后一条消息必须是 user（由 validate_current_question 校验）
    """

    # === 追踪标识 ===
    request_id: Annotated[str, Field(min_length=6, max_length=128)]
    client_request_id: Annotated[str, Field(min_length=6, max_length=128)]
    session_id: Annotated[str, Field(min_length=1, max_length=64)]

    # === 调用主体与输入 ===
    actor: RagChatActor
    input: RagChatInput

    # === 模型与执行策略 ===
    model_profile: RagGenerationProfile
    execution_policy: RagExecutionPolicy

    # === RAG 检索范围 ===
    rag: RagExecutionScope
    tools: Annotated[list[McpToolGrant], Field(max_length=20)] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_current_question(self) -> Self:
        """最后一条必须是当前用户问题，避免检索与生成意图错位。

        RAG 检索以最后一条 user 消息为查询，若最后一条是 assistant
        或 system，则检索目标不明确，应拒绝请求。
        """
        if self.input.messages[-1].role != "user":
            raise ValueError("VALIDATION")
        return self


# ============================================================================
# 引用模型：候选复核后的最终引用绑定
# ============================================================================


class Citation(RagModel):
    """当前回答内的引用标识绑定不可变来源，不能指向更新后的文件。

    citation_id 在本条回答内唯一，location 指向已通过候选复核的不可变来源。
    excerpt 是引用文本的摘录片段（最多 2000 字符），用于前端展示。
    """

    # 回答内唯一引用 ID
    citation_id: Label

    # 不可变来源定位
    location: SourceLocation

    # 引用摘录文本
    excerpt: Annotated[str, Field(min_length=1, max_length=2000)]


# ============================================================================
# SSE 事件模型：RAG 聊天的流式事件扩展
# ============================================================================


class RagCitationPayload(RagModel):
    """citation 事件仅提供候选映射，不表示回答实际使用了该来源。

    此事件在流式生成过程中发送，客户端可用于构建引用预览，
    但最终生效的引用以 RagCompletedPayload.citations 为准。
    """

    request_id: Label
    citation: Citation


class RagCompletedPayload(RagModel):
    """完成引用是已复核的实际使用子集；usage 兼容原聊天但不可重复计费。

    字段分为四组：
      生成结果 — content 与 usage（兼容原聊天协议）
      答案状态 — 是否有证据支持的判定
      引用 — 已复核的实际使用引用集合
      凭证与用量 — 验证票据和执行用量（供 Java 结算）
    """

    # === 生成结果 ===
    request_id: Label
    content: Annotated[str, Field(max_length=200_000)]
    usage: RagGenerationUsage | None

    # === 答案状态 ===
    # ANSWERED          — 有证据支撑的完整回答
    # PARTIAL           — 部分有证据，部分无
    # NO_EVIDENCE       — 未使用知识库证据（可有实际 MCP 工具结果）
    # NEEDS_CLARIFICATION — 问题不明确，需要用户补充
    answer_status: Literal["ANSWERED", "PARTIAL", "NO_EVIDENCE", "NEEDS_CLARIFICATION"]

    # === 引用 ===
    citations: Annotated[list[Citation], Field(max_length=100)]

    # === 凭证与用量 ===
    validation_ticket: Ticket | None
    execution_usage: Annotated[list[ExecutionUsageItem], Field(max_length=1000)]

    @model_validator(mode="after")
    def validate_citations(self) -> Self:
        """有依据回答必须携带引用及复核票据，无依据状态不得附加伪引用。

        校验规则：
          1. ANSWERED / PARTIAL 必须有 citations，NO_EVIDENCE / NEEDS_CLARIFICATION 不得有。
          2. 有引用时必须有 validation_ticket。
          3. citation_id 在本次回答内必须唯一。
        """
        has_evidence = self.answer_status in ("ANSWERED", "PARTIAL")

        # 规则 1：有证据 ↔ 有引用
        if has_evidence != bool(self.citations):
            raise ValueError("CITATION_INVALID")

        # 规则 2：有引用 → 有票据
        if self.citations and self.validation_ticket is None:
            raise ValueError("CITATION_INVALID")

        # 规则 3：citation_id 唯一
        if len({item.citation_id for item in self.citations}) != len(self.citations):
            raise ValueError("CITATION_INVALID")
        return self


class RagFailedPayload(RagModel):
    """RAG 失败终态包含已发生用量，避免异常路径漏记调用成本。

    与普通聊天 FailedPayload 的区别：
      - error_code 使用 RAG 统一错误码（ErrorCode），范围更广
      - 增加 execution_usage 上报已发生的模型调用成本
    """

    request_id: Label
    error_code: ErrorCode
    execution_usage: Annotated[list[ExecutionUsageItem], Field(max_length=1000)]
