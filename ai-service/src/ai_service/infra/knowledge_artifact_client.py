"""Python Worker 通过 Java 对象票据下载当前 attempt 绑定的源文件。

职责：
  - 申请一次性 ArtifactTicket（READ_SOURCE），严格校验票据内容与命令一致。
  - 流式下载源文件到隔离临时目录，边写边算 SHA-256。
  - 按命令中的 file_size_bytes 和 source_hash 核验下载结果。
  - 上传入库产物清单到 Java 受控对象存储。

关键约束：
  - 票据轮询有截止时间（deadline）和指数退避（最大 1s）。
  - 超出 artifact_max_source_bytes 立即中断下载。
  - 任何异常都会清理临时工作目录，防止磁盘泄漏。
"""

from __future__ import annotations

import asyncio
import hashlib
import shutil
import tempfile
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import BinaryIO
from urllib.parse import urlsplit

import httpx2
from pydantic import ValidationError

from ai_service.contracts.knowledge import (
    ArtifactTicket,
    ArtifactTicketRequest,
    ArtifactTicketResponse,
    ArtifactUploadResponse,
)
from ai_service.contracts.knowledge_kafka import KnowledgeDocumentTask, KnowledgeJobCommand
from ai_service.contracts.rag_artifacts import ArtifactManifest
from ai_service.contracts.rag_types import InternalError
from ai_service.domain.knowledge_execution import (
    DownloadedKnowledgeSource,
    KnowledgeExecutionContext,
    KnowledgeManifestError,
    KnowledgeSourceError,
    KnowledgeSourceFailureCode,
    PublishedKnowledgeManifest,
)
from ai_service.infra.internal_signature import signed_headers
from ai_service.infra.settings import Settings

_MAX_ERROR_BODY_BYTES = 64 * 1024


# ============================================================================
# 内部错误
# ============================================================================


class _RemoteTicketError(RuntimeError):
    """仅保存稳定错误码和可重试标识，不携带 Java 响应正文。"""

    def __init__(self, error_code: str, retryable: bool) -> None:
        super().__init__(error_code)
        self.error_code = error_code
        self.retryable = retryable


# ============================================================================
# Java 知识库产物客户端
# ============================================================================


class JavaKnowledgeArtifactClient:
    """使用统一签名申请短期票据，并将源文件流式写入隔离临时目录。"""

    def __init__(self, settings: Settings, http_client: httpx2.AsyncClient) -> None:
        self.settings = settings
        self.http_client = http_client

    async def download(
        self, command: KnowledgeJobCommand, context: KnowledgeExecutionContext
    ) -> DownloadedKnowledgeSource:
        """读取 INGESTION 任务绑定对象，并严格核对命令中的大小和哈希。"""
        if not isinstance(command.task, KnowledgeDocumentTask):
            raise KnowledgeSourceError("INDEX_MISMATCH")
        ticket = await self._issue_source_ticket(command, context)
        self._validate_ticket(command, ticket)
        workspace = Path(
            await asyncio.to_thread(
                tempfile.mkdtemp,
                prefix=f"mjn-ai-{command.job_id}-{command.generation_id}-",
                dir=self.settings.artifact_temp_directory,
            )
        )
        source_path = workspace / "source.bin"
        try:
            size_bytes, content_hash = await self._download_to_path(ticket, source_path)
            if (
                size_bytes != command.task.file_size_bytes
                or content_hash != command.task.source_hash
            ):
                raise KnowledgeSourceError("INDEX_MISMATCH")
            return DownloadedKnowledgeSource(
                path=source_path,
                workspace_path=workspace,
                media_type=command.task.media_type,
                size_bytes=size_bytes,
                content_hash=content_hash,
            )
        except BaseException:
            await self._remove_workspace(workspace)
            raise

    async def release(self, source: DownloadedKnowledgeSource) -> None:
        """清理当前 attempt 的临时目录；不存在时视为幂等成功。"""
        await self._remove_workspace(source.workspace_path)

    async def _issue_source_ticket(
        self, command: KnowledgeJobCommand, context: KnowledgeExecutionContext
    ) -> ArtifactTicket:
        """轮询 Java 获取一次性的文件下载票据，超时或重试耗尽后映射为 STALE_ATTEMPT。

        等待时长 = min(配置超时, 距 deadline 剩余秒数)，指数退避不超过 1s。"""
        task = command.task
        if not isinstance(task, KnowledgeDocumentTask):
            raise KnowledgeSourceError("INDEX_MISMATCH")
        wait_seconds = min(
            self.settings.artifact_ticket_ready_timeout_seconds,
            max(0.0, (command.deadline - datetime.now(UTC)).total_seconds()),
        )
        expires_at = time.monotonic() + wait_seconds
        delay = self.settings.artifact_ticket_retry_interval_seconds
        while True:
            try:
                return await self._issue_source_ticket_once(command, context, task)
            except _RemoteTicketError as error:
                retry = error.error_code == "STALE_ATTEMPT" or error.retryable
                remaining = expires_at - time.monotonic()
                if not retry or remaining <= 0:
                    raise KnowledgeSourceError(self._map_remote_error(error.error_code)) from error
                await asyncio.sleep(min(delay, remaining))
                delay = min(delay * 2, 1.0)

    async def _issue_source_ticket_once(
        self,
        command: KnowledgeJobCommand,
        context: KnowledgeExecutionContext,
        task: KnowledgeDocumentTask,
    ) -> ArtifactTicket:
        """单次签发请求：构造签名头 POST 到 Java /internal/knowledge/jobs/{id}/artifact-tickets。

        返回的票据包含 URL/Token/MaxBytes/ContentHash/ExpiresAt，用于后续流式下载。"""
        route = f"/internal/knowledge/jobs/{command.job_id}/artifact-tickets"
        url = self.settings.java_internal_base_url + route
        path = urlsplit(url).path
        request = ArtifactTicketRequest.model_validate(
            {
                "contractVersion": "rag-v1",
                "requestId": command.request_id,
                "attemptId": command.attempt_id,
                "generationId": command.generation_id,
                "workerId": context.worker_id,
                "operation": "READ_SOURCE",
                "objectId": task.oss_id,
            }
        )
        body = request.model_dump_json(by_alias=True).encode("utf-8")
        headers = dict(
            signed_headers(
                self.settings.required_internal_service_token(),
                self.settings.required_internal_signing_key().encode("utf-8"),
                "POST",
                path,
                body,
            )
        )
        headers["Content-Type"] = "application/json"
        headers["Accept"] = "application/json"
        try:
            response = await self.http_client.post(
                url,
                content=body,
                headers=headers,
            )
        except httpx2.RequestError as error:
            raise _RemoteTicketError("INTERNAL", True) from error
        if response.status_code != 200:
            # 错误正文只用于提取稳定错误码，不作为异常消息向上游透传。
            raise self._parse_remote_error(response.content)
        if len(response.content) > _MAX_ERROR_BODY_BYTES:
            raise KnowledgeSourceError("INDEX_MISMATCH")
        try:
            return ArtifactTicketResponse.model_validate_json(response.content).ticket
        except ValidationError as error:
            raise KnowledgeSourceError("INDEX_MISMATCH") from error

    def _validate_ticket(self, command: KnowledgeJobCommand, ticket: ArtifactTicket) -> None:
        """校验票据的 Operation/ObjectId/URL/ContentHash/MaxBytes 与命令完全一致且未过期。"""
        task = command.task
        if not isinstance(task, KnowledgeDocumentTask):
            raise KnowledgeSourceError("INDEX_MISMATCH")
        expected_path = f"/internal/knowledge/jobs/{command.job_id}/artifacts/{task.oss_id}"
        expected_url = self.settings.java_internal_base_url + expected_path
        if (
            ticket.operation != "READ_SOURCE"
            or ticket.object_id != task.oss_id
            or ticket.url != expected_url
            or ticket.content_hash != task.source_hash
            or ticket.max_bytes != task.file_size_bytes
            or ticket.max_bytes > self.settings.artifact_max_source_bytes
            or ticket.expires_at <= datetime.now(UTC)
        ):
            raise KnowledgeSourceError("INDEX_MISMATCH")

    async def _download_to_path(self, ticket: ArtifactTicket, path: Path) -> tuple[int, str]:
        """流式下载源文件到临时路径，边写边算 SHA-256，超出 MaxBytes 立即中断。"""
        digest = hashlib.sha256()
        size_bytes = 0
        output: BinaryIO | None = None
        try:
            async with self.http_client.stream(
                "GET",
                ticket.url,
                headers={
                    "Authorization": f"Bearer {ticket.token}",
                    "Accept": "application/octet-stream",
                },
            ) as response:
                if response.status_code != 200:
                    body = await self._read_limited(response)
                    remote = self._parse_remote_error(body)
                    raise KnowledgeSourceError(
                        self._map_remote_error(remote.error_code)
                    ) from remote
                # 响应头先做快速一致性检查，流式读取时仍按实际字节数限制。
                content_length = response.headers.get("Content-Length")
                if content_length is not None and (
                    not content_length.isdecimal() or int(content_length) != ticket.max_bytes
                ):
                    raise KnowledgeSourceError("INDEX_MISMATCH")
                output = await asyncio.to_thread(path.open, "xb")
                async for chunk in response.aiter_bytes(
                    chunk_size=self.settings.artifact_download_chunk_bytes
                ):
                    if not chunk:
                        continue
                    size_bytes += len(chunk)
                    if size_bytes > ticket.max_bytes:
                        raise KnowledgeSourceError("INDEX_MISMATCH")
                    digest.update(chunk)
                    written = await asyncio.to_thread(output.write, chunk)
                    if written != len(chunk):
                        raise KnowledgeSourceError("INTERNAL")
        except KnowledgeSourceError:
            raise
        except httpx2.TimeoutException as error:
            raise KnowledgeSourceError("TIMEOUT") from error
        except (httpx2.HTTPError, OSError) as error:
            raise KnowledgeSourceError("INTERNAL") from error
        finally:
            if output is not None:
                await asyncio.to_thread(output.close)
        return size_bytes, digest.hexdigest()

    async def _read_limited(self, response: httpx2.Response) -> bytes:
        """读取错误响应正文，超过 64KB 丢弃；防止下游日志污染和 DoS。"""
        body = bytearray()
        async for chunk in response.aiter_bytes():
            body.extend(chunk)
            if len(body) > _MAX_ERROR_BODY_BYTES:
                return b""
        return bytes(body)

    def _parse_remote_error(self, body: bytes) -> _RemoteTicketError:
        """解析 Java 返回的 InternalError JSON；空体或超限视为 INTERNAL 可重试。"""
        if not body or len(body) > _MAX_ERROR_BODY_BYTES:
            return _RemoteTicketError("INTERNAL", True)
        try:
            error = InternalError.model_validate_json(body)
        except ValidationError:
            return _RemoteTicketError("INTERNAL", True)
        return _RemoteTicketError(error.error_code, error.retryable)

    def _map_remote_error(self, error_code: str) -> KnowledgeSourceFailureCode:
        """Java 端 STALE_ATTEMPT/LEASE_EXPIRED 统一映射为 Python 端 TIMEOUT。

        Python 不需要区分具体过期原因，只需知道不再持有有效租约从而终止重试。"""
        if error_code in {"STALE_ATTEMPT", "LEASE_EXPIRED", "TIMEOUT"}:
            return "TIMEOUT"
        if error_code in {"VALIDATION", "INDEX_MISMATCH"}:
            return "INDEX_MISMATCH"
        return "INTERNAL"

    async def publish(
        self,
        command: KnowledgeJobCommand,
        context: KnowledgeExecutionContext,
        manifest: ArtifactManifest,
    ) -> PublishedKnowledgeManifest:
        """通过 generation 绑定的短期票据上传规范化清单原始字节。"""
        body = manifest.model_dump_json(by_alias=True).encode("utf-8")
        content_hash = hashlib.sha256(body).hexdigest()
        try:
            ticket = await self._issue_manifest_ticket(command, context)
            expected_url = (
                self.settings.java_internal_base_url
                + f"/internal/knowledge/jobs/{command.job_id}/manifests/{command.generation_id}"
            )
            if (
                ticket.operation != "WRITE_MANIFEST"
                or ticket.object_id != command.generation_id
                or ticket.url != expected_url
                or ticket.content_hash is not None
                or ticket.expires_at <= datetime.now(UTC)
                or len(body) > ticket.max_bytes
            ):
                raise KnowledgeManifestError("INDEX_MISMATCH")
            response = await self.http_client.put(
                ticket.url,
                content=body,
                headers={
                    "Authorization": f"Bearer {ticket.token}",
                    "Content-Type": "application/json",
                    "Accept": "application/json",
                },
            )
            if response.status_code != 200 or len(response.content) > _MAX_ERROR_BODY_BYTES:
                raise KnowledgeManifestError("INTERNAL")
            uploaded = ArtifactUploadResponse.model_validate_json(response.content)
            # 回执与本地原始字节一致，才允许把清单对象 ID 回传给 Java。
            if uploaded.content_hash != content_hash or uploaded.size_bytes != len(body):
                raise KnowledgeManifestError("INDEX_MISMATCH")
            return PublishedKnowledgeManifest(
                object_id=uploaded.object_id,
                content_hash=uploaded.content_hash,
            )
        except KnowledgeManifestError:
            raise
        except (httpx2.HTTPError, ValidationError, ValueError) as error:
            raise KnowledgeManifestError("INTERNAL") from error

    async def _issue_manifest_ticket(
        self, command: KnowledgeJobCommand, context: KnowledgeExecutionContext
    ) -> ArtifactTicket:
        """申请 WRITE_MANIFEST 下游公文票据，不轮询（调用方已在 publish 中统一处理）。"""
        route = f"/internal/knowledge/jobs/{command.job_id}/artifact-tickets"
        url = self.settings.java_internal_base_url + route
        request = ArtifactTicketRequest.model_validate(
            {
                "contractVersion": "rag-v1",
                "requestId": command.request_id,
                "attemptId": command.attempt_id,
                "generationId": command.generation_id,
                "workerId": context.worker_id,
                "operation": "WRITE_MANIFEST",
                "objectId": command.generation_id,
            }
        )
        body = request.model_dump_json(by_alias=True).encode("utf-8")
        headers = dict(
            signed_headers(
                self.settings.required_internal_service_token(),
                self.settings.required_internal_signing_key().encode("utf-8"),
                "POST",
                urlsplit(url).path,
                body,
            )
        )
        headers["Content-Type"] = "application/json"
        headers["Accept"] = "application/json"
        try:
            response = await self.http_client.post(url, content=body, headers=headers)
        except httpx2.RequestError as error:
            raise KnowledgeManifestError("INTERNAL") from error
        if response.status_code != 200 or len(response.content) > _MAX_ERROR_BODY_BYTES:
            raise KnowledgeManifestError("INTERNAL")
        try:
            return ArtifactTicketResponse.model_validate_json(response.content).ticket
        except ValidationError as error:
            raise KnowledgeManifestError("INDEX_MISMATCH") from error

    async def _remove_workspace(self, workspace: Path) -> None:
        """通过线程池异步删除临时目录，ignore_errors=True 保证幂等。"""
        await asyncio.to_thread(shutil.rmtree, workspace, True)