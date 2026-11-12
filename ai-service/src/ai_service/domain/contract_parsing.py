"""合同信息提取的受限解析与逐页 OCR；不读取业务库或自行选择模型。"""

from __future__ import annotations

import asyncio
import hashlib
import math
from concurrent.futures import ProcessPoolExecutor
from contextlib import closing
from dataclasses import replace
from functools import partial

import pypdfium2 as pdfium
from PIL import Image, ImageOps
from pypdf import PdfReader

from ai_service.contracts.contract_review import ContractReviewExecutionRequest
from ai_service.contracts.retrieval import ImageLocator, PdfPageLocator
from ai_service.domain.knowledge_execution import DownloadedKnowledgeSource
from ai_service.providers.errors import (
    ModelConfigurationError,
    ModelProviderAuthenticationError,
    ModelProviderBudgetError,
    ModelProviderError,
    ModelProviderRateLimitError,
)
from ai_service.providers.metering import ExecutionUsageTracker, estimate_tokens
from ai_service.providers.vision_protocol import VisionInput, VisionProviderFactory
from ai_service.rag.models import (
    KnowledgeProcessingError,
    ParsedBlock,
    ParsedDocument,
    ParsedImage,
)
from ai_service.rag.parser_limits import ParserLimits
from ai_service.rag.parsers import KnowledgeDocumentParser


class ContractOcrError(RuntimeError):
    """区分缺少授权视觉模型和视觉识别失败。"""

    def __init__(self, error_code: str) -> None:
        super().__init__(error_code)
        self.error_code = error_code


class ContractSourceParser:
    """将原生文本及扫描图片合并成一个完整、有序的合同文档。"""

    def __init__(self, limits: ParserLimits, factory: VisionProviderFactory) -> None:
        self._limits = limits
        self._factory = factory

    async def parse(
        self,
        source: DownloadedKnowledgeSource,
        request: ContractReviewExecutionRequest,
        tracker: ExecutionUsageTracker,
    ) -> ParsedDocument:
        """解析器运行在可终止进程中，避免损坏文档阻塞请求或共享 PDFium 线程。"""
        pool = ProcessPoolExecutor(max_workers=1)
        try:
            document = await asyncio.wait_for(
                asyncio.get_running_loop().run_in_executor(
                    pool, _parse_source, source, request.file_name, self._limits
                ),
                timeout=60,
            )
        except BaseException:
            pool.terminate_workers()
            raise
        finally:
            pool.shutdown(wait=True, cancel_futures=True)
        if document.truncated or document.skipped_image_count:
            raise KnowledgeProcessingError("BUDGET_EXCEEDED")
        if source.media_type.endswith("wordprocessingml.document") and document.blocks:
            # 正常 DOCX 常带 logo、签章等小图；已有正文时只识别接近页面尺寸的图片。
            document = replace(
                document,
                images=tuple(image for image in document.images if _is_page_sized(image)),
            )
        if len(document.images) > 20:
            raise KnowledgeProcessingError("BUDGET_EXCEEDED")
        if not document.images:
            return document
        profile = request.vision_profile
        if profile is None:
            raise ContractOcrError("CONTRACT_OCR_REQUIRED")
        provider = self._factory.provider_for(profile)
        blocks = list(document.blocks)
        characters = document.character_count
        for image in document.images:
            with Image.open(image.path) as opened:
                width, height = opened.size
            # 图像计量只作预算和缺失回执时的估算，不冒充供应商实际用量。
            estimated = 85 + 170 * math.ceil(width / 512) * math.ceil(height / 512)
            context = "逐字识别这张合同页面的全部正文、表格、页眉和页脚。"
            estimated += estimate_tokens(context) + 256
            if estimated > profile.max_input_tokens:
                raise ModelProviderBudgetError("The vision input budget is exhausted")
            output_limit = min(
                request.vision_max_output_tokens, tracker.remaining_tokens - estimated
            )
            if output_limit < 1:
                raise ModelProviderBudgetError("The execution token budget is exhausted")
            call_id = tracker.begin("VISION", profile.profile, estimated, output_limit)
            try:
                batch = await provider.analyze(
                    profile,
                    (
                        VisionInput(
                            input_id=image.media_id,
                            path=image.path,
                            media_type=image.media_type,
                            context=context,
                            ocr_only=True,
                            max_output_tokens=output_limit,
                            estimated_input_tokens=estimated,
                            report_usage=partial(tracker.finish, call_id),
                        ),
                    ),
                )
            except (
                ModelConfigurationError,
                ModelProviderAuthenticationError,
                ModelProviderRateLimitError,
            ):
                tracker.reject(call_id)
                raise
            except ModelProviderError as error:
                tracker.reject(call_id)
                if any(item.call_id == call_id for item in tracker.items):
                    raise ContractOcrError("CONTRACT_OCR_FAILED") from error
                raise
            if len(batch.outputs) != 1 or batch.outputs[0].input_id != image.media_id:
                raise ContractOcrError("CONTRACT_OCR_FAILED")
            text = batch.outputs[0].text.strip()
            if not text or "[无法识别]" in text:
                raise ContractOcrError("CONTRACT_OCR_FAILED")
            characters += len(text)
            if characters > self._limits.max_characters or len(blocks) >= self._limits.max_blocks:
                raise KnowledgeProcessingError("BUDGET_EXCEEDED")
            locator = image.locator
            blocks.append(
                ParsedBlock(
                    ordinal=len(blocks),
                    kind="PARAGRAPH",
                    text=text,
                    title_path=image.title_path,
                    locator=(
                        PdfPageLocator.model_validate(
                            {"kind": "PDF_PAGE", "pageNumber": locator.page_number}
                        )
                        if locator.page_number is not None
                        else locator
                    ),
                )
            )
        if source.media_type == "application/pdf":
            blocks.sort(
                key=lambda block: (
                    block.locator.page_number if isinstance(block.locator, PdfPageLocator) else 0
                )
            )
        return replace(document, blocks=tuple(blocks), character_count=characters)


def _is_page_sized(image: ParsedImage) -> bool:
    """过滤正文旁的小图，只保留可能承载整页合同文字的 DOCX 图片。"""
    with Image.open(image.path) as opened:
        width, height = opened.size
    return min(width, height) >= 300 and width * height >= 250_000


def _save_image(
    image: Image.Image,
    source: DownloadedKnowledgeSource,
    index: int,
    limits: ParserLimits,
    *,
    page_number: int | None = None,
) -> ParsedImage:
    """规范化方向和色彩，限制解码像素与输出字节。"""
    if image.width * image.height > limits.max_image_pixels:
        raise KnowledgeProcessingError("BUDGET_EXCEEDED")
    directory = source.workspace_path / "contract-images"
    directory.mkdir(exist_ok=True)
    path = directory / f"{index:04d}.png"
    with ImageOps.exif_transpose(image) as oriented, oriented.convert("RGB") as normalized:
        normalized.save(path, format="PNG")
    if path.stat().st_size > limits.max_image_bytes:
        raise KnowledgeProcessingError("BUDGET_EXCEEDED")
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    locator = ImageLocator.model_validate(
        {
            "kind": "IMAGE",
            "container": "PDF" if page_number is not None else "DOCX",
            "imageIndex": index,
            "pageNumber": page_number,
            "slideNumber": None,
            "startOffset": None,
            "endOffset": None,
            "sheetName": None,
            "rowNumber": None,
            "columnNumber": None,
            "contentHash": digest,
            "mediaType": "image/png",
        }
    )
    return ParsedImage(str(index), path, "image/png", digest, locator, ())


def _parse_source(
    source: DownloadedKnowledgeSource,
    name: str,
    limits: ParserLimits,
) -> ParsedDocument:
    """子进程入口：原生 PDF 保留文本，图像页整页渲染，防止遗漏拼接扫描图。"""
    if source.media_type.startswith("image/"):
        with Image.open(source.path) as image:
            if image.format not in {"PNG", "JPEG"}:
                raise KnowledgeProcessingError("DOCUMENT_UNSUPPORTED")
            if getattr(image, "n_frames", 1) != 1:
                raise KnowledgeProcessingError("DOCUMENT_UNSUPPORTED")
            normalized = _save_image(image, source, 0, limits)
        return ParsedDocument(name, source.media_type, (), 0, images=(normalized,))
    if source.media_type != "application/pdf":
        return asyncio.run(
            KnowledgeDocumentParser(limits).parse(
                source, name, "python-parser-v1", extract_images=True
            )
        )
    reader = PdfReader(source.path, strict=False)
    if reader.is_encrypted and reader.decrypt("") == 0:
        raise KnowledgeProcessingError("DOCUMENT_UNSUPPORTED")
    if len(reader.pages) > limits.max_pdf_pages:
        raise KnowledgeProcessingError("BUDGET_EXCEEDED")
    blocks: list[ParsedBlock] = []
    images: list[ParsedImage] = []
    characters = 0
    with pdfium.PdfDocument(source.path) as rendered:
        for index, page in enumerate(reader.pages):
            text = (page.extract_text() or "").strip()
            contents = page.get_contents()
            has_drawing_commands = contents is not None and bool(contents.get_data().strip())
            if (text and not page.images) or (not text and not has_drawing_commands):
                if text:
                    characters += len(text)
                    blocks.append(
                        ParsedBlock(
                            len(blocks),
                            "PARAGRAPH",
                            text,
                            (),
                            PdfPageLocator.model_validate(
                                {"kind": "PDF_PAGE", "pageNumber": index + 1}
                            ),
                        )
                    )
            else:
                if len(images) >= min(20, limits.max_images):
                    raise KnowledgeProcessingError("BUDGET_EXCEEDED")
                with closing(rendered[index]) as pdf_page:
                    width, height = pdf_page.get_size()
                    scale = min(2.0, 2000 / max(width, height))
                    with closing(pdf_page.render(scale=scale)) as bitmap, bitmap.to_pil() as image:
                        images.append(
                            _save_image(image, source, index, limits, page_number=index + 1)
                        )
            if characters > limits.max_characters or len(blocks) > limits.max_blocks:
                raise KnowledgeProcessingError("BUDGET_EXCEEDED")
    return ParsedDocument(
        name,
        source.media_type,
        tuple(blocks),
        characters,
        page_count=len(reader.pages),
        images=tuple(images),
    )
