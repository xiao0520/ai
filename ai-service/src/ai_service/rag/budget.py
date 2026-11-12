"""知识库模型调用预算核验；额度结算仍由 Java 控制面负责。

职责：
  - measured_tokens — 汇总已发生的 TokenMeasurement 用量，忽略 UNKNOWN 留待 Java 对账。
  - require_budget — 在模型调用前核验 attempt 上限，超限时阻止后续付费调用和向量发布。
  - 核验同时计入 planned_tokens（计划用量），为即将发起的调用预留预算。

关键约束：
  - 仅统计 TokenMeasurement（已知 Token 用量），UNKNOWN 留给 Java 后续对账。
  - 预算来自命令快照的 execution_budget.max_tokens，由 Java 在下发命令时设定。
  - 调用前后均需核验（require_budget），避免已发生用量超过预算。
"""

from collections.abc import Iterable

from ai_service.contracts.knowledge_kafka import KnowledgeJobCommand
from ai_service.contracts.rag_types import ExecutionUsageItem, TokenMeasurement
from ai_service.rag.models import KnowledgeProcessingError

# ============================================================================
# 预算核验函数
# ============================================================================


def measured_tokens(items: Iterable[ExecutionUsageItem]) -> int:
    """只汇总实际或已验证估算的 Token；UNKNOWN 留给 Java 后续对账。"""
    total = 0
    for item in items:
        measurement = item.measurement
        if isinstance(measurement, TokenMeasurement):
            total += measurement.input_tokens + measurement.output_tokens
    return total


def require_budget(
    command: KnowledgeJobCommand,
    items: Iterable[ExecutionUsageItem],
    *,
    planned_tokens: int = 0,
) -> None:
    """调用前后检查 attempt 上限，超限时阻止后续付费调用和向量发布。

    planned_tokens 用于预防性预算核算：在模型调用之前，将预期 Token 数纳入
    已消耗计算；并发配额的原子预占仍由 Java 控制面负责。"""
    usage_items = tuple(items)
    budget = command.execution_budget
    if budget is None:
        raise KnowledgeProcessingError("INDEX_MISMATCH", usage_items=usage_items)
    consumed_tokens = measured_tokens(usage_items)
    planned_tokens = max(planned_tokens, 0)
    if consumed_tokens + planned_tokens > budget.max_tokens:
        raise KnowledgeProcessingError(
            "BUDGET_EXCEEDED",
            usage_items=usage_items,
            measured_tokens=consumed_tokens,
            planned_tokens=planned_tokens,
            max_tokens=budget.max_tokens,
        )