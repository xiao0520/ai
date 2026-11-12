from typing import Any

from arkruntime.resources.multimodal_embeddings import AsyncMultimodalEmbeddings

class AsyncArk:
    multimodal_embeddings: AsyncMultimodalEmbeddings
    def __init__(
        self,
        *,
        api_key: str | None = ...,
        base_url: str = ...,
        timeout: float | Any = ...,
        max_retries: int = ...,
    ) -> None: ...
    async def close(self) -> None: ...
