"""Office、Markdown 和 PDF 共用的安全图片规范化。"""

import hashlib
import io
from pathlib import Path

from PIL import Image, UnidentifiedImageError

from ai_service.contracts.retrieval import ImageLocator
from ai_service.rag.models import ParsedImage
from ai_service.rag.parser_limits import ParserLimits


class ImageNormalizer:
    """验证图片预算并统一转为 PNG，保留精确来源定位。"""

    def __init__(self, limits: ParserLimits) -> None:
        self._limits = limits

    def normalize(
        self,
        raw: bytes,
        workspace: Path,
        *,
        container: str,
        image_index: int,
        page_number: int | None = None,
        slide_number: int | None = None,
        start_offset: int | None = None,
        end_offset: int | None = None,
        sheet_name: str | None = None,
        row_number: int | None = None,
        column_number: int | None = None,
        title_path: tuple[str, ...] = (),
        alt_text: str | None = None,
    ) -> ParsedImage | None:
        """返回规范化图片；空内容、超限或无法解码时返回 None。"""
        if not raw or len(raw) > self._limits.max_image_bytes:
            return None
        try:
            with Image.open(io.BytesIO(raw)) as source:
                width, height = source.size
                if width < 1 or height < 1 or width * height > self._limits.max_image_pixels:
                    return None
                source.load()
                normalized = source.convert("RGBA" if "A" in source.getbands() else "RGB")
                output = io.BytesIO()
                normalized.save(output, format="PNG", optimize=True)
        except Image.DecompressionBombError, UnidentifiedImageError, OSError, ValueError:
            return None

        data = output.getvalue()
        if len(data) > self._limits.max_image_bytes:
            return None
        content_hash = hashlib.sha256(data).hexdigest()
        media_id = hashlib.sha256(f"{container}:{image_index}:{content_hash}".encode()).hexdigest()
        image_directory = workspace / "images"
        image_directory.mkdir(parents=True, exist_ok=True)
        image_path = image_directory / f"{image_index:06d}-{content_hash[:16]}.png"
        image_path.write_bytes(data)
        locator = ImageLocator.model_validate(
            {
                "kind": "IMAGE",
                "container": container,
                "imageIndex": image_index,
                "pageNumber": page_number,
                "slideNumber": slide_number,
                "startOffset": start_offset,
                "endOffset": end_offset,
                "sheetName": sheet_name,
                "rowNumber": row_number,
                "columnNumber": column_number,
                "contentHash": content_hash,
                "mediaType": "image/png",
            }
        )
        return ParsedImage(
            media_id=media_id,
            path=image_path,
            media_type="image/png",
            content_hash=content_hash,
            locator=locator,
            title_path=title_path,
            alt_text=alt_text,
        )
