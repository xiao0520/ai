"""对多路召回结果执行可审计的倒数排名融合。

职责：
  - ReciprocalRankFusion — 按各路从一开始的名次计算 RRF 分数。
  - 按稳定来源去重：同一 fragment 多路命中时合并路线诊断信息。
  - 最终按 RRF 分数降序排列并截取 top-N。
  - ReciprocalRankFusionError — 召回诊断缺失或同一稳定片段出现冲突时的异常。

关键约束：
  - 融合仅在各路 Document 层执行，不读取 Qdrant 或向量。
  - RRF k 和 limit 通过构造参数注入，不依赖运行时配置。
  - 去重键为七元组 (knowledge_id, index_version, document_id, document_version_id,
    generation_id, chunk_id, content_hash)，确保同一内容不被重复计入。
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from langchain_core.documents import Document
from langchain_core.runnables import Runnable, RunnableLambda

from ai_service.rag.langchain_documents import source_location_from_document

# ============================================================================
# 异常
# ============================================================================


class ReciprocalRankFusionError(RuntimeError):
    """召回诊断缺失或同一稳定片段出现冲突时抛出的异常。"""


# ============================================================================
# RRF 融合器
# ============================================================================


@dataclass(frozen=True, slots=True)
class ReciprocalRankFusion:
    """按各路从一开始的名次计算 RRF，并按稳定来源去重和排序。

    融合公式：RRF(d) = sum(1 / (k + rank_i(d)))，其中 k 为平滑常数。
    同一 fragment 在多个召回路线中命中时，取最佳排名并合并路线诊断信息。
    """

    k: int
    limit: int

    def __post_init__(self) -> None:
        if self.k < 1 or self.limit < 1:
            raise ValueError("VALIDATION")

    def as_runnable(self) -> Runnable[list[Document], list[Document]]:
        """返回可插入 LCEL 主链的确定性融合节点。"""
        return RunnableLambda(self.fuse)

    def fuse(self, documents: list[Document]) -> list[Document]:
        """合并重复片段的路线诊断，再计算 RRF 分数和最终名次。"""
        merged: dict[tuple[str, ...], Document] = {}
        for original in documents:
            location = source_location_from_document(original)
            source = location.source
            key = (
                source.knowledge_id,
                source.index_version,
                source.document_id,
                source.document_version_id,
                source.generation_id,
                source.chunk_id,
                source.content_hash,
            )
            ranks, scores = self._diagnostics(original)
            current = merged.get(key)
            if current is None:
                current = original.model_copy(deep=True)
                current.metadata["retrievalRanks"] = dict(ranks)
                current.metadata["retrievalScores"] = dict(scores)
                merged[key] = current
                continue
            if current.page_content != original.page_content:
                raise ReciprocalRankFusionError("INDEX_MISMATCH")
            current_ranks, current_scores = self._diagnostics(current)
            for route, rank in ranks.items():
                previous = current_ranks.get(route)
                if previous is None or rank < previous:
                    current_ranks[route] = rank
                    if route in scores:
                        current_scores[route] = scores[route]
            current.metadata["retrievalRanks"] = current_ranks
            current.metadata["retrievalScores"] = current_scores

        fused: list[Document] = []
        for document in merged.values():
            ranks, _ = self._diagnostics(document)
            rrf_score = sum(1.0 / (self.k + rank) for rank in ranks.values())
            document.metadata["rrfScore"] = rrf_score
            document.metadata["rrfMatchedRoutes"] = len(ranks)
            fused.append(document)
        fused.sort(key=self._sort_key)
        selected = fused[: self.limit]
        for rank, document in enumerate(selected, start=1):
            document.metadata["rrfRank"] = rank
        return selected

    @staticmethod
    def _diagnostics(document: Document) -> tuple[dict[str, int], dict[str, float]]:
        """从 metadata 中提取召回诊断信息，校验类型和值合法性。"""
        raw_ranks = document.metadata.get("retrievalRanks")
        raw_scores = document.metadata.get("retrievalScores")
        if not isinstance(raw_ranks, dict) or not raw_ranks or not isinstance(raw_scores, dict):
            raise ReciprocalRankFusionError("INDEX_MISMATCH")
        ranks: dict[str, int] = {}
        scores: dict[str, float] = {}
        for route, rank in raw_ranks.items():
            if not isinstance(route, str) or isinstance(rank, bool) or not isinstance(rank, int):
                raise ReciprocalRankFusionError("INDEX_MISMATCH")
            if rank < 1:
                raise ReciprocalRankFusionError("INDEX_MISMATCH")
            ranks[route] = rank
        for route, score in raw_scores.items():
            if not isinstance(route, str) or isinstance(score, bool) or not isinstance(
                score, (int, float)
            ):
                raise ReciprocalRankFusionError("INDEX_MISMATCH")
            normalized_score = float(score)
            if not math.isfinite(normalized_score):
                raise ReciprocalRankFusionError("INDEX_MISMATCH")
            scores[route] = normalized_score
        return ranks, scores

    @staticmethod
    def _sort_key(document: Document) -> tuple[float, int, int, tuple[str, ...]]:
        """RRF 融合后的排序键：分数降序 > 路线数降序 > 最佳排名升序 > 稳定键。

        使用 -rrfScore 和 -路线数 实现降序，min(rank) 实现升序（名次越小越好）。
        最后使用稳定键作为 tiebreaker，确保相同分数的结果在不同运行中排序一致。
        """
        location = source_location_from_document(document)
        source = location.source
        ranks = document.metadata["retrievalRanks"]
        if not isinstance(ranks, dict):
            raise ReciprocalRankFusionError("INDEX_MISMATCH")
        return (
            -float(document.metadata["rrfScore"]),
            -len(ranks),
            min(int(rank) for rank in ranks.values()),
            (
                source.knowledge_id,
                source.index_version,
                source.document_id,
                source.document_version_id,
                source.generation_id,
                source.chunk_id,
                source.content_hash,
            ),
        )