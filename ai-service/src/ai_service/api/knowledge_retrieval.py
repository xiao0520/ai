"""Java 控制面调用的知识检索诊断接口。

本模块提供知识库检索调试端点，用于验证索引配置、检索流程和召回效果：
  - 认证 — 复用 chat.py 的 require_internal_request 依赖。
  - 契约 — RetrievalTestRequest 包含检索参数和候选分页。
  - 容错 — 按异常类型映射为稳定错误码，区分重试和非重试场景。
  - 会话 — 请求级无状态，不写入持久化存储。
"""

from __future__ import annotations

import asyncio
import logging
from typing import Annotated, cast

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse
from pydantic import ValidationError
from qdrant_client import AsyncQdrantClient

from ai_service.api.chat import require_internal_request
from ai_service.contracts.rag_types import ErrorCode, InternalError
from ai_service.contracts.retrieval import RetrievalTestRequest, RetrievalTestResponse
from ai_service.infra.knowledge_scope_client import (
    KnowledgeScopeClient,
    KnowledgeScopeClientError,
)
from ai_service.infra.settings import Settings, get_settings
from ai_service.providers.embedding_protocol import EmbeddingProviderFactory
from ai_service.providers.errors import (
    ModelConfigurationError,
    ModelProviderAuthenticationError,
    ModelProviderError,
    ModelProviderRateLimitError,
    ModelProviderRequestError,
    ModelProviderTimeoutError,
)
from ai_service.providers.rerank_protocol import RerankProviderFactory
from ai_service.rag.context import ContextSelectionError
from ai_service.rag.execution import (
    AuthorizedRetrievalChainError,
    KnowledgeRetrievalTestUseCase,
)
from ai_service.rag.reranking import GroupedRerankError
from ai_service.rag.retrieval import AuthorizedRetrievalError

# ============================================================================
# 路由与依赖注入
# ============================================================================

router = APIRouter(prefix="/internal/ai/v1/knowledge", tags=["internal-rag"])
LOGGER = logging.getLogger(__name__)


def get_retrieval_test_use_case(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
) -> KnowledgeRetrievalTestUseCase:
    """从应用生命周期取共享客户端，为本次请求创建无状态检索用例。

    依赖组件均从 app.state 获取（启动时注入）：
      - KnowledgeScopeClient — 通过内部 HTTP 查询 Java 授权的知识库范围。
      - EmbeddingProviderFactory — 向量化模型工厂（支持 vendor 多路由）。
      - RerankProviderFactory — 重排模型工厂。
      - AsyncQdrantClient — Qdrant 向量库异步客户端。
    """
    return KnowledgeRetrievalTestUseCase(
        cast(KnowledgeScopeClient, request.app.state.knowledge_scope_client),
        cast(EmbeddingProviderFactory, request.app.state.embedding_provider_factory),
        cast(RerankProviderFactory, request.app.state.rerank_provider_factory),
        cast(AsyncQdrantClient, request.app.state.qdrant_client),
        timeout_seconds=settings.qdrant_timeout_seconds,
        max_concurrency=settings.rag_embedding_max_concurrency,
    )


# ============================================================================
# HTTP 端点
# ============================================================================


@router.post("/retrieval-tests", response_model=None)
async def test_knowledge_retrieval(
    request: Request,
    _: Annotated[None, Depends(require_internal_request)],
    use_case: Annotated[
        KnowledgeRetrievalTestUseCase, Depends(get_retrieval_test_use_case)
    ],
) -> RetrievalTestResponse | JSONResponse:
    """执行真实检索链；Java 已创建授权范围并仍负责候选复核与预算结算。

    处理流程：
      1. 解析原始请求体 → RetrievalTestRequest（大小上限 2MB）。
      2. 调用 use_case.execute 执行检索流水线。
      3. 异常映射为 InternalError 并返回对应 HTTP 状态码。

    注意：此端点仅用于调试，不写入缓存、不计费、不触发候选复核。
    """
    # --- 请求体解析与大小校验 ---
    raw_body = await request.body()
    if not raw_body or len(raw_body) > 2 * 1024 * 1024:
        return _error_response("unknown", "VALIDATION", False, 422)
    try:
        body = RetrievalTestRequest.model_validate_json(raw_body)
    except ValidationError:
        return _error_response("unknown", "VALIDATION", False, 422)

    # --- 执行检索 ---
    try:
        return await use_case.execute(body)
    except asyncio.CancelledError:
        raise

    # --- 异常映射 ---
    except Exception as error:
        code, retryable, status = _map_error(error)
        # INTERNAL 异常打印完整堆栈，已知异常仅记录摘要
        if code == "INTERNAL":
            LOGGER.exception(
                "知识检索测试发生未分类异常：request_id=%s, error_type=%s",
                body.request_id,
                type(error).__name__,
            )
        else:
            LOGGER.warning(
                "知识检索测试失败：request_id=%s, error_code=%s, error_type=%s",
                body.request_id,
                code,
                type(error).__name__,
            )
        return _error_response(body.request_id, code, retryable, status)


# ============================================================================
# 错误响应构造
# ============================================================================


def _error_response(
    request_id: str,
    error_code: ErrorCode,
    retryable: bool,
    status: int,
) -> JSONResponse:
    """输出不携带解析细节、上游正文或内部地址的机器错误。

    返回的 InternalError 仅包含 requestId、errorCode 和 retryable，
    不暴露内部栈信息或上游错误正文，符合安全性要求。
    """
    payload = InternalError.model_validate(
        {
            "requestId": request_id,
            "errorCode": error_code,
            "retryable": retryable,
        }
    )
    return JSONResponse(
        status_code=status,
        content=payload.model_dump(by_alias=True, mode="json"),
    )


# ============================================================================
# 异常映射
# ============================================================================


def _map_error(error: Exception) -> tuple[ErrorCode, bool, int]:
    """将 Java、向量库和模型异常归一化为机器契约错误。

    映射按异常来源分为三组：
      - Java 服务异常 — KnowledgeScopeClientError，透传其错误码和重试标记。
      - 模型 Provider 异常 — 各 ModelProvider* 错误，按模型不可用/限流/超时/参数分类。
      - 检索链异常 — 向量库、重排、上下文筛选的领域错误，提取错误码字符串映射。
    """
    # --- Java 服务异常 ---
    if isinstance(error, KnowledgeScopeClientError):
        return cast(ErrorCode, error.error_code), error.retryable, _status_for(error.error_code)

    # --- 模型 Provider 异常 ---
    if isinstance(error, ModelConfigurationError):
        return "MODEL_UNAVAILABLE", False, 503
    if isinstance(error, ModelProviderAuthenticationError):
        return "PROVIDER_AUTHORIZATION", False, 503
    if isinstance(error, ModelProviderRateLimitError):
        return "RATE_LIMITED", True, 503
    if isinstance(error, ModelProviderTimeoutError):
        return "TIMEOUT", True, 503
    if isinstance(error, ModelProviderRequestError):
        return "VALIDATION", False, 422
    if isinstance(error, ModelProviderError):
        return "RERANK_UNAVAILABLE", True, 503

    # --- 检索链异常 ---
    if isinstance(
        error,
        (
            AuthorizedRetrievalChainError,
            AuthorizedRetrievalError,
            ContextSelectionError,
            GroupedRerankError,
        ),
    ):
        code = str(error)
        # VECTOR_UNAVAILABLE 可重试（向量库暂时不可用），其余不可
        if code in {
            "VALIDATION",
            "AUTHORIZATION",
            "SCOPE_EXPIRED",
            "INDEX_MISMATCH",
            "VECTOR_UNAVAILABLE",
        }:
            return cast(ErrorCode, code), code == "VECTOR_UNAVAILABLE", _status_for(code)

    # --- 未分类异常 → INTERNAL ---
    return "INTERNAL", True, 503


def _status_for(error_code: str) -> int:
    """将错误码映射为 HTTP 状态码。

    映射规则：
      - AUTHORIZATION → 403 Forbidden
      - SCOPE_EXPIRED / INDEX_MISMATCH → 409 Conflict（资源状态不一致）
      - VALIDATION → 422 Unprocessable Entity
      - 其余 → 503 Service Unavailable
    """
    if error_code in {"AUTHORIZATION", "PROVIDER_AUTHORIZATION"}:
        return 403
    if error_code in {"SCOPE_EXPIRED", "INDEX_MISMATCH"}:
        return 409
    if error_code == "VALIDATION":
        return 422
    return 503