"""文档图片 OCR/视觉理解 Provider 的通用边界。

职责：
  - VisionProvider 协议 — OCR 和视觉描述的统一入口。
  - VisionProviderFactory — 按 (provider_code, api_mode) 选择视觉适配器。
  - 数据类 — VisionInput/VisionOutput/VisionBatch，保持输入输出强类型。

关键约束：
  - 图片路径必须来自当前任务临时工作区，拒绝外部路径。
  - 输出数量必须与输入一致，不完整或空输出直接失败。
  - 支持通配符 "*" 表示任意供应商可用的通用适配器。
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from ai_service.contracts.rag_types import VisionProfile
from ai_service.providers.errors import ModelConfigurationError
from ai_service.providers.metering import UsageReporter

# ============================================================================
# 数据结构
# ============================================================================


@dataclass(frozen=True, slots=True)
class VisionInput:
    """从当前任务工作区读取的一张规范化图片。"""

    input_id: str
    path: Path
    media_type: str
    context: str
    ocr_only: bool = False
    max_output_tokens: int | None = None
    estimated_input_tokens: int = 0
    report_usage: UsageReporter | None = None


@dataclass(frozen=True, slots=True)
class VisionOutput:
    """一张图片对应的可检索文字与描述。"""

    input_id: str
    text: str


@dataclass(frozen=True, slots=True)
class VisionBatch:
    """有序视觉结果及供应商报告的实际用量。"""

    outputs: tuple[VisionOutput, ...]
    request_count: int
    input_tokens: int
    output_tokens: int


# ============================================================================
# 视觉 Provider 协议
# ============================================================================


class VisionProvider(Protocol):
    """执行一个已发布视觉模型 Profile。"""

    async def analyze(self, profile: VisionProfile, inputs: tuple[VisionInput, ...]) -> VisionBatch:
        """按输入顺序返回结果；空输出或数量不一致必须失败。"""
        ...


# ============================================================================
# 视觉 Provider 工厂注册表
# ============================================================================


class VisionProviderFactory:
    """按模型管理下发的供应商和 API 模式选择视觉适配器。"""

    def __init__(self, strategies: dict[tuple[str, str], VisionProvider]) -> None:
        self._strategies = dict(strategies)

    def provider_for(self, profile: VisionProfile) -> VisionProvider:
        """优先精确供应商，再按兼容协议选择通用适配器。"""
        provider = self._strategies.get((profile.provider_code, profile.api_mode))
        if provider is None:
            provider = self._strategies.get(("*", profile.api_mode))
        if provider is None:
            raise ModelConfigurationError("The vision provider adapter is not registered")
        return provider
