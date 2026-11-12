"""Java 控制面与 Python 执行面共享的聊天内部协议模型。

本模块定义了 Java → Python 的 HTTP/SSE 契约，按数据流向分为四组：
  - SSE 错误码 — Java-Python 稳定契约中的错误码枚举。
  - 契约基类 — ContractModel 统一 camelCase 序列化规则。
  - 请求模型 — 聊天输入、调用主体、模型配置、执行策略和完整请求体。
  - SSE 事件 — 增量文本、完成、失败和心跳事件的数据体。
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, HttpUrl
from pydantic.alias_generators import to_camel

from ai_service.contracts.mcp import McpToolGrant

# 错误码属于 Java-Python SSE 稳定契约；AUTHORIZATION 表示内部认证失败，
# PROVIDER_AUTHORIZATION 表示模型供应商拒绝凭证，变更时必须同步 Java 映射与契约测试。
type SseErrorCode = Literal[
    "VALIDATION",
    "AUTHORIZATION",
    "PROVIDER_AUTHORIZATION",
    "MODEL_UNAVAILABLE",
    "BUDGET_EXCEEDED",
    "TOOL_REJECTED",
    "TOOL_CREDENTIAL_MISSING",
    "TOOL_TIMEOUT",
    "RATE_LIMITED",
    "CANCELLED",
    "TIMEOUT",
    "INTERNAL",
]


# ============================================================================
# 契约基类
# ============================================================================


class ContractModel(BaseModel):
    """统一 Java 内部 HTTP 与 SSE 契约的字段命名规则。

    所有契约模型继承此类，自动将 Python snake_case 字段序列化为 Java camelCase。
    populate_by_name=True 允许同时接受两种命名风格的输入。
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


# ============================================================================
# 请求模型：Java → Python 的聊天调用参数
# ============================================================================


class ChatMessage(ContractModel):
    """单条已受 Java 会话权限约束的上下文消息。"""

    role: Literal["system", "user", "assistant"]
    content: str = Field(min_length=1, max_length=20_000)


class ChatInput(ContractModel):
    """本次模型调用的完整消息输入。"""

    messages: list[ChatMessage] = Field(min_length=1, max_length=50)


class ChatActor(ContractModel):
    """由 Java 签名下发的调用主体快照。

    roles 列表由 Java 在请求时从会话权限中提取，Python 不做鉴权。
    """

    user_id: str = Field(min_length=1, max_length=128)
    roles: list[str] = Field(default_factory=list, max_length=50)


class ModelProfile(ContractModel):
    """不含明文凭证的已发布模型配置快照。

    credential_key 是凭证别名，Python 通过内部注册表查找实际 API Key，
    不在消息中传递明文凭证。
    """

    # 配置标识
    id: int = Field(gt=0)
    revision: int = Field(gt=0)

    # 模型定位
    model_name: str = Field(min_length=1, max_length=255)
    provider_code: str = Field(min_length=1, max_length=50)

    # 连接参数
    api_host: HttpUrl
    api_mode: Literal["CHAT_COMPLETIONS", "RESPONSES"] = "CHAT_COMPLETIONS"
    credential_key: str = Field(pattern=r"^[A-Za-z0-9_.-]{1,100}$")

    # Token 限制
    max_input_tokens: int = Field(gt=0, le=2_000_000)
    max_output_tokens: int | None = Field(default=None, gt=0, le=2_000_000)


class ExecutionPolicy(ContractModel):
    """Java 在本次调用已核定的预算和超时限制。"""

    max_input_tokens: int = Field(gt=0)
    max_output_tokens: int = Field(gt=0, le=2_000_000)
    timeout_seconds: int = Field(gt=0, le=300)


class ChatExecutionPolicy(ExecutionPolicy):
    """普通聊天的可选工具多轮总预算；RAG 继续使用原有执行策略。"""

    max_total_tokens: int | None = Field(default=None, gt=0)


class InternalChatStreamRequest(ContractModel):
    """Java 调用 Python 流式聊天接口的完整请求契约。

    字段分为四层：
      追踪 — request_id / client_request_id / session_id 全链路标识
      主体 — actor 调用者快照（用户 + 角色）
      输入 — input 完整的上下文消息列表
      配置 — model_profile + execution_policy 模型与预算
    """

    # === 追踪标识 ===
    request_id: str = Field(min_length=6, max_length=128)
    client_request_id: str = Field(min_length=6, max_length=128)
    session_id: str = Field(min_length=1, max_length=64)

    # === 调用主体 ===
    actor: ChatActor

    # === 消息输入 ===
    input: ChatInput

    # === 模型与策略 ===
    model_profile: ModelProfile
    execution_policy: ChatExecutionPolicy
    tools: list[McpToolGrant] = Field(default_factory=list, max_length=20)


# ============================================================================
# 用量模型
# ============================================================================


class Usage(ContractModel):
    """归一化后的模型 Token 用量。"""

    input_tokens: int | None = Field(default=None, ge=0)
    output_tokens: int | None = Field(default=None, ge=0)
    total_tokens: int | None = Field(default=None, ge=0)


# ============================================================================
# SSE 事件数据体：Python → Java 的流式响应
# ============================================================================


class DeltaPayload(ContractModel):
    """SSE 增量文本事件的数据体。

    Delta 事件在流式生成过程中持续发送，客户端拼接所有 delta.text
    形成完整的生成内容。
    """

    text: str = Field(min_length=1)


class CompletedPayload(ContractModel):
    """SSE 成功终态事件的数据体。

    Completed 是流式生成的最后一个有效事件，content 为完整生成文本，
    usage 为实际 Token 用量（可能为 None 表示供应商未报告）。
    """

    request_id: str
    content: str
    usage: Usage | None = None


class FailedPayload(ContractModel):
    """SSE 失败终态事件的数据体，仅暴露稳定错误码。

    error_code 使用 SseErrorCode 枚举，不传递供应商原始错误信息，
    避免泄露内部实现细节。
    """

    error_code: SseErrorCode


class KeepalivePayload(ContractModel):
    """模型等待期间维持 Java 连接的非计费事件数据体。

    Keepalive 在模型响应较慢时发送，防止 HTTP 连接超时断开。
    不传递任何内容，不计入 Token 用量。
    """

    request_id: str
