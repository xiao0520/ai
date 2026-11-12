"""Java 控制面调用的合同审查流式入口。"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import shutil
import tempfile
from collections.abc import AsyncGenerator
from pathlib import Path
from typing import Annotated, cast

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, ValidationError
from qdrant_client import AsyncQdrantClient
from starlette.datastructures import UploadFile

from ai_service.api.chat import require_internal_request
from ai_service.contracts.chat import KeepalivePayload
from ai_service.contracts.contract_review import (
    ContractReviewCompletedPayload,
    ContractReviewErrorCode,
    ContractReviewExecutionRequest,
    ContractReviewFailedPayload,
    ContractReviewProgressPayload,
    ContractReviewUsagePayload,
)
from ai_service.domain.contract_parsing import ContractSourceParser
from ai_service.domain.contract_review import ContractReviewError, ContractReviewGraph
from ai_service.domain.knowledge_execution import DownloadedKnowledgeSource
from ai_service.infra.knowledge_scope_client import (
    KnowledgeScopeClient,
    KnowledgeScopeClientError,
)
from ai_service.infra.settings import Settings, get_settings
from ai_service.providers.embedding_protocol import EmbeddingProviderFactory
from ai_service.providers.errors import (
    ModelConfigurationError,
    ModelProviderAuthenticationError,
    ModelProviderBudgetError,
    ModelProviderError,
    ModelProviderRateLimitError,
    ModelProviderRequestError,
    ModelProviderTimeoutError,
)
from ai_service.providers.langchain_openai import LangChainOpenAiChatProvider
from ai_service.providers.rerank_protocol import RerankProviderFactory
from ai_service.rag.execution import (
    AuthorizedRetrievalChainError,
    KnowledgeRetrievalTestUseCase,
)
from ai_service.rag.parsers import KnowledgeDocumentParser
from ai_service.rag.retrieval import AuthorizedRetrievalError

router = APIRouter(prefix="/internal/ai/v1", tags=["internal-contract-review"])
LOGGER = logging.getLogger(__name__)


def get_contract_review_dependencies(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
) -> tuple[
    KnowledgeDocumentParser,
    KnowledgeRetrievalTestUseCase,
    LangChainOpenAiChatProvider,
]:
    """复用应用生命周期内的解析器、检索基础设施和结构化模型 Provider。"""
    retriever = KnowledgeRetrievalTestUseCase(
        cast(KnowledgeScopeClient, request.app.state.knowledge_scope_client),
        cast(EmbeddingProviderFactory, request.app.state.embedding_provider_factory),
        cast(RerankProviderFactory, request.app.state.rerank_provider_factory),
        cast(AsyncQdrantClient, request.app.state.qdrant_client),
        timeout_seconds=settings.qdrant_timeout_seconds,
        max_concurrency=settings.rag_embedding_max_concurrency,
    )
    return (
        cast(KnowledgeDocumentParser, request.app.state.document_parser),
        retriever,
        cast(LangChainOpenAiChatProvider, request.app.state.structured_model_provider),
    )


@router.post("/contract-reviews/stream", response_model=None)
async def stream_contract_review(
    request: Request,
    _: Annotated[None, Depends(require_internal_request)],
    dependencies: Annotated[
        tuple[
            KnowledgeDocumentParser,
            KnowledgeRetrievalTestUseCase,
            LangChainOpenAiChatProvider,
        ],
        Depends(get_contract_review_dependencies),
    ],
    settings: Annotated[Settings, Depends(get_settings)],
) -> StreamingResponse | JSONResponse:
    """接收 Java 已鉴权的合同文件和执行快照，返回阶段进度与唯一终态。"""
    try:
        async with request.form(
            max_files=1,
            max_fields=2,
            max_part_size=settings.artifact_max_source_bytes,
        ) as form:
            raw_metadata = form.get("request")
            uploaded_file = form.get("file")
            if not isinstance(raw_metadata, str) or not isinstance(uploaded_file, UploadFile):
                return _validation_response()
            body = ContractReviewExecutionRequest.model_validate_json(raw_metadata)
            if uploaded_file.content_type != body.media_type:
                return _validation_response(body.request_id)
            content = await uploaded_file.read(settings.artifact_max_source_bytes + 1)
    except ValidationError, ValueError, HTTPException:
        return _validation_response()

    if not content or len(content) > settings.artifact_max_source_bytes:
        return _validation_response(body.request_id)

    workspace = Path(
        tempfile.mkdtemp(prefix="contract-review-", dir=settings.artifact_temp_directory)
    )
    suffix = {"application/pdf": ".pdf", "image/png": ".png", "image/jpeg": ".jpg"}.get(
        body.media_type, ".docx"
    )
    source_path = workspace / f"source{suffix}"
    try:
        await asyncio.to_thread(source_path.write_bytes, content)
    except BaseException:
        await asyncio.to_thread(shutil.rmtree, workspace, True)
        raise
    source = DownloadedKnowledgeSource(
        path=source_path,
        workspace_path=workspace,
        media_type=body.media_type,
        size_bytes=len(content),
        content_hash=hashlib.sha256(content).hexdigest(),
    )
    parser, retriever, provider = dependencies
    graph = ContractReviewGraph(
        parser,
        retriever,
        provider,
        source,
        cast(
            ContractSourceParser | None, getattr(request.app.state, "contract_source_parser", None)
        ),
    )
    return StreamingResponse(
        _event_stream(body, request, graph, workspace, settings),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


async def _event_stream(
    body: ContractReviewExecutionRequest,
    request: Request,
    graph: ContractReviewGraph,
    workspace: Path,
    settings: Settings,
) -> AsyncGenerator[str]:
    """Graph 等待期间发送保活，并保证成功或失败事件只出现一次。"""
    stream = graph.stream(body)
    pending_item: (
        asyncio.Task[
            tuple[
                str,
                ContractReviewProgressPayload
                | ContractReviewCompletedPayload
                | ContractReviewUsagePayload,
            ]
        ]
        | None
    ) = None
    deadline = asyncio.get_running_loop().time() + body.execution_policy.timeout_seconds
    last_keepalive = asyncio.get_running_loop().time()
    last_usage = graph.usage
    try:
        while True:
            if await request.is_disconnected():
                return
            if pending_item is None:
                pending_item = asyncio.create_task(anext(stream))
            try:
                event_name, payload = await asyncio.wait_for(
                    asyncio.shield(pending_item),
                    timeout=min(
                        0.5,
                        settings.provider_keepalive_seconds,
                        max(0, deadline - asyncio.get_running_loop().time()),
                    ),
                )
                pending_item = None
                yield _encode_event(event_name, payload)
                if event_name == "completed":
                    return
            except TimeoutError:
                if asyncio.get_running_loop().time() >= deadline:
                    raise ModelProviderTimeoutError("Contract review timed out") from None
                if graph.usage != last_usage:
                    last_usage = graph.usage
                    yield _encode_event(
                        "usage",
                        ContractReviewUsagePayload(
                            request_id=body.request_id, execution_usage=graph.usage
                        ),
                    )
                if (
                    asyncio.get_running_loop().time() - last_keepalive
                    >= settings.provider_keepalive_seconds
                ):
                    yield _encode_event("keepalive", KeepalivePayload(request_id=body.request_id))
                    last_keepalive = asyncio.get_running_loop().time()
    except asyncio.CancelledError:
        raise
    except Exception as error:  # noqa: BLE001 -- SSE 边界统一收口，日志不能附带含原文的异常堆栈。
        # 先停止在途调用，再读取最终计量，防止超时后的回执落在失败快照之外。
        if pending_item is not None:
            if not pending_item.done():
                pending_item.cancel()
            await asyncio.gather(pending_item, return_exceptions=True)
            pending_item = None
        error_code = _error_code(error)
        # 校验异常可能包含原文片段，日志只保留标识和异常类型。
        LOGGER.warning(
            "合同审查失败：request_id=%s, error_code=%s, error_type=%s",
            body.request_id,
            error_code,
            type(error).__name__,
        )
        yield _encode_event(
            "failed",
            ContractReviewFailedPayload(
                request_id=body.request_id,
                error_code=error_code,
                execution_usage=graph.usage,
            ),
        )
    finally:
        if pending_item is not None:
            if not pending_item.done():
                pending_item.cancel()
            await asyncio.gather(pending_item, return_exceptions=True)
        try:
            await stream.aclose()
        finally:
            await asyncio.to_thread(shutil.rmtree, workspace, True)


def _error_code(error: Exception) -> ContractReviewErrorCode:
    """把内部异常收敛为合同审查协议允许的稳定错误码。"""
    if isinstance(error, ContractReviewError):
        return error.error_code
    if isinstance(error, KnowledgeScopeClientError):
        if error.error_code in {"AUTHORIZATION", "SCOPE_EXPIRED", "INDEX_MISMATCH"}:
            return "KNOWLEDGE_ACCESS_DENIED"
        return "KNOWLEDGE_NOT_READY"
    if isinstance(error, (AuthorizedRetrievalChainError, AuthorizedRetrievalError)):
        if str(error) in {"AUTHORIZATION", "SCOPE_EXPIRED", "INDEX_MISMATCH"}:
            return "KNOWLEDGE_ACCESS_DENIED"
        return "KNOWLEDGE_NOT_READY"
    if isinstance(error, (ModelConfigurationError, ModelProviderAuthenticationError)):
        return "MODEL_UNAVAILABLE"
    if isinstance(error, ModelProviderRateLimitError):
        return "RATE_LIMITED"
    if isinstance(error, ModelProviderTimeoutError):
        return "TIMEOUT"
    if isinstance(error, ModelProviderBudgetError):
        return "BUDGET_EXCEEDED"
    if isinstance(error, ModelProviderRequestError):
        return "VALIDATION"
    if isinstance(error, ModelProviderError):
        return "INTERNAL"
    return "INTERNAL"


def _validation_response(request_id: str = "unknown") -> JSONResponse:
    """multipart 或执行快照无效时返回不含内部细节的 422。"""
    return JSONResponse(
        status_code=422,
        content={"requestId": request_id, "errorCode": "VALIDATION"},
    )


def _encode_event(event_name: str, payload: BaseModel) -> str:
    """编码 Java SSE 客户端使用的单行 JSON 事件。"""
    return f"event: {event_name}\ndata: {payload.model_dump_json(by_alias=True)}\n\n"
