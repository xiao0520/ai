"""中文 BM25 稀疏编码；词项映射与权重规则由索引快照 revision 冻结。

职责：
  - ChineseBm25SparseEmbeddings — 使用 jieba 分词和 FNV-1a 稳定词项 ID 生成 BM25 稀疏向量。
  - 文档侧应用 BM25 TF 饱和公式（k1=1.2, b=0.75）+ 长度归一化。
  - 查询侧仅保留原始词频，IDF 交给 Qdrant 在检索时动态计算。
  - SparseEncodingError — 快照版本、文本或词项映射不满足冻结规则时的异常。

关键约束：
  - sparse_revision 必须为 "jieba-bm25-v1"，revision 不匹配时拒绝生成。
  - 过滤纯符号和空白词项；含字母、数字或 CJK 统一表意文字的词项保留。
  - 词项 ID 与文档侧完全一致（FNV-1a 32 位 hash），确保向量维度对齐。
  - FNV-1a 哈希冲突（不同词项映射到同一索引）直接拒绝。
"""

from __future__ import annotations

import math
import re
import unicodedata
from collections import Counter

import jieba
from langchain_qdrant import SparseEmbeddings, SparseVector

_SUPPORTED_SPARSE_REVISION = "jieba-bm25-v1"
_SUPPORTED_CORPUS_PREFIX = "corpus-v1-"
_TERM_CHARACTER = re.compile(r"[A-Za-z0-9\u3400-\u9fff]")
_BM25_K1 = 1.2
_BM25_B = 0.75
_BM25_AVERAGE_LENGTH = 256.0


# ============================================================================
# 异常
# ============================================================================


class SparseEncodingError(RuntimeError):
    """快照版本、文本或词项映射不满足冻结规则。"""


# ============================================================================
# BM25 稀疏编码器
# ============================================================================


class ChineseBm25SparseEmbeddings(SparseEmbeddings):
    """使用 jieba 和稳定词项 ID 生成 BM25 稀疏向量，IDF 交给 Qdrant 维护。"""

    def __init__(self, sparse_revision: str, sparse_corpus_revision: str) -> None:
        if sparse_revision != _SUPPORTED_SPARSE_REVISION:
            raise SparseEncodingError("INDEX_MISMATCH")
        if not sparse_corpus_revision.startswith(_SUPPORTED_CORPUS_PREFIX):
            raise SparseEncodingError("INDEX_MISMATCH")
        self.sparse_revision = sparse_revision
        self.sparse_corpus_revision = sparse_corpus_revision
        self._tokenizer = jieba.Tokenizer()

    def embed_documents(self, texts: list[str]) -> list[SparseVector]:
        """生成文档侧 BM25 稀疏向量：TF 饱和 + 长度归一化，IDF 由 Qdrant 索引时计算。

        与 embed_query 的区别在于使用 BM25 的 TF 饱和公式（k1=1.2, b=0.75），
        而查询侧仅保留原始词频。"""
        return [self._document_vector(text) for text in texts]

    def embed_query(self, text: str) -> SparseVector:
        """查询侧保留原始词频，不应用 BM25 饱和公式；Qdrant 在检索时动态接入 IDF。

        词项 ID 与文档侧完全一致（使用相同的 FNV-1a 映射），确保向量维度对齐。"""
        counts = Counter(self._tokens(text))
        return self._vector(counts, {term: float(count) for term, count in counts.items()})

    def _document_vector(self, text: str) -> SparseVector:
        """文档侧 BM25 权重 = 词频 * (k1 + 1) / (词频 + k1 * 长度归一化因子)。

        长度归一化因子 = 1 - b + b * |d| / avg_doc_len，其中 avg_doc_len 固定为 256。"""
        tokens = self._tokens(text)
        counts = Counter(tokens)
        length = len(tokens)
        denominator_length = 1.0 - _BM25_B + _BM25_B * length / _BM25_AVERAGE_LENGTH
        values = {
            term: count * (_BM25_K1 + 1.0) / (count + _BM25_K1 * denominator_length)
            for term, count in counts.items()
        }
        return self._vector(counts, values)

    def _tokens(self, text: str) -> tuple[str, ...]:
        """使用 jieba（关闭 HMM 新词发现）分词，NFKC 归一化后过滤纯符号 Token。

        只保留包含字母/数字/汉字的 Token，标点和空白被丢弃以降低噪声。"""
        normalized = unicodedata.normalize("NFKC", text).strip()
        if not normalized:
            raise SparseEncodingError("PARSE_FAILED")
        tokens = tuple(
            token.lower()
            for raw in self._tokenizer.cut(normalized, HMM=False)
            if (token := raw.strip()) and _TERM_CHARACTER.search(token)
        )
        if not tokens:
            raise SparseEncodingError("PARSE_FAILED")
        return tokens

    def _vector(self, counts: Counter[str], values: dict[str, float]) -> SparseVector:
        """将词项-Counter 和词项-权重转为按索引排序的 SparseVector。

        同时检测 FNV-1a 哈希冲突：不同词项映射到同一索引时拒绝，避免静默覆盖。"""
        indexed: list[tuple[int, str, float]] = []
        seen: dict[int, str] = {}
        for term in counts:
            index = self._term_index(term)
            previous = seen.get(index)
            if previous is not None and previous != term:
                raise SparseEncodingError("INDEX_MISMATCH")
            seen[index] = term
            value = values[term]
            if not math.isfinite(value) or value <= 0:
                raise SparseEncodingError("INDEX_MISMATCH")
            indexed.append((index, term, value))
        indexed.sort(key=lambda item: item[0])
        return SparseVector(
            indices=[item[0] for item in indexed],
            values=[item[2] for item in indexed],
        )

    @staticmethod
    def _term_index(term: str) -> int:
        """FNV-1a 32 位映射不依赖 Python hash seed，跨进程和重放保持稳定。"""
        value = 2_166_136_261
        for byte in term.encode("utf-8"):
            value ^= byte
            value = value * 16_777_619 & 0xFFFF_FFFF
        return value or 1