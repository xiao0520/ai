"""Rerank 模型 Provider 的通用边界和动态适配器注册表。

职责：
  - RerankProvider 协议 — 重排的统一入口，按 Profile 选择具体适配器。
  - RerankProviderFactory — 生命周期级注册表，按 (provider_code, adapter_code) 路由。
  - 数据类 — RerankDocument/RerankScore/RerankBatch，强类型重排输入输出。

路由规则：
  - 优先使用供应商专用实现（精确匹配 provider_code + adapter_code）。
  - 回退到通配符 "*" 提供的通用适配器。
  - 重复注册或未注册的组合拒绝启动。
  - provider_code 归一化为小写、adapter_code 归一化为大写。
"""

from __future__ import annotations

from dataclasses import dataclass
from types import MappingProxyType
from typing import Literal, Protocol

from ai_service.contracts.rag_types import RerankProfile
from ai_service.providers.errors import ModelConfigurationError

# ============================================================================
# 数据结构
# ============================================================================


@dataclass(frozen=True, slots=True)
class RerankDocument:
    """一次受控重排输入，标识只用于恢复稳定片段顺序。"""

    input_id: str
    text: str
    modality: Literal["TEXT", "IMAGE"]


@dataclass(frozen=True, slots=True)
class RerankScore:
    """单个候选在同一 Profile 分组内的相关性分数。"""

    input_id: str
    score: float


@dataclass(frozen=True, slots=True)
class RerankBatch:
    """一次重排调用的完整结果及可计量文档对数量。"""

    scores: tuple[RerankScore, ...]
    request_count: int
    document_pair_count: int
    input_tokens: int | None = None
    measurement_quality: Literal["ACTUAL", "ESTIMATED"] = "ACTUAL"


# ============================================================================
# Rerank Provider 协议
# ============================================================================


class RerankProvider(Protocol):
    """执行一个 Java 已发布的精确 Rerank Profile。"""

    async def rerank(
        self,
        profile: RerankProfile,
        query: str,
        documents: tuple[RerankDocument, ...],
    ) -> RerankBatch:
        """返回全部候选的组内分数；缺失、重复或额外结果必须失败。"""
        ...


# ============================================================================
# Rerank Provider 工厂注册表
# ============================================================================


class RerankProviderFactory:
    """按模型管理下发的供应商和 adapterCode 动态选择重排适配器。"""

    def __init__(self, strategies: dict[tuple[str, str], RerankProvider]) -> None:
        normalized: dict[tuple[str, str], RerankProvider] = {}
        for (provider_code, adapter_code), provider in strategies.items():
            key = self._key(provider_code, adapter_code)
            if key in normalized:
                raise ValueError("Duplicate rerank provider and adapter code")
            normalized[key] = provider
        if not normalized:
            raise ValueError("At least one rerank provider is required")
        self._strategies = MappingProxyType(normalized)

    def provider_for(self, profile: RerankProfile) -> RerankProvider:
        """优先使用供应商专用实现，再按协议选择通用适配器。"""
        exact = self._key(profile.provider_code, profile.adapter_code)
        provider = self._strategies.get(exact)
        if provider is None:
            provider = self._strategies.get(self._key("*", profile.adapter_code))
        if provider is None:
            raise ModelConfigurationError("The rerank provider adapter is not registered")
        return provider

    @staticmethod
    def _key(provider_code: str, adapter_code: str) -> tuple[str, str]:
        provider = provider_code.strip().lower()
        adapter = adapter_code.strip().upper()
        if not provider or not adapter:
            raise ModelConfigurationError("The rerank provider routing key is empty")
        return provider, adapter
