"""知识库解析与切片领域模型；不携带权限或外部存储凭证。

职责：
  - 定义解析阶段的核心数据类：ParsedBlock、ParsedImage、ParsedDocument、KnowledgeChunk。
  - 定义统一的异常类型 KnowledgeProcessingError，将故障归一化为稳定错误码。
  - 提供类型别名（ParsedLocator、ParsedBlockKind、ChunkModality）统一跨模块语义。

关键约束：
  - 所有数据类均为 frozen dataclass，不可变以保障解析→切片→向量化三阶段一致性。
  - 不携带权限、凭证或外部存储引用，所有状态由上游命令快照驱动。
  - KnowledgeChunk 的 content_hash 生成后正文不得再改变，链式引用（parent/prev/next）必须自洽。
"""

from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from ai_service.contracts.rag_types import ExecutionUsageItem
from ai_service.contracts.retrieval import (
    DocxBlockLocator,
    ImageLocator,
    PdfPageLocator,
    PresentationBlockLocator,
    SourceLocation,
    SpreadsheetRangeLocator,
    TableRangeLocator,
    TextRangeLocator,
)

# ============================================================================
# 类型别名：统一跨模块的领域语义
# ============================================================================

type ParsedLocator = (
    PdfPageLocator
    | TextRangeLocator
    | DocxBlockLocator
    | PresentationBlockLocator
    | SpreadsheetRangeLocator
    | TableRangeLocator
    | ImageLocator
)
type ParsedBlockKind = Literal["PARAGRAPH", "CODE", "TABLE_ROW", "IMAGE"]
type ChunkModality = Literal["TEXT", "IMAGE"]
type KnowledgeProcessingErrorCode = Literal[
    "DOCUMENT_UNSUPPORTED", "PARSE_FAILED", "INDEX_MISMATCH", "BUDGET_EXCEEDED"
]


# ============================================================================
# 统一异常
# ============================================================================


class KnowledgeProcessingError(RuntimeError):
    """将解析和切片故障归一化为 Kafka 已发布的稳定错误码。

    error_code 来自 KnowledgeProcessingErrorCode 类型别名，保证与 Java 端
    的 Kafka 事件错误码映射一致，避免运行时返回未定义的错误码。"""

    def __init__(
        self,
        error_code: KnowledgeProcessingErrorCode,
        *,
        usage_items: tuple[ExecutionUsageItem, ...] = (),
        measured_tokens: int | None = None,
        planned_tokens: int | None = None,
        max_tokens: int | None = None,
    ) -> None:
        """保存稳定错误码及失败前已发生的模型用量和预算诊断。"""
        super().__init__(error_code)
        self.error_code = error_code
        self.usage_items = usage_items
        self.measured_tokens = measured_tokens
        self.planned_tokens = planned_tokens
        self.max_tokens = max_tokens


# ============================================================================
# 不可变领域数据类
# ============================================================================


@dataclass(frozen=True, slots=True)
class ParsedBlock:
    """单个不可变结构块；图片块由受控视觉模型生成可检索文本。

    字段分组：
      标识与结构 — ordinal / kind / title_path / locator
      内容 — text / context_text（表头或周边文本）
      模态扩展 — modality / media_id / media_hash（图片块专用）
    """

    ordinal: int
    kind: ParsedBlockKind
    text: str
    title_path: tuple[str, ...]
    locator: ParsedLocator
    context_text: str | None = None
    modality: ChunkModality = "TEXT"
    media_id: str | None = None
    media_hash: str | None = None


@dataclass(frozen=True, slots=True)
class ParsedImage:
    """从受控源文件提取并规范化的临时图片；任务结束后随工作区删除。

    图片从 DOCX、PPTX、XLSX、PDF 或 Markdown Base64 data URI 中提取，转为 PNG/JPEG
    落盘到临时目录。content_hash 确保图片内容可校验，防止中途篡改。
    """

    media_id: str
    path: Path
    media_type: Literal["image/png", "image/jpeg"]
    content_hash: str
    locator: ImageLocator
    title_path: tuple[str, ...]
    alt_text: str | None = None


@dataclass(frozen=True, slots=True)
class ParsedDocument:
    """受支持文档统一输出；正文和图片仅存在于当前执行批次内。

    当前解析器超出 ParserLimits 时直接失败，不返回截断后的成功文档；
    因此正常解析结果的 truncated 保持 False。
    """

    title: str
    media_type: str
    blocks: tuple[ParsedBlock, ...]
    character_count: int
    page_count: int = 1
    skipped_block_count: int = 0
    truncated: bool = False
    images: tuple[ParsedImage, ...] = ()
    skipped_image_count: int = 0


@dataclass(frozen=True, slots=True)
class KnowledgeChunk:
    """Embedding 前的最终片段；contentHash 生成后正文不得再改变。

    链式引用说明：
      parent_chunk_id  — 同一原始结构块的稳定分组标识，供兄弟片段补齐。
      previous_chunk_id — 在文档全局序列中的前一个 chunk_id（为空表示是文档首块）。
      next_chunk_id     — 在文档全局序列中的后一个 chunk_id（为空表示是文档尾块）。
      此链供上下文补齐阶段（ContextSelector）按相邻关系展开证据。
    """

    chunk_id: str
    point_id: str
    ordinal: int
    text: str
    token_count: int
    content_hash: str
    parent_chunk_id: str
    previous_chunk_id: str | None
    next_chunk_id: str | None
    location: SourceLocation
    parser_revision: str
    splitter_revision: str
    tokenizer_revision: str
    modality: ChunkModality = "TEXT"
    media_id: str | None = None
    media_hash: str | None = None
