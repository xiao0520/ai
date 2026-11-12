"""文档解析资源限制；各格式解析器共享同一组上限。"""

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class ParserLimits:
    """限制解析输出规模，避免压缩文档、图片或异常 PDF 耗尽 Worker。"""

    max_characters: int
    max_blocks: int
    max_pdf_pages: int
    max_office_units: int = 2_000
    max_spreadsheet_rows: int = 200_000
    max_images: int = 500
    max_image_bytes: int = 20_971_520
    max_image_pixels: int = 40_000_000
