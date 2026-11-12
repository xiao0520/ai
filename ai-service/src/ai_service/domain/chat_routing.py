"""基于 LangChain LCEL 的受限聊天模型路由。"""

from __future__ import annotations

from decimal import Decimal
from typing import Any, TypedDict, cast

from langchain_core.runnables import Runnable, RunnableBranch, RunnableLambda

from ai_service.contracts.chat_routing import (
    ChatRouteCandidate,
    ChatRouteReasonCode,
    InternalChatRouteRequest,
    InternalChatRouteResponse,
)


class _RouteState(TypedDict):
    """保存 Java 授权候选及本次规则匹配结果，只在路由链内部传递。"""

    request: InternalChatRouteRequest
    candidates: list[ChatRouteCandidate]
    required_capability: str | None
    reason_code: ChatRouteReasonCode


# 问题关键词仅用于选择候选能力，不参与 Java 权限或预算判断。
_CODE_MARKERS = ("代码", "编程", "报错", "异常", "sql", "python", "java", "vue", "typescript")
_REASONING_MARKERS = ("分析", "推理", "论证", "规划", "架构", "比较", "为什么", "reason")


class AuthorizedChatModelRouter:
    """只能在 Java 候选快照内选择模型的确定性 LangChain Runnable。"""

    def __init__(self) -> None:
        prepare = RunnableLambda(self._prepare)
        branches = RunnableBranch(
            (lambda state: len(state["candidates"]) == 1, RunnableLambda(self._single)),
            (lambda state: state["required_capability"] is not None, RunnableLambda(self._capability)),
            RunnableLambda(self._fallback),
        )
        self._chain = cast(
            Runnable[InternalChatRouteRequest, InternalChatRouteResponse],
            prepare | branches,
        )

    async def route(self, request: InternalChatRouteRequest) -> InternalChatRouteResponse:
        """异步执行 LCEL 路由链，方便 FastAPI 与后续策略扩展复用。"""
        return await self._chain.ainvoke(request)

    def _prepare(self, request: InternalChatRouteRequest) -> _RouteState:
        capability, reason = self._required_capability(request)
        return {
            "request": request,
            "candidates": list(request.candidates),
            "required_capability": capability,
            "reason_code": reason,
        }

    def _single(self, state: _RouteState) -> InternalChatRouteResponse:
        return self._response(state["request"], state["candidates"][0], "SINGLE_CANDIDATE")

    def _capability(self, state: _RouteState) -> InternalChatRouteResponse:
        capability = state["required_capability"]
        matches = [
            candidate
            for candidate in state["candidates"]
            if capability in candidate.capabilities
        ]
        if not matches:
            return self._fallback(state)
        return self._response(
            state["request"], self._best(matches), state["reason_code"]
        )

    def _fallback(self, state: _RouteState) -> InternalChatRouteResponse:
        request = state["request"]
        candidate = next(
            item
            for item in state["candidates"]
            if item.model_id == request.policy.fallback_model_id
        )
        return self._response(request, candidate, "DEFAULT_FALLBACK")

    def _required_capability(
        self, request: InternalChatRouteRequest
    ) -> tuple[str | None, ChatRouteReasonCode]:
        """按视觉、工具、长上下文、代码和推理顺序提取首个必需能力。"""
        features = request.features
        if features.has_image:
            return "VISION", "VISION_REQUIRED"
        if features.tool_required:
            return "TOOL", "TOOL_REQUIRED"
        if features.estimated_input_tokens >= 8_192:
            return "LONG_CONTEXT", "LONG_CONTEXT"
        question = request.question.casefold()
        if any(marker in question for marker in _CODE_MARKERS):
            return "CODE", "CODE_TASK"
        if any(marker in question for marker in _REASONING_MARKERS):
            return "REASONING", "REASONING_TASK"
        return None, "DEFAULT_FALLBACK"

    def _best(self, candidates: list[ChatRouteCandidate]) -> ChatRouteCandidate:
        return min(candidates, key=self._rank_key)

    def _rank_key(self, candidate: ChatRouteCandidate) -> tuple[Any, ...]:
        """优先级和质量降序、成本与延迟升序，最终用模型 ID 稳定破同分。"""
        unknown_cost = Decimal(999999999)
        input_cost = (
            unknown_cost
            if candidate.input_cost_per_million is None
            else candidate.input_cost_per_million
        )
        output_cost = (
            unknown_cost
            if candidate.output_cost_per_million is None
            else candidate.output_cost_per_million
        )
        cost = input_cost + output_cost
        return (
            -candidate.priority,
            -candidate.quality_tier,
            cost,
            candidate.latency_tier,
            candidate.model_id,
        )

    def _response(
        self,
        request: InternalChatRouteRequest,
        candidate: ChatRouteCandidate,
        reason_code: ChatRouteReasonCode,
    ) -> InternalChatRouteResponse:
        return InternalChatRouteResponse(
            request_id=request.request_id,
            candidate_snapshot_hash=request.candidate_snapshot_hash,
            selected_model_id=candidate.model_id,
            selected_revision=candidate.revision,
            reason_code=reason_code,
            policy_revision=request.policy.revision,
        )
