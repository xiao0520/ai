"""知识库执行边界；Kafka 编排、受控源文件和入库流水线通过协议解耦。

外部服务提供 Kafka 编排和产物托管；本模块定义标准协议分界线，通过静态协议
（Protocol / abc）将可控来源与具体管道分离。

核心协议：
  - KnowledgeSourceReader – 受控源下载，不接受任意 URL 或 OSS 凭证。
  - KnowledgeIngestionPipeline – 解析、切片、Embedding 与向量写入的流水线边界。
  - KnowledgeManifestPublisher – 产物清单写入受控对象存储。
  - KnowledgeGenerationStore – generation 盘点与精确删除。
  - KnowledgeJobExecutor – Kafka 已授权任务的执行协议。

核心实现：
  - KnowledgeIngestionExecutor – 先取得受控源文件，再交给入库流水线。
  - KnowledgeMaintenanceExecutor – 盘点与清理 generation。
  - KnowledgeRoutingExecutor – 按命令任务类型路由至对应的执行器。

所有状态仅通过命令携带的不可变快照读取，不使用业务数据库或本地模型路由。
"""

from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Literal, Protocol

from ai_service.contracts.knowledge_kafka import (
    KnowledgeArtifactsEvent,
    KnowledgeCancelledEvent,
    KnowledgeCleanupTask,
    KnowledgeDocumentTask,
    KnowledgeFailedEvent,
    KnowledgeJobCommand,
    KnowledgeMaintenanceEvent,
    KnowledgeProgressEvent,
    KnowledgeReconcileTask,
)
from ai_service.contracts.rag_artifacts import ArtifactManifest

# ============================================================================
# 类型别名与实体
# ============================================================================

type KnowledgeTerminal = (
    KnowledgeArtifactsEvent
    | KnowledgeMaintenanceEvent
    | KnowledgeFailedEvent
    | KnowledgeCancelledEvent
)
type ProgressCallback = Callable[[KnowledgeProgressEvent], Awaitable[None]]
type KnowledgeSourceFailureCode = Literal["TIMEOUT", "INTERNAL", "INDEX_MISMATCH"]


@dataclass(frozen=True, slots=True)
class KnowledgeExecutionContext:
    """保存当前 Kafka attempt 的动态执行身份，供 Java 绑定租约与票据。"""

    worker_id: str


@dataclass(frozen=True, slots=True)
class DownloadedKnowledgeSource:
    """已通过命令快照校验的本地源文件；工作目录只能由 SourceReader 清理。"""

    path: Path
    workspace_path: Path
    media_type: str
    size_bytes: int
    content_hash: str


class KnowledgeSourceError(RuntimeError):
    """将受控源文件故障归一化为 Kafka 已发布的稳定错误码。"""

    def __init__(self, error_code: KnowledgeSourceFailureCode) -> None:
        super().__init__(error_code)
        self.error_code = error_code


@dataclass(frozen=True, slots=True)
class PublishedKnowledgeManifest:
    """受控对象存储返回的不可变清单标识和原始字节哈希。"""

    object_id: str
    content_hash: str


class KnowledgeManifestError(RuntimeError):
    """清单序列化、上传或票据绑定失败。"""


# ============================================================================
# 协议边界
# ============================================================================


class KnowledgeManifestPublisher(Protocol):
    """把清单写入当前 attempt 绑定的受控对象，不持久化业务状态。"""

    async def publish(
        self,
        command: KnowledgeJobCommand,
        context: KnowledgeExecutionContext,
        manifest: ArtifactManifest,
    ) -> PublishedKnowledgeManifest:
        """返回 Java 可验收的对象标识和 SHA-256。"""
        ...


class KnowledgeSourceReader(Protocol):
    """读取 Java 已绑定的源文件，不接受任意 URL、路径或 OSS 凭证。"""

    async def download(
        self, command: KnowledgeJobCommand, context: KnowledgeExecutionContext
    ) -> DownloadedKnowledgeSource:
        """申请对象票据，下载并校验源文件。"""
        ...

    async def release(self, source: DownloadedKnowledgeSource) -> None:
        """删除当前 attempt 的本地临时文件。"""
        ...


class KnowledgeIngestionPipeline(Protocol):
    """解析、切片、Embedding 与 Qdrant 写入的流水线边界。"""

    async def execute(
        self,
        command: KnowledgeJobCommand,
        source: DownloadedKnowledgeSource,
        context: KnowledgeExecutionContext,
        report_progress: ProgressCallback,
    ) -> KnowledgeTerminal:
        """使用已校验源文件生成待 Java 验收的技术产物。"""
        ...


@dataclass(frozen=True, slots=True)
class GenerationMaintenanceResult:
    """Qdrant generation 盘点与精确删除结果。"""

    observed_generation_ids: tuple[str, ...]
    deleted_generation_ids: tuple[str, ...]
    observed_point_count: int
    deleted_point_count: int


class KnowledgeGenerationStore(Protocol):
    """执行 Java 已裁定范围内的 generation 清理和对账。"""

    async def delete_generation_id(
        self, command: KnowledgeJobCommand, generation_id: str
    ) -> int:
        """幂等删除指定 generation 并返回删除前点数。"""
        ...

    async def reconcile_generations(
        self, command: KnowledgeJobCommand, task: KnowledgeReconcileTask
    ) -> GenerationMaintenanceResult:
        """只读盘点 generation，返回观察结果供 Java 决定后续清理。"""
        ...


class KnowledgeJobExecutor(Protocol):
    """执行 Kafka 已授权的任务，并保持执行身份与 Java 租约一致。"""

    async def execute(
        self,
        command: KnowledgeJobCommand,
        context: KnowledgeExecutionContext,
        report_progress: ProgressCallback,
    ) -> KnowledgeTerminal:
        """返回真实终态或待验收产物。"""
        ...


class KnowledgeIngestionExecutor:
    """正式入库入口：先取得受控源文件，再交给入库流水线。"""

    def __init__(
        self, source_reader: KnowledgeSourceReader, pipeline: KnowledgeIngestionPipeline
    ) -> None:
        self.source_reader = source_reader
        self.pipeline = pipeline

    async def execute(
        self,
        command: KnowledgeJobCommand,
        context: KnowledgeExecutionContext,
        report_progress: ProgressCallback,
    ) -> KnowledgeTerminal:
        """下载并校验 INGESTION 源文件。"""
        if not isinstance(command.task, KnowledgeDocumentTask):
            return KnowledgeFailedEvent(
                eventType="FAILED", errorCode="EXECUTOR_NOT_READY", usageItems=[]
            )
        await report_progress(
            KnowledgeProgressEvent(eventType="PROGRESS", stage="PREPARING", progress=5)
        )
        try:
            source = await self.source_reader.download(command, context)
        except KnowledgeSourceError as error:
            return KnowledgeFailedEvent(
                eventType="FAILED", errorCode=error.error_code, usageItems=[]
            )
        try:
            await report_progress(
                KnowledgeProgressEvent(eventType="PROGRESS", stage="PREPARING", progress=10)
            )
            return await self.pipeline.execute(command, source, context, report_progress)
        finally:
            await self.source_reader.release(source)


class KnowledgeMaintenanceExecutor:
    """执行精确 generation 清理或只读盘点，孤儿资格由 Java 最新状态复核。"""

    def __init__(self, generation_store: KnowledgeGenerationStore) -> None:
        self.generation_store = generation_store

    async def execute(
        self,
        command: KnowledgeJobCommand,
        context: KnowledgeExecutionContext,
        report_progress: ProgressCallback,
    ) -> KnowledgeTerminal:
        """按任务类型分发执行：CLEANUP 精确删除、RECONCILE 只读盘点，其他回退 EXECUTOR_NOT_READY。

        Qdrant 写入错误也在此层捕获并转为 Kafka FAILED 事件，不向上传播异常。"""
        task = command.task
        try:
            if isinstance(task, KnowledgeCleanupTask):
                await report_progress(
                    KnowledgeProgressEvent(
                        eventType="PROGRESS", stage="CLEANING", progress=30
                    )
                )
                deleted = await self.generation_store.delete_generation_id(
                    command, task.target_generation_id
                )
                observed_ids = [task.target_generation_id] if deleted else []
                return KnowledgeMaintenanceEvent.model_validate(
                    {
                        "eventType": "MAINTENANCE_COMPLETED",
                        "result": {
                            "jobType": "CLEANUP",
                            "targetGenerationId": task.target_generation_id,
                            "observedGenerationIds": observed_ids,
                            "deletedGenerationIds": observed_ids,
                            "observedPointCount": deleted,
                            "deletedPointCount": deleted,
                        },
                    }
                )
            if isinstance(task, KnowledgeReconcileTask):
                await report_progress(
                    KnowledgeProgressEvent(
                        eventType="PROGRESS", stage="RECONCILING", progress=20
                    )
                )
                result = await self.generation_store.reconcile_generations(command, task)
                return KnowledgeMaintenanceEvent.model_validate(
                    {
                        "eventType": "MAINTENANCE_COMPLETED",
                        "result": {
                            "jobType": "RECONCILE",
                            "targetGenerationId": None,
                            "observedGenerationIds": list(
                                result.observed_generation_ids
                            ),
                            "deletedGenerationIds": list(result.deleted_generation_ids),
                            "observedPointCount": result.observed_point_count,
                            "deletedPointCount": result.deleted_point_count,
                        },
                    }
                )
        except RuntimeError as error:
            if getattr(error, "error_code", None) == "INDEX_MISMATCH":
                return KnowledgeFailedEvent(
                    eventType="FAILED", errorCode="INDEX_MISMATCH", usageItems=[]
                )
            return KnowledgeFailedEvent(
                eventType="FAILED", errorCode="VECTOR_UNAVAILABLE", usageItems=[]
            )
        return KnowledgeFailedEvent(
            eventType="FAILED", errorCode="EXECUTOR_NOT_READY", usageItems=[]
        )


class KnowledgeRoutingExecutor:
    """按任务判别体路由入库与维护执行器，不让具体适配器进入 Kafka 层。"""

    def __init__(
        self,
        ingestion: KnowledgeIngestionExecutor,
        maintenance: KnowledgeMaintenanceExecutor,
    ) -> None:
        self.ingestion = ingestion
        self.maintenance = maintenance

    async def execute(
        self,
        command: KnowledgeJobCommand,
        context: KnowledgeExecutionContext,
        report_progress: ProgressCallback,
    ) -> KnowledgeTerminal:
        """KnowledgeDocumentTask → 入库执行器，其他 → 维护执行器。

        单体 Kafka 入口路由逻辑不涉及具体业务判断，仅根据 task 类型做 dispatch。"""
        if isinstance(command.task, KnowledgeDocumentTask):
            return await self.ingestion.execute(command, context, report_progress)
        return await self.maintenance.execute(command, context, report_progress)


class UnavailableKnowledgeExecutor:
    """用于只验证 Kafka 往返的测试执行器，不访问 Java 资源接口。"""

    async def execute(
        self,
        command: KnowledgeJobCommand,
        context: KnowledgeExecutionContext,
        report_progress: ProgressCallback,
    ) -> KnowledgeTerminal:
        """保留任务可见性与人工重试入口，不伪造向量产物。"""
        return KnowledgeFailedEvent(
            eventType="FAILED", errorCode="EXECUTOR_NOT_READY", usageItems=[]
        )