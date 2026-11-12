"""向量模型 Provider 的通用边界；具体供应商不能进入 RAG 领域模型。

职责：
  - EmbeddingProvider 协议 — 文本和图片向量化的统一入口。
  - EmbeddingProviderFactory — 按 (provider_code, api_mode) 选择适配器。
  - 数据类（EmbeddingText/EmbeddingMedia/DenseEmbedding/EmbeddingBatch）— 强类型输入输出。

关键约束：
  - 向量维度必须与 Profile 声明的 dimensions 一致。
  - 响应不完整或索引错乱必须失败。
  - 支持的通配符 "*" 表示任意供应商可用的通用适配器。
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from ai_service.contracts.rag_types import EmbeddingProfile
from ai_service.providers.errors import ModelConfigurationError

# ============================================================================
# 数据结构
# ============================================================================


@dataclass(frozen=True, slots=True)
class EmbeddingText:
    """一次向量输入；标识只用于把返回向量恢复到原片段顺序。"""

    input_id: str
    text: str


@dataclass(frozen=True, slots=True)
class EmbeddingMedia:
    """一次受控图片向量输入；文件必须来自当前任务临时工作区。"""

    input_id: str
    path: Path
    media_type: str


@dataclass(frozen=True, slots=True)
class DenseEmbedding:
    """已校验维度和有限数值的单条稠密向量。"""

    input_id: str
    values: tuple[float, ...]


@dataclass(frozen=True, slots=True)
class EmbeddingBatch:
    """一次批次的有序向量与供应商实际用量。"""

    embeddings: tuple[DenseEmbedding, ...]
    request_count: int
    prompt_tokens: int
    total_tokens: int


# ============================================================================
# Embedding Provider 协议
# ============================================================================


class EmbeddingProvider(Protocol):
    """执行一个已发布 Profile 的文本或图片向量化。"""

    async def embed_texts(
        self, profile: EmbeddingProfile, inputs: tuple[EmbeddingText, ...]
    ) -> EmbeddingBatch:
        """按输入顺序返回向量；响应不完整或维度不符时必须失败。"""
        ...

    async def embed_images(
        self, profile: EmbeddingProfile, inputs: tuple[EmbeddingMedia, ...]
    ) -> EmbeddingBatch:
        """按输入顺序返回图片向量；不支持图片的协议必须明确拒绝。"""
        ...


# ============================================================================
# Embedding Provider 工厂注册表
# ============================================================================


class EmbeddingProviderFactory:
    """按模型管理下发的供应商和 API 模式选择已注册适配器。"""

    def __init__(self, strategies: dict[tuple[str, str], EmbeddingProvider]) -> None:
        self._strategies = dict(strategies)

    def provider_for(self, profile: EmbeddingProfile) -> EmbeddingProvider:
        """优先精确供应商，再按协议选择通用适配器。"""
        provider = self._strategies.get((profile.provider_code, profile.api_mode))
        if provider is None:
            provider = self._strategies.get(("*", profile.api_mode))
        if provider is None:
            raise ModelConfigurationError("The embedding provider adapter is not registered")
        return provider