"""受限文档解析器；只读取 Worker 已校验的本地源文件。

职责：
  - 支持文本、PDF、Word、Excel、PowerPoint 的结构化解析。
  - 按 parser_revision 冻结的规则提取标题路径、表格和块边界。
  - 可选提取内嵌图片（DOCX、XLSX、PPTX、Markdown 和 PDF）。

关键约束：
  - 字符、块、页或表格行超限时拒绝解析；超限图片跳过并记录数量。
  - DOCX/XLSX 图片从 OOXML 包中直接提取（PIL 解码），不依赖系统渲染。
  - 解析失败与预算超限分别返回 PARSE_FAILED、BUDGET_EXCEEDED，不伪造完整结果。
"""

from __future__ import annotations

import asyncio
import base64
import binascii
import csv
import io
import posixpath
import re
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import date, datetime, time
from pathlib import Path
from typing import cast
from xml.etree import ElementTree
from zipfile import BadZipFile, ZipFile

from docx import Document as WordDocument
from docx.opc.exceptions import PackageNotFoundError
from docx.table import Table, _Cell
from docx.text.paragraph import Paragraph
from openpyxl import load_workbook
from openpyxl.utils.exceptions import InvalidFileException
from pptx import Presentation
from pptx.enum.shapes import MSO_SHAPE_TYPE
from pptx.exc import PackageNotFoundError as PresentationPackageNotFoundError
from pptx.shapes.graphfrm import GraphicFrame
from pptx.shapes.picture import Picture
from pypdf.errors import PdfReadError

from ai_service.contracts.retrieval import (
    DocxBlockLocator,
    PresentationBlockLocator,
    SpreadsheetRangeLocator,
    TableRangeLocator,
    TextRangeLocator,
)
from ai_service.domain.knowledge_execution import DownloadedKnowledgeSource
from ai_service.rag.image_normalizer import ImageNormalizer
from ai_service.rag.models import (
    KnowledgeProcessingError,
    ParsedBlock,
    ParsedDocument,
    ParsedImage,
)
from ai_service.rag.parser_limits import ParserLimits
from ai_service.rag.pdf_parser import PdfDocumentParser

_SUPPORTED_PARSER_REVISION = "python-parser-v1"
_CSV_MEDIA_TYPE = "text/csv"
_DOCX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
_XLSX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
_PPTX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
_MEDIA_TYPE_ALIASES = {
    "text/plain": "text/plain",
    "text/markdown": "text/markdown",
    "text/x-markdown": "text/markdown",
    _CSV_MEDIA_TYPE: _CSV_MEDIA_TYPE,
    "application/csv": _CSV_MEDIA_TYPE,
    "text/comma-separated-values": _CSV_MEDIA_TYPE,
    _DOCX_MEDIA_TYPE: _DOCX_MEDIA_TYPE,
    _XLSX_MEDIA_TYPE: _XLSX_MEDIA_TYPE,
    _PPTX_MEDIA_TYPE: _PPTX_MEDIA_TYPE,
    "application/pdf": "application/pdf",
}
_EXTENSION_MEDIA_TYPES = {
    ".txt": "text/plain",
    ".md": "text/markdown",
    ".markdown": "text/markdown",
    ".csv": _CSV_MEDIA_TYPE,
    ".docx": _DOCX_MEDIA_TYPE,
    ".xlsx": _XLSX_MEDIA_TYPE,
    ".pptx": _PPTX_MEDIA_TYPE,
    ".pdf": "application/pdf",
}
_GENERIC_MEDIA_TYPES = {
    "",
    "application/octet-stream",
    "application/zip",
    "application/x-zip-compressed",
}
_HEADING = re.compile(r"^(#{1,6})[ \t]+(.+?)[ \t]*#*[ \t]*$")
_FENCE = re.compile(r"^[ \t]*(`{3,}|~{3,})")
_TABLE_SEPARATOR = re.compile(
    r"^[ \t]*\|?[ \t]*:?-{3,}:?[ \t]*(?:\|[ \t]*:?-{3,}:?[ \t]*)+\|?[ \t]*$"
)
_DOCX_HEADING = re.compile(r"^Heading\s+([1-9][0-9]*)$", re.IGNORECASE)
_MARKDOWN_IMAGE = re.compile(r"!\[(?P<alt>[^\]\r\n]{0,512})\]\((?P<target>[^)\r\n]+)\)")
_DATA_IMAGE = re.compile(
    r"^data:image/(?P<subtype>png|jpeg|jpg);base64,(?P<data>[A-Za-z0-9+/=\s]+)$",
    re.IGNORECASE,
)
_OFFICE_DOCUMENT_RELATIONSHIPS_NS = (
    "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
)


# ============================================================================
# 解析器限制与内部数据类
# ============================================================================


@dataclass(frozen=True, slots=True)
class _Line:
    """Markdown 行及其原文字符偏移；full_end 包含行尾换行符。"""

    text: str
    start: int
    end: int
    full_end: int


# ============================================================================
# 知识文档解析器
# ============================================================================


class KnowledgeDocumentParser:
    """解析受支持的文本、PDF、Word、Excel 和 PowerPoint 文档。"""

    def __init__(self, limits: ParserLimits) -> None:
        self.limits = limits
        self._image_normalizer = ImageNormalizer(limits)
        self._pdf_parser = PdfDocumentParser(limits, self._image_normalizer)

    async def parse(
        self,
        source: DownloadedKnowledgeSource,
        source_name: str,
        parser_revision: str,
        *,
        extract_images: bool = False,
    ) -> ParsedDocument:
        """在线程中执行阻塞解析；revision 不匹配时拒绝静默换算法。"""
        if parser_revision != _SUPPORTED_PARSER_REVISION:
            raise KnowledgeProcessingError("INDEX_MISMATCH")
        return await asyncio.to_thread(self._parse_sync, source, source_name, extract_images)

    def _parse_sync(
        self,
        source: DownloadedKnowledgeSource,
        source_name: str,
        extract_images: bool,
    ) -> ParsedDocument:
        """按受控 MIME 分派解析器，并把格式损坏统一映射为解析失败。"""
        media_type = self._resolve_media_type(source.media_type, source_name)
        title = self._safe_title(source_name)
        try:
            if media_type == "text/plain":
                document = self._parse_text(source.path, title, media_type)
            elif media_type == "text/markdown":
                document = self._parse_markdown(
                    source.path,
                    source.workspace_path,
                    title,
                    media_type,
                    extract_images,
                )
            elif media_type == _CSV_MEDIA_TYPE:
                document = self._parse_csv(source.path, title, media_type)

            elif media_type == _XLSX_MEDIA_TYPE:
                document = self._parse_xlsx(
                    source.path,
                    source.workspace_path,
                    title,
                    media_type,
                    extract_images,
                )
            elif media_type == _DOCX_MEDIA_TYPE:
                document = self._parse_docx(
                    source.path,
                    source.workspace_path,
                    title,
                    media_type,
                    extract_images,
                )
            elif media_type == _PPTX_MEDIA_TYPE:
                document = self._parse_pptx(
                    source.path,
                    source.workspace_path,
                    title,
                    media_type,
                    extract_images,
                )
            elif media_type == "application/pdf":
                document = self._pdf_parser.parse(
                    source.path,
                    source.workspace_path,
                    title,
                    media_type,
                    extract_images,
                )
            else:
                raise KnowledgeProcessingError("DOCUMENT_UNSUPPORTED")
        except KnowledgeProcessingError:
            raise
        except (
            BadZipFile,
            InvalidFileException,
            PackageNotFoundError,
            PdfReadError,
            PresentationPackageNotFoundError,
            ElementTree.ParseError,
            KeyError,
            OSError,
            ValueError,
        ) as error:
            raise KnowledgeProcessingError("PARSE_FAILED") from error
        return self._validate(document)

    def _resolve_media_type(self, media_type: str, source_name: str) -> str:
        """将 Java 下发的 MIME 类型映射到受支持格式。

        支持常见别名；空白、octet-stream 和通用 ZIP 类型回退到扩展名推断。
        已声明的具体 MIME 与扩展名冲突时拒绝，避免把任意二进制误当文档解析。"""
        normalized = media_type.partition(";")[0].strip().lower()
        extension_type = _EXTENSION_MEDIA_TYPES.get(Path(source_name).suffix.lower())
        if normalized in _GENERIC_MEDIA_TYPES:
            if extension_type is not None:
                return extension_type
            raise KnowledgeProcessingError("DOCUMENT_UNSUPPORTED")

        # 浏览器上传 CSV 时可能错误标记为旧 Excel MIME；仅在扩展名明确为 CSV 时兼容。
        if normalized == "application/vnd.ms-excel" and extension_type == _CSV_MEDIA_TYPE:
            return extension_type
        resolved = _MEDIA_TYPE_ALIASES.get(normalized)
        if resolved is None:
            raise KnowledgeProcessingError("DOCUMENT_UNSUPPORTED")
        if extension_type is None or resolved == extension_type:
            return resolved
        if extension_type == "text/markdown" and resolved == "text/plain":
            return extension_type

        raise KnowledgeProcessingError("DOCUMENT_UNSUPPORTED")

    def _parse_text(self, path: Path, title: str, media_type: str) -> ParsedDocument:
        """按空行（连续两个换行）分段为 PARAGRAPH 块，每段可独立索引。"""
        text = self._decode_text(path)
        blocks = tuple(
            ParsedBlock(
                ordinal=index,
                kind="PARAGRAPH",
                text=text[start:end],
                title_path=(),
                locator=TextRangeLocator.model_validate(
                    {"kind": "TEXT_RANGE", "startOffset": start, "endOffset": end}
                ),
            )
            for index, (start, end) in enumerate(self._paragraph_spans(text))
        )
        return ParsedDocument(title, media_type, blocks, len(text))

    def _parse_markdown(
        self,
        path: Path,
        workspace: Path,
        title: str,
        media_type: str,
        extract_images: bool,
    ) -> ParsedDocument:
        """解析 Markdown：识别标题层级、代码围栏、GFM 表格和 Base64 内嵌图片。

        标题形成 title_path 层级链，表格行继承当前标题路径，图片仅在 MULTIMODAL
        模式下提取并转为 PNG 文件，非多模态模式图片占位符被空格覆盖。"""
        text = self._decode_text(path)
        parse_text = text
        images: list[ParsedImage] = []
        skipped_images = 0
        matches = list(_MARKDOWN_IMAGE.finditer(text))
        if matches:
            # 图片语法（尤其是 Base64 数据）不应进入正文索引，保留换行以稳定偏移。
            masked = list(text)
            for image_index, match in enumerate(matches):
                for offset in range(match.start(), match.end()):
                    if masked[offset] not in {"\r", "\n"}:
                        masked[offset] = " "
                if not extract_images:
                    continue
                if len(images) >= self.limits.max_images:
                    skipped_images += 1
                    continue
                target = match.group("target").strip()
                data_match = _DATA_IMAGE.fullmatch(target)
                if data_match is None:
                    skipped_images += 1
                    continue
                try:
                    raw = base64.b64decode(data_match.group("data"), validate=True)
                except binascii.Error, ValueError:
                    skipped_images += 1
                    continue
                image = self._image_normalizer.normalize(
                    raw,
                    workspace,
                    container="MARKDOWN",
                    image_index=image_index,
                    start_offset=match.start(),
                    end_offset=match.end(),
                    alt_text=match.group("alt").strip() or None,
                )
                if image is None:
                    skipped_images += 1
                else:
                    images.append(image)
            parse_text = "".join(masked)

        lines = self._lines(parse_text)
        blocks: list[ParsedBlock] = []
        title_path: list[str] = []
        table_index = 0
        index = 0
        while index < len(lines):
            line = lines[index]
            stripped = line.text.strip()
            if not stripped:
                index += 1
                continue
            heading = _HEADING.match(line.text)
            if heading:
                self._set_heading(title_path, len(heading.group(1)), heading.group(2))
                index += 1
                continue
            fence = _FENCE.match(line.text)
            if fence:
                end_index = self._fence_end(lines, index, fence.group(1)[0])
                self._append_text_block(
                    blocks,
                    "CODE",
                    parse_text,
                    line.start,
                    lines[end_index].end,
                    title_path,
                )
                index = end_index + 1
                continue
            if (
                index + 1 < len(lines)
                and "|" in line.text
                and _TABLE_SEPARATOR.match(lines[index + 1].text)
            ):
                end_index = index + 2
                while end_index < len(lines) and "|" in lines[end_index].text:
                    end_index += 1
                rows = [line, *lines[index + 2 : end_index]]
                header = self._table_row_text(rows[0].text)
                column_count = max(1, len(self._table_cells(rows[0].text)))
                for row_index, row in enumerate(rows):
                    row_text = self._table_row_text(row.text)
                    if not row_text:
                        continue
                    blocks.append(
                        ParsedBlock(
                            ordinal=len(blocks),
                            kind="TABLE_ROW",
                            text=row_text,
                            title_path=tuple(title_path),
                            locator=TableRangeLocator.model_validate(
                                {
                                    "kind": "TABLE_RANGE",
                                    "tableIndex": table_index,
                                    "rowStart": row_index,
                                    "rowEnd": row_index + 1,
                                    "columnStart": 0,
                                    "columnEnd": column_count,
                                }
                            ),
                            context_text=header if row_index > 0 else None,
                        )
                    )
                table_index += 1
                index = end_index
                continue
            end_index = index + 1
            while end_index < len(lines):
                candidate = lines[end_index]
                if (
                    not candidate.text.strip()
                    or _HEADING.match(candidate.text)
                    or _FENCE.match(candidate.text)
                ):
                    break
                if (
                    end_index + 1 < len(lines)
                    and "|" in candidate.text
                    and _TABLE_SEPARATOR.match(lines[end_index + 1].text)
                ):
                    break
                end_index += 1
            self._append_text_block(
                blocks,
                "PARAGRAPH",
                parse_text,
                line.start,
                lines[end_index - 1].end,
                title_path,
            )
            index = end_index
        return ParsedDocument(
            title,
            media_type,
            tuple(blocks),
            len(text),
            images=tuple(images),
            skipped_image_count=skipped_images,
        )

    def _parse_docx(
        self,
        path: Path,
        workspace: Path,
        title: str,
        media_type: str,
        extract_images: bool,
    ) -> ParsedDocument:
        """解析 Word 文档：段落按 Heading 样式提取标题层级，表格提取为 TABLE_ROW。

        内嵌图片从 word/media/ 中提取并尺寸校验；标题优先用 core_properties.title，
        文档级 title_path 在每次遇到标题样式时更新。"""
        document = WordDocument(str(path))
        blocks: list[ParsedBlock] = []
        title_path: list[str] = []
        table_index = 0
        block_index = 0
        for element in document.iter_inner_content():
            if isinstance(element, Paragraph):
                text = element.text.strip()
                style_name = element.style.name if element.style is not None else ""
                heading = _DOCX_HEADING.match(style_name)
                if heading and text:
                    self._set_heading(title_path, int(heading.group(1)), text)
                elif text:
                    blocks.append(
                        ParsedBlock(
                            ordinal=len(blocks),
                            kind="PARAGRAPH",
                            text=text,
                            title_path=tuple(title_path),
                            locator=DocxBlockLocator.model_validate(
                                {"kind": "DOCX_BLOCK", "blockIndex": block_index}
                            ),
                        )
                    )
            elif isinstance(element, Table):
                header = self._docx_row_text(element.rows[0].cells) if element.rows else ""
                column_count = max((len(row.cells) for row in element.rows), default=1)
                for row_index, row in enumerate(element.rows):
                    row_text = self._docx_row_text(row.cells)
                    if not row_text:
                        continue
                    blocks.append(
                        ParsedBlock(
                            ordinal=len(blocks),
                            kind="TABLE_ROW",
                            text=row_text,
                            title_path=tuple(title_path),
                            locator=TableRangeLocator.model_validate(
                                {
                                    "kind": "TABLE_RANGE",
                                    "tableIndex": table_index,
                                    "rowStart": row_index,
                                    "rowEnd": row_index + 1,
                                    "columnStart": 0,
                                    "columnEnd": max(1, column_count),
                                }
                            ),
                            context_text=header if row_index > 0 else None,
                        )
                    )
                table_index += 1
            block_index += 1

        images: list[ParsedImage] = []
        skipped_images = 0
        if extract_images:
            with ZipFile(path) as archive:
                media_entries = sorted(
                    (
                        entry
                        for entry in archive.infolist()
                        if entry.filename.startswith("word/media/") and not entry.is_dir()
                    ),
                    key=lambda entry: entry.filename,
                )
                for image_index, entry in enumerate(media_entries):
                    if (
                        len(images) >= self.limits.max_images
                        or entry.file_size > self.limits.max_image_bytes
                    ):
                        skipped_images += 1
                        continue
                    image = self._image_normalizer.normalize(
                        archive.read(entry),
                        workspace,
                        container="DOCX",
                        image_index=image_index,
                    )
                    if image is None:
                        skipped_images += 1
                    else:
                        images.append(image)

        document_title = document.core_properties.title.strip()
        return ParsedDocument(
            self._safe_title(document_title) if document_title else title,
            media_type,
            tuple(blocks),
            sum(len(block.text) for block in blocks),
            images=tuple(images),
            skipped_image_count=skipped_images,
        )

    def _parse_csv(self, path: Path, title: str, media_type: str) -> ParsedDocument:
        """按 CSV 行生成表格块，并保留原始行列范围。"""
        text = self._decode_csv(path)
        try:
            dialect = csv.Sniffer().sniff(text[:65_536], delimiters=",;\t|")
        except csv.Error:
            dialect = csv.excel
        rows = csv.reader(io.StringIO(text, newline=""), dialect)
        return self._parse_spreadsheet_rows(
            ((row_number, tuple(row)) for row_number, row in enumerate(rows, start=1)),
            title,
            media_type,
            container="CSV",
            sheet_name="CSV",
            page_count=1,
        )

    def _parse_xlsx(
        self,
        path: Path,
        workspace: Path,
        title: str,
        media_type: str,
        extract_images: bool,
    ) -> ParsedDocument:
        """解析 XLSX 单元格和内嵌图片，保留公式、工作表及图片锚点。"""
        source_file = path.open("rb")
        try:
            workbook = load_workbook(source_file, read_only=True, data_only=False, keep_links=False)
        except Exception:
            source_file.close()
            raise
        try:
            if len(workbook.worksheets) > self.limits.max_office_units:
                raise KnowledgeProcessingError("BUDGET_EXCEEDED")
            blocks: list[ParsedBlock] = []
            character_count = 0
            total_rows = 0
            for worksheet in workbook.worksheets:
                sheet_name = self._safe_label(worksheet.title)
                header: str | None = None
                for row_number, row in enumerate(worksheet.iter_rows(values_only=True), start=1):
                    total_rows += 1
                    if total_rows > self.limits.max_spreadsheet_rows:
                        raise KnowledgeProcessingError("BUDGET_EXCEEDED")
                    values = tuple(self._cell_text(value) for value in row)
                    appended = self._append_spreadsheet_row(
                        blocks,
                        values,
                        container="XLSX",
                        sheet_name=sheet_name,
                        row_number=row_number,
                        header=header,
                    )
                    if appended is not None:
                        character_count += len(appended)
                        if header is None:
                            header = appended
                    self._require_parse_budget(blocks, character_count)
            workbook_title = str(workbook.properties.title or "").strip()
            worksheet_count = len(workbook.worksheets)
        finally:
            workbook.close()
            source_file.close()

        images: tuple[ParsedImage, ...] = ()
        skipped_images = 0
        if extract_images:
            images, skipped_images = self._extract_xlsx_images(path, workspace)
        return ParsedDocument(
            self._safe_title(workbook_title) if workbook_title else title,
            media_type,
            tuple(blocks),
            character_count,
            page_count=max(1, worksheet_count),
            images=images,
            skipped_image_count=skipped_images,
        )

    def _extract_xlsx_images(
        self, path: Path, workspace: Path
    ) -> tuple[tuple[ParsedImage, ...], int]:
        """从 OOXML Drawing 关系提取工作表图片，不加载宏或外部链接。"""
        images: list[ParsedImage] = []
        skipped_images = 0
        image_index = 0
        with ZipFile(path) as archive:
            # 从工作簿关系追到工作表、绘图部件，再按图片锚点恢复位置。
            workbook_part = "xl/workbook.xml"
            workbook_root = self._xml_part(archive, workbook_part)
            workbook_relationships = self._package_relationships(archive, workbook_part)
            for sheet in workbook_root.iter():
                if self._xml_local_name(sheet.tag) != "sheet":
                    continue
                relationship_id = self._relationship_attribute(sheet, "id")
                relationship = workbook_relationships.get(relationship_id or "")
                if relationship is None or not relationship[0].endswith("/worksheet"):
                    continue
                worksheet_part = self._package_target(workbook_part, relationship[1])
                if worksheet_part is None:
                    continue
                worksheet_root = self._xml_part(archive, worksheet_part)
                worksheet_relationships = self._package_relationships(archive, worksheet_part)
                sheet_name = self._safe_label(sheet.attrib.get("name", "Sheet"))
                for drawing in worksheet_root.iter():
                    if self._xml_local_name(drawing.tag) != "drawing":
                        continue
                    drawing_id = self._relationship_attribute(drawing, "id")
                    drawing_relationship = worksheet_relationships.get(drawing_id or "")
                    if drawing_relationship is None or not drawing_relationship[0].endswith(
                        "/drawing"
                    ):
                        continue
                    drawing_part = self._package_target(worksheet_part, drawing_relationship[1])
                    if drawing_part is None:
                        continue
                    drawing_root = self._xml_part(archive, drawing_part)
                    drawing_relationships = self._package_relationships(archive, drawing_part)
                    for anchor in drawing_root:
                        if self._xml_local_name(anchor.tag) not in {
                            "oneCellAnchor",
                            "twoCellAnchor",
                            "absoluteAnchor",
                        }:
                            continue
                        picture = self._xml_descendant(anchor, "pic")
                        # 图表和其他 Drawing 对象也有锚点，但不属于图片提取失败。
                        if picture is None:
                            continue
                        blip = self._xml_descendant(picture, "blip")
                        embedded_id = (
                            self._relationship_attribute(blip, "embed")
                            if blip is not None
                            else None
                        )
                        image_relationship = drawing_relationships.get(embedded_id or "")
                        row_number, column_number = self._xlsx_anchor_cell(anchor)
                        alt_text = self._xlsx_picture_alt_text(picture)
                        current_index = image_index
                        image_index += 1
                        if (
                            image_relationship is None
                            or not image_relationship[0].endswith("/image")
                            or len(images) >= self.limits.max_images
                        ):
                            skipped_images += 1
                            continue
                        image_part = self._package_target(drawing_part, image_relationship[1])
                        if image_part is None:
                            skipped_images += 1
                            continue
                        try:
                            image_entry = archive.getinfo(image_part)
                        except KeyError:
                            skipped_images += 1
                            continue
                        if (
                            image_entry.is_dir()
                            or image_entry.file_size > self.limits.max_image_bytes
                        ):
                            skipped_images += 1
                            continue
                        image = self._image_normalizer.normalize(
                            archive.read(image_entry),
                            workspace,
                            container="XLSX",
                            image_index=current_index,
                            sheet_name=sheet_name,
                            row_number=row_number,
                            column_number=column_number,
                            title_path=(sheet_name,),
                            alt_text=alt_text,
                        )
                        if image is None:
                            skipped_images += 1
                        else:
                            images.append(image)
        return tuple(images), skipped_images

    @staticmethod
    def _xml_local_name(tag: str) -> str:
        """返回 XML QName 的本地名，兼容 OOXML 前缀差异。"""
        return tag.rsplit("}", 1)[-1]

    @classmethod
    def _xml_descendant(
        cls, element: ElementTree.Element | None, local_name: str
    ) -> ElementTree.Element | None:
        """按本地名查找首个后代节点。"""
        if element is None:
            return None
        return next(
            (
                candidate
                for candidate in element.iter()
                if cls._xml_local_name(candidate.tag) == local_name
            ),
            None,
        )

    @staticmethod
    def _relationship_attribute(element: ElementTree.Element | None, local_name: str) -> str | None:
        """读取带命名空间的 relationship 属性。"""
        if element is None:
            return None
        namespaced = element.attrib.get(f"{{{_OFFICE_DOCUMENT_RELATIONSHIPS_NS}}}{local_name}")
        if namespaced:
            return namespaced
        return next(
            (
                value
                for key, value in element.attrib.items()
                if key.rsplit("}", 1)[-1] == local_name
            ),
            None,
        )

    @classmethod
    def _xml_part(cls, archive: ZipFile, part: str) -> ElementTree.Element:
        """读取一个 OOXML XML 部件；缺失或损坏时由上层统一返回解析失败。"""
        return ElementTree.fromstring(archive.read(part))

    @classmethod
    def _package_relationships(
        cls, archive: ZipFile, source_part: str
    ) -> dict[str, tuple[str, str]]:
        """读取内部 OOXML 关系；外部链接不会被访问或返回。"""
        relationships_part = posixpath.join(
            posixpath.dirname(source_part),
            "_rels",
            posixpath.basename(source_part) + ".rels",
        )
        try:
            root = cls._xml_part(archive, relationships_part)
        except KeyError:
            return {}
        relationships: dict[str, tuple[str, str]] = {}
        for relationship in root:
            if cls._xml_local_name(relationship.tag) != "Relationship":
                continue
            if relationship.attrib.get("TargetMode", "").upper() == "EXTERNAL":
                continue
            relationship_id = relationship.attrib.get("Id")
            relationship_type = relationship.attrib.get("Type")
            target = relationship.attrib.get("Target")
            if relationship_id and relationship_type and target:
                relationships[relationship_id] = (relationship_type, target)
        return relationships

    @staticmethod
    def _package_target(source_part: str, target: str) -> str | None:
        """把关系目标归一化为包内路径，并拒绝逃逸到包外。"""
        normalized_target = target.replace("\\", "/")
        if normalized_target.startswith("/"):
            candidate = posixpath.normpath(normalized_target.lstrip("/"))
        else:
            candidate = posixpath.normpath(
                posixpath.join(posixpath.dirname(source_part), normalized_target)
            )
        if candidate in {"", ".", ".."} or candidate.startswith("../"):
            return None
        return candidate

    @classmethod
    def _xlsx_anchor_cell(cls, anchor: ElementTree.Element) -> tuple[int | None, int | None]:
        """读取图片左上角锚点；绝对定位图片没有单元格坐标。"""
        marker = next(
            (child for child in anchor if cls._xml_local_name(child.tag) == "from"),
            None,
        )
        if marker is None:
            return None, None
        row = cls._xml_descendant(marker, "row")
        column = cls._xml_descendant(marker, "col")
        try:
            row_number = int(row.text) + 1 if row is not None and row.text is not None else None
            column_number = (
                int(column.text) + 1 if column is not None and column.text is not None else None
            )
        except ValueError:
            return None, None
        if row_number is None or column_number is None or row_number < 1 or column_number < 1:
            return None, None
        return row_number, column_number

    @classmethod
    def _xlsx_picture_alt_text(cls, picture: ElementTree.Element | None) -> str | None:
        """优先读取 Excel 图片的说明文字，不把自动生成的形状名当语义。"""
        properties = cls._xml_descendant(picture, "cNvPr")
        if properties is None:
            return None
        description = properties.attrib.get("descr", "").strip()
        return description or None

    def _parse_spreadsheet_rows(
        self,
        rows: Iterable[tuple[int, Sequence[object]]],
        title: str,
        media_type: str,
        *,
        container: str,
        sheet_name: str,
        page_count: int,
    ) -> ParsedDocument:
        """把 CSV 等行迭代器转换为统一表格块。"""
        blocks: list[ParsedBlock] = []
        character_count = 0
        header: str | None = None
        for scanned_rows, (row_number, row) in enumerate(rows, start=1):
            if scanned_rows > self.limits.max_spreadsheet_rows:
                raise KnowledgeProcessingError("BUDGET_EXCEEDED")
            values = tuple(self._cell_text(value) for value in row)
            appended = self._append_spreadsheet_row(
                blocks,
                values,
                container=container,
                sheet_name=sheet_name,
                row_number=row_number,
                header=header,
            )
            if appended is not None:
                character_count += len(appended)
                if header is None:
                    header = appended
            self._require_parse_budget(blocks, character_count)
        return ParsedDocument(
            title,
            media_type,
            tuple(blocks),
            character_count,
            page_count=page_count,
        )

    def _append_spreadsheet_row(
        self,
        blocks: list[ParsedBlock],
        values: Sequence[str],
        *,
        container: str,
        sheet_name: str,
        row_number: int,
        header: str | None,
    ) -> str | None:
        """忽略全空行，并用首尾非空列生成可复现的单元格范围。"""
        non_empty = [index for index, value in enumerate(values) if value]
        if not non_empty:
            return None
        first = non_empty[0]
        last = non_empty[-1]
        row_text = " | ".join(values[first : last + 1]).strip()
        if not row_text:
            return None
        blocks.append(
            ParsedBlock(
                ordinal=len(blocks),
                kind="TABLE_ROW",
                text=row_text,
                title_path=(sheet_name,),
                locator=SpreadsheetRangeLocator.model_validate(
                    {
                        "kind": "SPREADSHEET_RANGE",
                        "container": container,
                        "sheetName": sheet_name,
                        "rowStart": row_number,
                        "rowEnd": row_number + 1,
                        "columnStart": first + 1,
                        "columnEnd": last + 2,
                    }
                ),
                context_text=header if header != row_text else None,
            )
        )
        return row_text

    def _parse_pptx(
        self,
        path: Path,
        workspace: Path,
        title: str,
        media_type: str,
        extract_images: bool,
    ) -> ParsedDocument:
        """解析 PPTX 的幻灯片文本、表格和图片，保留幻灯片位置。"""
        container = "PPTX"
        presentation = Presentation(str(path))
        if len(presentation.slides) > self.limits.max_office_units:
            raise KnowledgeProcessingError("BUDGET_EXCEEDED")
        blocks: list[ParsedBlock] = []
        images: list[ParsedImage] = []
        skipped_images = 0
        character_count = 0
        image_index = 0

        for slide_number, slide in enumerate(presentation.slides, start=1):
            title_shape = slide.shapes.title
            slide_title = (
                self._safe_label(title_shape.text)
                if title_shape is not None and title_shape.text.strip()
                else ""
            )
            title_path = (slide_title,) if slide_title else ()
            for shape_index, shape in enumerate(slide.shapes):
                if shape is title_shape:
                    title_text = shape.text.strip()
                    if title_text:
                        character_count += self._append_presentation_text(
                            blocks,
                            title_text,
                            (),
                            container,
                            slide_number,
                            shape_index,
                        )
                elif shape.has_table:
                    table_shape = cast(GraphicFrame, shape)
                    rows = table_shape.table.rows
                    header = self._presentation_row_text(rows[0].cells) if rows else ""
                    for row_index, row in enumerate(rows):
                        row_text = self._presentation_row_text(row.cells)
                        if not row_text:
                            continue
                        blocks.append(
                            ParsedBlock(
                                ordinal=len(blocks),
                                kind="TABLE_ROW",
                                text=row_text,
                                title_path=title_path,
                                locator=PresentationBlockLocator.model_validate(
                                    {
                                        "kind": "PRESENTATION_BLOCK",
                                        "container": container,
                                        "slideNumber": slide_number,
                                        "shapeIndex": shape_index,
                                        "rowIndex": row_index,
                                    }
                                ),
                                context_text=header if row_index > 0 else None,
                            )
                        )
                        character_count += len(row_text)
                elif shape.has_text_frame:
                    paragraphs = [
                        paragraph.text.strip()
                        for paragraph in shape.text_frame.paragraphs
                        if paragraph.text.strip()
                    ]
                    if paragraphs:
                        body = "\n".join(paragraphs)
                        character_count += self._append_presentation_text(
                            blocks,
                            body,
                            title_path,
                            container,
                            slide_number,
                            shape_index,
                        )

                if extract_images and shape.shape_type == MSO_SHAPE_TYPE.PICTURE:
                    if len(images) >= self.limits.max_images:
                        skipped_images += 1
                        image_index += 1
                        continue
                    picture = cast(Picture, shape)
                    image = self._image_normalizer.normalize(
                        picture.image.blob,
                        workspace,
                        container=container,
                        image_index=image_index,
                        slide_number=slide_number,
                        alt_text=shape.name.strip() or None,
                    )
                    image_index += 1
                    if image is None:
                        skipped_images += 1
                    else:
                        images.append(image)
                self._require_parse_budget(blocks, character_count)

        metadata_title = str(presentation.core_properties.title or "").strip()
        return ParsedDocument(
            self._safe_title(metadata_title) if metadata_title else title,
            media_type,
            tuple(blocks),
            character_count,
            page_count=max(1, len(presentation.slides)),
            images=tuple(images),
            skipped_image_count=skipped_images,
        )

    def _append_presentation_text(
        self,
        blocks: list[ParsedBlock],
        value: str,
        title_path: tuple[str, ...],
        container: str,
        slide_number: int,
        shape_index: int,
    ) -> int:
        """追加一个幻灯片文本形状并返回字符数。"""
        blocks.append(
            ParsedBlock(
                ordinal=len(blocks),
                kind="PARAGRAPH",
                text=value,
                title_path=title_path,
                locator=PresentationBlockLocator.model_validate(
                    {
                        "kind": "PRESENTATION_BLOCK",
                        "container": container,
                        "slideNumber": slide_number,
                        "shapeIndex": shape_index,
                        "rowIndex": None,
                    }
                ),
            )
        )
        return len(value)

    def _presentation_row_text(self, cells: Sequence[object]) -> str:
        """将 PPT 表格单元格规范化为稳定的竖线分隔文本。"""
        values = [self._cell_text(getattr(cell, "text", "")) for cell in cells]
        return " | ".join(values).strip(" |")

    def _cell_text(self, value: object) -> str:
        """把表格单元格值转为稳定文本，不执行公式。"""
        if value is None:
            return ""
        if isinstance(value, (datetime, date, time)):
            return value.isoformat()
        if isinstance(value, bool):
            return "TRUE" if value else "FALSE"
        return str(value).replace("\r\n", "\n").replace("\r", "\n").strip()

    def _require_parse_budget(self, blocks: Sequence[ParsedBlock], character_count: int) -> None:
        """在结构化文档遍历期间提前执行字符和块数量限制。"""
        if len(blocks) > self.limits.max_blocks or character_count > self.limits.max_characters:
            raise KnowledgeProcessingError("BUDGET_EXCEEDED")

    def _decode_csv(self, path: Path) -> str:
        """CSV 优先读取 UTF-8，兼容常见的 GB18030 导出文件。"""
        data = path.read_bytes()
        for encoding in ("utf-8-sig", "gb18030"):
            try:
                text = data.decode(encoding, errors="strict")
            except UnicodeDecodeError:
                continue
            if "\x00" in text:
                break
            if len(text) > self.limits.max_characters:
                raise KnowledgeProcessingError("BUDGET_EXCEEDED")
            return text
        raise KnowledgeProcessingError("PARSE_FAILED")

    def _validate(self, document: ParsedDocument) -> ParsedDocument:
        """解析后整体校验：至少有一个块或图片、字符数/块数/图片数不超限。

        空文档（如 PDF 只有不可提取的扫描页）在视觉阶段由图片填充。"""
        if not document.blocks and not document.images:
            raise KnowledgeProcessingError("PARSE_FAILED")
        if document.character_count > self.limits.max_characters:
            raise KnowledgeProcessingError("BUDGET_EXCEEDED")
        if len(document.blocks) > self.limits.max_blocks:
            raise KnowledgeProcessingError("BUDGET_EXCEEDED")
        if len(document.images) > self.limits.max_images:
            raise KnowledgeProcessingError("BUDGET_EXCEEDED")
        return document

    def _decode_text(self, path: Path) -> str:
        """读取 UTF-8（含 BOM）文本，拒绝含空字节的二进制文件和超大文本。

        空字节检测用于防止将压缩文件或可执行文件误当作文本解析。"""
        try:
            text = path.read_bytes().decode("utf-8-sig", errors="strict")
        except UnicodeDecodeError as error:
            raise KnowledgeProcessingError("PARSE_FAILED") from error
        if "\x00" in text:
            raise KnowledgeProcessingError("PARSE_FAILED")
        if len(text) > self.limits.max_characters:
            raise KnowledgeProcessingError("BUDGET_EXCEEDED")
        return text

    def _paragraph_spans(self, text: str) -> list[tuple[int, int]]:
        """按空行（连续两个换行符）切分段落区间，返回去空白后的起止偏移。

        单个换行不切分（保留段落内软换行）；两个以上换行视为段落边界。"""
        separators = list(re.finditer(r"(?:\r\n|\r|\n)[ \t]*(?:\r\n|\r|\n)+", text))
        spans: list[tuple[int, int]] = []
        start = 0
        for separator in separators:
            span = self._trim_span(text, start, separator.start())
            if span is not None:
                spans.append(span)
            start = separator.end()
        span = self._trim_span(text, start, len(text))
        if span is not None:
            spans.append(span)
        return spans

    def _append_text_block(
        self,
        blocks: list[ParsedBlock],
        kind: str,
        text: str,
        start: int,
        end: int,
        title_path: list[str],
    ) -> None:
        """去空白后将文本区间追加为 PARAGRAPH 或 CODE 块。

        空白行（段落间的空行）在此被丢弃，不在最终块列表中。"""
        span = self._trim_span(text, start, end)
        if span is None:
            return
        clean_start, clean_end = span
        blocks.append(
            ParsedBlock(
                ordinal=len(blocks),
                kind="CODE" if kind == "CODE" else "PARAGRAPH",
                text=text[clean_start:clean_end],
                title_path=tuple(title_path),
                locator=TextRangeLocator.model_validate(
                    {
                        "kind": "TEXT_RANGE",
                        "startOffset": clean_start,
                        "endOffset": clean_end,
                    }
                ),
            )
        )

    def _lines(self, text: str) -> list[_Line]:
        """将文本按行分割同时保留每行原始偏移和含换行符的结束位置。

        full_end 包含换行符长度，用于 Markdown 图片屏蔽时精确定位替换范围。"""
        lines: list[_Line] = []
        offset = 0
        for raw in text.splitlines(keepends=True):
            line_text = raw.rstrip("\r\n")
            end = offset + len(line_text)
            lines.append(_Line(line_text, offset, end, offset + len(raw)))
            offset += len(raw)
        if offset < len(text) or not lines:
            lines.append(_Line(text[offset:], offset, len(text), len(text)))
        return lines

    def _fence_end(self, lines: list[_Line], start: int, marker: str) -> int:
        """找代码围栏的闭合标记，未闭合则返回最后一行。

        marker 是围栏起始字符（` 或 ~），闭合必须使用相同字符。"""
        for index in range(start + 1, len(lines)):
            match = _FENCE.match(lines[index].text)
            if match and match.group(1)[0] == marker:
                return index
        return len(lines) - 1

    def _set_heading(self, title_path: list[str], level: int, value: str) -> None:
        """设置标题路径：清除当前级别及以下，填充缺失级别为「未命名章节」，上限 32 级。

        例如 level=3 时先清空 title_path 中 >=3 的项，再补齐到 2 级后追加新标题。"""
        heading = self._safe_label(value)
        del title_path[max(0, level - 1) :]
        while len(title_path) < level - 1:
            title_path.append("未命名章节")
        title_path.append(heading)
        del title_path[32:]

    def _table_cells(self, row: str) -> list[str]:
        """按 | 拆分表格行单元格并去除空白。"""
        return [cell.strip() for cell in row.strip().strip("|").split("|")]

    def _table_row_text(self, row: str) -> str:
        """将表格行转为 "cell1 | cell2" 格式，空单元格跳过。"""
        return " | ".join(cell for cell in self._table_cells(row) if cell)

    def _docx_row_text(self, cells: Iterable[_Cell]) -> str:
        """将 DOCX Table 行转为 "cell | cell" 格式文本。"""
        return " | ".join(cell.text.strip() for cell in cells if cell.text.strip())

    def _safe_title(self, value: str) -> str:
        """限制文档标题长度为 512 字符，空标题回退为「未命名文档」。"""
        normalized = Path(value).name.strip() or "未命名文档"
        return normalized[:512]

    def _safe_label(self, value: str) -> str:
        """压缩空白、限制章节标签为 128 字符，空值回退为「未命名章节」。"""
        normalized = re.sub(r"\s+", " ", value).strip() or "未命名章节"
        return normalized[:128]

    def _trim_span(self, text: str, start: int, end: int) -> tuple[int, int] | None:
        """去除首尾空白后返回 (start, end)；全空白区间返回 None。"""
        while start < end and text[start].isspace():
            start += 1
        while end > start and text[end - 1].isspace():
            end -= 1
        return (start, end) if start < end else None
