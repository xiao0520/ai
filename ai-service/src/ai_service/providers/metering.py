"""单次执行内的模型用量和硬预算；账户预占与扣费仍由 Java 完成。"""

from __future__ import annotations

from collections.abc import Callable
from typing import Literal
from uuid import NAMESPACE_URL, uuid5

import tiktoken

from ai_service.contracts.chat import Usage
from ai_service.contracts.rag_types import ExecutionUsageItem, ProfileRef, TokenMeasurement
from ai_service.providers.errors import ModelProviderBudgetError

type UsageReporter = Callable[[Usage, Literal["ACTUAL", "ESTIMATED"]], None]


def estimate_tokens(text: str) -> int:
    """按固定 BPE 编码估算文本，不把本地估算标记为供应商实际值。"""
    return len(tiktoken.get_encoding("cl100k_base").encode_ordinary(text))


class ExecutionUsageTracker:
    """按调用标识保留已发生用量，并在并发调用前原子占用本次执行预算。"""

    def __init__(self, request_id: str, max_tokens: int) -> None:
        self.request_id = request_id
        self.max_tokens = max_tokens
        self._items: dict[str, ExecutionUsageItem] = {}
        self._output_reservations: dict[str, int] = {}
        self._sequence = 0
        self._finished: set[str] = set()

    @property
    def items(self) -> list[ExecutionUsageItem]:
        """返回累计快照，重复上报由 Java 按 callId 幂等处理。"""
        return list(self._items.values())

    @property
    def remaining_tokens(self) -> int:
        """可供后续调用使用的余额，包含正在执行调用的输出预留。"""
        consumed = sum(
            item.measurement.input_tokens + item.measurement.output_tokens
            for item in self._items.values()
            if isinstance(item.measurement, TokenMeasurement)
        )
        return max(0, self.max_tokens - consumed - sum(self._output_reservations.values()))

    def begin(
        self,
        stage: Literal["CHAT", "EMBEDDING", "RERANK", "VISION"],
        profile: ProfileRef,
        input_tokens: int,
        output_tokens: int = 0,
    ) -> str:
        """调用前校验余额；中断且无回执时保留已提交输入的估算用量。"""
        if input_tokens + output_tokens > self.remaining_tokens:
            raise ModelProviderBudgetError("The execution token budget is exhausted")
        self._sequence += 1
        call_id = uuid5(NAMESPACE_URL, f"{self.request_id}:{stage}:{self._sequence}").hex
        self._items[call_id] = ExecutionUsageItem.model_validate(
            {
                "stage": stage,
                "callId": call_id,
                "attemptId": self.request_id,
                "profile": profile,
                "measurement": {
                    "unit": "TOKENS",
                    "quality": "ESTIMATED",
                    "inputTokens": input_tokens,
                    "outputTokens": 0,
                },
            }
        )
        self._output_reservations[call_id] = output_tokens
        return call_id

    def finish(self, call_id: str, usage: Usage, quality: Literal["ACTUAL", "ESTIMATED"]) -> None:
        """供应商实际回执覆盖估算，调用完成后释放其输出预留。"""
        item = self._items[call_id]
        measurement = TokenMeasurement.model_validate(
            {
                "unit": "TOKENS",
                "quality": quality,
                "inputTokens": usage.input_tokens or 0,
                "outputTokens": usage.output_tokens or 0,
            }
        )
        self._items[call_id] = item.model_copy(update={"measurement": measurement})
        self._finished.add(call_id)
        self._output_reservations.pop(call_id, None)
        if (
            sum(
                entry.measurement.input_tokens + entry.measurement.output_tokens
                for entry in self._items.values()
                if isinstance(entry.measurement, TokenMeasurement)
            )
            > self.max_tokens
        ):
            raise ModelProviderBudgetError("The provider usage exceeded the execution budget")

    def reject(self, call_id: str) -> None:
        """明确未执行的认证、配置、参数或限流拒绝不计消耗。"""
        if call_id in self._finished:
            return
        self._items.pop(call_id, None)
        self._output_reservations.pop(call_id, None)
