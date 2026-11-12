from typing import Any

from arkruntime.types.multimodal_embedding import (
    EmbeddingInputParam,
    EncodingFormat,
    MultiModalEmbeddingResponse,
)

class AsyncMultimodalEmbeddings:
    async def create(
        self,
        *,
        model: str,
        input: list[EmbeddingInputParam],
        encoding_format: EncodingFormat | None = ...,
        dimensions: int | None = ...,
        timeout: float | Any | None = ...,
    ) -> MultiModalEmbeddingResponse: ...
