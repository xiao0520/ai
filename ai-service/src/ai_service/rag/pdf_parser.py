"""PDF 格式解析器；其他格式不依赖 PDF 实现细节。"""

from pathlib import Path

from pypdf import PdfReader

from ai_service.contracts.retrieval import PdfPageLocator
from ai_service.rag.image_normalizer import ImageNormalizer
from ai_service.rag.models import KnowledgeProcessingError, ParsedBlock, ParsedDocument, ParsedImage
from ai_service.rag.parser_limits import ParserLimits


class PdfDocumentParser:
    """按页提取 PDF 文本和可选图片，并执行页数与图片预算限制。"""

    def __init__(self, limits: ParserLimits, image_normalizer: ImageNormalizer) -> None:
        self._limits = limits
        self._image_normalizer = image_normalizer

    def parse(
        self,
        path: Path,
        workspace: Path,
        title: str,
        media_type: str,
        extract_images: bool,
    ) -> ParsedDocument:
        """解析 PDF；加密文件只尝试空密码，无法读取时拒绝处理。"""
        reader = PdfReader(path, strict=False)
        if reader.is_encrypted:
            try:
                if reader.decrypt("") == 0:
                    raise KnowledgeProcessingError("DOCUMENT_UNSUPPORTED")
            except KnowledgeProcessingError:
                raise
            except Exception as error:
                raise KnowledgeProcessingError("DOCUMENT_UNSUPPORTED") from error
        if len(reader.pages) > self._limits.max_pdf_pages:
            raise KnowledgeProcessingError("BUDGET_EXCEEDED")

        blocks: list[ParsedBlock] = []
        images: list[ParsedImage] = []
        skipped_images = 0
        character_count = 0
        image_index = 0
        for page_number, page in enumerate(reader.pages, start=1):
            page_text = (page.extract_text() or "").strip()
            if page_text:
                character_count += len(page_text)
                blocks.append(
                    ParsedBlock(
                        ordinal=len(blocks),
                        kind="PARAGRAPH",
                        text=page_text,
                        title_path=(),
                        locator=PdfPageLocator.model_validate(
                            {"kind": "PDF_PAGE", "pageNumber": page_number}
                        ),
                    )
                )
            if extract_images:
                for image_file in page.images:
                    if len(images) >= self._limits.max_images:
                        skipped_images += 1
                        image_index += 1
                        continue
                    image = self._image_normalizer.normalize(
                        image_file.data,
                        workspace,
                        container="PDF",
                        image_index=image_index,
                        page_number=page_number,
                    )
                    image_index += 1
                    if image is None:
                        skipped_images += 1
                    else:
                        images.append(image)
        if not blocks and not images:
            raise KnowledgeProcessingError("DOCUMENT_UNSUPPORTED")
        metadata_title = ""
        if reader.metadata is not None and reader.metadata.title:
            metadata_title = str(reader.metadata.title).strip()
        document = ParsedDocument(
            self._safe_title(metadata_title) if metadata_title else title,
            media_type,
            tuple(blocks),
            character_count,
            page_count=len(reader.pages),
            skipped_block_count=len(reader.pages) - len(blocks),
            images=tuple(images),
            skipped_image_count=skipped_images,
        )
        return self._validate(document)

    def _validate(self, document: ParsedDocument) -> ParsedDocument:
        if document.character_count > self._limits.max_characters:
            raise KnowledgeProcessingError("BUDGET_EXCEEDED")
        if len(document.blocks) > self._limits.max_blocks:
            raise KnowledgeProcessingError("BUDGET_EXCEEDED")
        return document

    @staticmethod
    def _safe_title(value: str) -> str:
        normalized = " ".join(value.split()).strip()
        return normalized[:512] or "未命名文档"
