from enum import Enum
from typing import NotRequired, TypedDict

class EmbeddingInputType(str, Enum):
    text = "text"
    image_url = "image_url"
    video_url = "video_url"

class EncodingFormat(str, Enum):
    base64 = "base64"
    float = "float"

class EmbeddingInputParam(TypedDict):
    type: EmbeddingInputType
    text: NotRequired[str | None]

class EmbeddingDataItem:
    embedding: list[float] | str

class EmbeddingUsage:
    prompt_tokens: int
    total_tokens: int

class MultiModalEmbeddingResponse:
    data: EmbeddingDataItem
    usage: EmbeddingUsage
