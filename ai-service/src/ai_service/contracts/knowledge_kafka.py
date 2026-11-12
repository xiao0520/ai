"""知识库 Kafka 命令、结果与取消契约；服务身份由 Broker ACL 保证。

契约按消息流向分为四组：
  - 命令（KnowledgeJobCommand）：Java → Python，单条消息下发一个入库/清理/对账任务。
  - 事件（KnowledgeJobEvent）：Python → Java，同 attempt 内序列递增的状态回传。
  - 控制（KnowledgeJobControl）：Java → Python，通过独立 Topic 广播取消指令。
  - 死信（KnowledgeDeadLetter）：Python 内部，毒消息定位信息落入死信 Topic。
"""

from typing import Annotated, Literal, Self

from pydantic import AwareDatetime, Field, model_validator

from ai_service.contracts.knowledge import IngestionResult
from ai_service.contracts.rag_types import (
    BusinessId,
    Count,
    Digest,
    DocumentProcessingSnapshot,
    ExecutionUsageItem,
    IndexSnapshot,
    Label,
    RagModel,
)

# ============================================================================
# 命令段：Java → Python 的任务负载
# ============================================================================


class KnowledgeDocumentTask(RagModel):
    """传递 Java 已校验的文档引用；不在消息中复制 OSS 密钥或临时下载票据。"""

    # 任务类型标识
    job_type: Literal["INGESTION"] = Field(alias="jobType")

    # 文档身份：唯一绑定一个已发布的文档版本
    document_id: BusinessId = Field(alias="documentId")
    document_version_id: BusinessId = Field(alias="documentVersionId")

    # 源文件引用：OSS 对象 ID + 内容哈希用于下载后校验完整性
    oss_id: BusinessId = Field(alias="ossId")
    source_hash: Digest = Field(alias="sourceHash")

    # 文件元信息：类型、名称、大小，用于解析器选路和预算估算
    media_type: Annotated[str, Field(min_length=1, max_length=255)] = Field(alias="mediaType")
    source_name: Annotated[str, Field(min_length=1, max_length=512)] | None = Field(
        default=None, alias="sourceName"
    )
    file_size_bytes: Annotated[int, Field(ge=1, le=1_073_741_824)] = Field(alias="fileSizeBytes")

    # 目标索引入口
    target_index_version: Label = Field(alias="targetIndexVersion")


class KnowledgeCleanupTask(RagModel):
    """删除 Java 已确认无发布引用的单个 generation。"""

    job_type: Literal["CLEANUP"] = Field(alias="jobType")

    # 目标定位：索引版本 + generation 精确删除
    target_index_version: Label = Field(alias="targetIndexVersion")
    target_generation_id: BusinessId = Field(alias="targetGenerationId")

    # 清理原因，用于审计和重试策略区分
    reason: Literal["FAILED", "CANCELLED", "EXPIRED", "SUPERSEDED", "ORPHANED"]


class KnowledgeReconcileTask(RagModel):
    """盘点 Qdrant generation；冻结保留集合仅供审计，删除由 Java 最新状态复核后另发 CLEANUP。"""

    job_type: Literal["RECONCILE"] = Field(alias="jobType")
    target_index_version: Label = Field(alias="targetIndexVersion")

    # 白名单：不在保留集合中的 generation 将被标记为待删除，但实际删除仍需 Java 另发 CLEANUP
    retained_generation_ids: Annotated[list[BusinessId], Field(max_length=100_000)] = Field(
        alias="retainedGenerationIds"
    )


class KnowledgeExecutionBudget(RagModel):
    """Java 为单个入库 attempt 批准的模型 Token 上限。

    仅 INGESTION 任务携带此预算；CLEANUP/RECONCILE 不消耗模型资源。
    """

    max_tokens: Annotated[int, Field(ge=1, le=2_147_483_647)] = Field(alias="maxTokens")


class KnowledgeJobCommand(RagModel):
    """同一 attempt 重复投递时，消息标识与全部执行快照保持不变。

    字段按职责分为四层：
      路由 — Kafka 分区 key 与链路追踪
      任务 — 入库/清理/对账的不可变参数
      快照 — 索引和目标文档的发布版本固化
      预算 — 仅入库任务携带的 Token 上限
    """

    # === 路由与追踪标识 ===
    contract_version: Literal["knowledge-kafka-v1"] = Field(alias="contractVersion")
    message_id: Label = Field(alias="messageId")
    request_id: Label = Field(alias="requestId")
    job_id: BusinessId = Field(alias="jobId")
    knowledge_id: BusinessId = Field(alias="knowledgeId")

    # === 执行批次标识 ===
    attempt_id: Label = Field(alias="attemptId")
    generation_id: BusinessId = Field(alias="generationId")
    attempt_number: Annotated[int, Field(ge=1, le=100)] = Field(alias="attemptNumber")
    created_at: AwareDatetime = Field(alias="createdAt")
    deadline: AwareDatetime

    # === 任务负载（discriminator 按 job_type 路由到具体任务类型）===
    task: Annotated[
        KnowledgeDocumentTask | KnowledgeCleanupTask | KnowledgeReconcileTask,
        Field(discriminator="job_type"),
    ]

    # === 不可变执行快照 ===
    index_snapshot: IndexSnapshot = Field(alias="indexSnapshot")
    document_processing_snapshot: DocumentProcessingSnapshot | None = Field(
        alias="documentProcessingSnapshot"
    )

    # === 模型预算（仅 INGESTION）===
    execution_budget: KnowledgeExecutionBudget | None = Field(alias="executionBudget")

    @model_validator(mode="after")
    def validate_execution_snapshot(self) -> Self:
        """任务只能执行命令内与知识库、目标索引一致的不可变快照。

        校验分三步：
          1. 快照一致性：indexSnapshot 必须与命令的 knowledgeId、索引版本匹配。
          2. 任务类型限制：CLEANUP/RECONCILE 不得绑定文档处理快照或模型预算。
          3. 入库任务必须携带处理快照和执行预算。
          4. 文档文本向量模型必须与目标索引一致。
          5. 配置了多模态向量时，模型必须与目标索引一致。
        """
        # 步骤 1：快照与命令的 knowledgeId / 索引版本一致性
        if self.knowledge_id != self.index_snapshot.knowledge_id:
            raise ValueError("indexSnapshot 与 knowledgeId 不一致")
        if self.task.target_index_version != self.index_snapshot.index_version:
            raise ValueError("indexSnapshot 与目标索引版本不一致")

        # 步骤 2：非入库任务不得携带文档处理快照或模型预算
        if not isinstance(self.task, KnowledgeDocumentTask):
            if self.document_processing_snapshot is not None or self.execution_budget is not None:
                raise ValueError("维护任务不得绑定文档处理快照或模型预算")
            return self

        # 步骤 3：入库任务必须绑定预算和文档处理快照
        if self.execution_budget is None:
            raise ValueError("INGESTION 必须绑定模型执行预算")
        processing = self.document_processing_snapshot
        if processing is None:
            raise ValueError("INGESTION 必须绑定文档处理快照")

        # 步骤 4：文档文本向量模型必须与目标索引一致
        if processing.text_embedding.profile != self.index_snapshot.embedding.profile:
            raise ValueError("文档文本向量模型与目标索引不一致")

        # 步骤 5：若文档有多模态向量，目标索引也必须配置相同的多模态模型
        index_multimodal = self.index_snapshot.multimodal_embedding
        processing_multimodal = processing.multimodal_embedding
        if (
            processing_multimodal is not None
            and (
                index_multimodal is None
                or index_multimodal.profile != processing_multimodal.profile
            )
        ):
            raise ValueError("文档多模态向量模型与目标索引不一致")
        return self


# ============================================================================
# 事件段：Python → Java 的状态回传
# 事件通过 KnowledgeJobEvent 外壳统一封装，内部按 event_type 分发到具体事件体。
# ============================================================================


class KnowledgeStartedEvent(RagModel):
    """消费开始只推进 RUNNING，不表示任务成功。

    STARTED 事件是 Python 消费到 Kafka 消息后的第一个回执，证明消息已被
    worker 接收并开始处理，但尚未完成任何实质工作。
    """

    event_type: Literal["STARTED"] = Field(alias="eventType")


class KnowledgeProgressEvent(RagModel):
    """有限进度和续租信号，不承载正文或供应商原始响应。

    progress 为 0-99 的百分比，不代表精确完成度，仅用于 UI 展示和 lease 续约判断。
    """

    event_type: Literal["PROGRESS"] = Field(alias="eventType")

    # 当前执行阶段
    stage: Literal[
        "PREPARING", "PARSING", "EMBEDDING", "WRITING", "VALIDATING", "CLEANING", "RECONCILING"
    ]

    # 0-99 百分比，不承诺到达 100（完成由 ARTIFACTS_READY 或 MAINTENANCE_COMPLETED 表示）
    progress: Annotated[int, Field(ge=0, le=99)]


class KnowledgeFailedEvent(RagModel):
    """执行失败由 Java 决定重试；执行器未实现必须明确失败，禁止伪造成功。

    error_code 语义：
      EXECUTOR_NOT_READY — worker 启动中 / 依赖未就绪，可重试
      COMMAND_EXPIRED     — 消息超过 deadline，不可重试
      DOCUMENT_UNSUPPORTED — 文件格式/编码不支持
      PARSE_FAILED        — 解析器返回截断或异常
      EMBEDDING_UNAVAILABLE — 向量模型不可用
      VECTOR_UNAVAILABLE  — Qdrant 写入失败
      MODEL_UNAVAILABLE   — 模型供应商不可用
      INDEX_MISMATCH      — 快照与索引不一致
      BUDGET_EXCEEDED     — Token 预算不足
      TIMEOUT             — 执行超时
      INTERNAL            — 未知内部错误
    """

    event_type: Literal["FAILED"] = Field(alias="eventType")
    error_code: Literal[
        "EXECUTOR_NOT_READY",
        "COMMAND_EXPIRED",
        "DOCUMENT_UNSUPPORTED",
        "PARSE_FAILED",
        "EMBEDDING_UNAVAILABLE",
        "VECTOR_UNAVAILABLE",
        "MODEL_UNAVAILABLE",
        "INDEX_MISMATCH",
        "BUDGET_EXCEEDED",
        "TIMEOUT",
        "INTERNAL",
    ] = Field(alias="errorCode")

    # 即使失败也上报已发生的用量，避免漏记模型调用成本
    usage_items: Annotated[list[ExecutionUsageItem], Field(max_length=1000)] = Field(
        alias="usageItems"
    )


class KnowledgeCancelledEvent(RagModel):
    """仅报告当前 attempt 已停止，不能取消其他批次。

    CANCELLED 由 Python 在消费到 KnowledgeJobControl 后发出，表示本 worker
    已主动终止当前 attempt，不会继续消费同 attempt 的消息。
    """

    event_type: Literal["CANCELLED"] = Field(alias="eventType")
    usage_items: Annotated[list[ExecutionUsageItem], Field(max_length=1000)] = Field(
        alias="usageItems"
    )


class KnowledgeArtifactsEvent(RagModel):
    """技术产物就绪后仍须 Java 验收；不能据此直接激活索引。

    ARTIFACTS_READY 表示向量已写入 Qdrant 且清单已上传 OSS，但 generation
    在 Java 执行候选复核并激活前仍为不可检索状态。
    """

    event_type: Literal["ARTIFACTS_READY"] = Field(alias="eventType")
    result: IngestionResult


class KnowledgeMaintenanceResult(RagModel):
    """清理或对账只回报 generation 摘要，不携带向量、正文或凭证。

    字段分为三组：
      操作目标 — 清理时指向单个 generation，对账时为 None
      盘点结果 — Qdrant 中实际观察到的 generation 集合
      删除明细 — 本次实际删除的 generation 及点数
    """

    job_type: Literal["CLEANUP", "RECONCILE"] = Field(alias="jobType")

    # === 操作目标 ===
    target_generation_id: BusinessId | None = Field(alias="targetGenerationId")

    # === 盘点结果 ===
    observed_generation_ids: Annotated[list[BusinessId], Field(max_length=100_000)] = Field(
        alias="observedGenerationIds"
    )
    observed_point_count: Count = Field(alias="observedPointCount")

    # === 删除明细 ===
    deleted_generation_ids: Annotated[list[BusinessId], Field(max_length=100_000)] = Field(
        alias="deletedGenerationIds"
    )
    deleted_point_count: Count = Field(alias="deletedPointCount")

    @model_validator(mode="after")
    def validate_result(self) -> Self:
        """单批清理必须回显目标；对账结果不得伪造单目标。

        校验规则：
          - CLEANUP 必须有明确的 target_generation_id
          - RECONCILE 的 target_generation_id 必须为 None
          - 已删除的 generation 必须是已观察到的子集
        """
        if (self.job_type == "CLEANUP") != (self.target_generation_id is not None):
            raise ValueError("INDEX_MISMATCH")
        if not set(self.deleted_generation_ids).issubset(self.observed_generation_ids):
            raise ValueError("INDEX_MISMATCH")
        return self


class KnowledgeMaintenanceEvent(RagModel):
    """维护结果仍由 Java 对照当前成员关系验收后落库。"""

    event_type: Literal["MAINTENANCE_COMPLETED"] = Field(alias="eventType")
    result: KnowledgeMaintenanceResult


# ============================================================================
# 事件信封：统一包装所有 Python → Java 的事件
# ============================================================================


class KnowledgeJobEvent(RagModel):
    """序列在单个 attempt 内递增；原消息 key 必须与 jobId 一致。

    字段分为两层：
      路由 — 关联原始命令的标识，确保 Java 可定位到对应 attempt
      负载 — 按 event_type 区分的事件体，sequence 保证同 attempt 内有序
    """

    # === 路由标识（与 KnowledgeJobCommand 对应）===
    contract_version: Literal["knowledge-kafka-v1"] = Field(alias="contractVersion")
    message_id: Label = Field(alias="messageId")
    request_id: Label = Field(alias="requestId")
    job_id: BusinessId = Field(alias="jobId")
    attempt_id: Label = Field(alias="attemptId")
    generation_id: BusinessId = Field(alias="generationId")
    worker_id: Label = Field(alias="workerId")

    # === 事件负载 ===
    # 同 attempt 内从 1 开始递增，Java 据此检测丢失或乱序
    sequence: Annotated[int, Field(ge=1, le=2_147_483_647)]

    # discriminator 按 event_type 分发到具体事件体
    event: Annotated[
        KnowledgeStartedEvent
        | KnowledgeProgressEvent
        | KnowledgeFailedEvent
        | KnowledgeCancelledEvent
        | KnowledgeArtifactsEvent
        | KnowledgeMaintenanceEvent,
        Field(discriminator="event_type"),
    ]


# ============================================================================
# 控制段：Java → Python 的取消广播
# ============================================================================


class KnowledgeJobControl(RagModel):
    """取消通过独立 Topic 广播，避免排在同一分区的长任务之后。

    Python 消费到 CANCEL 指令后应立即停止当前 attempt 的处理，发出
    KnowledgeCancelledEvent，并停止消费同 attempt 的消息。
    """

    contract_version: Literal["knowledge-kafka-v1"] = Field(alias="contractVersion")
    message_id: Label = Field(alias="messageId")

    # 取消目标：精确到 attempt 级别
    job_id: BusinessId = Field(alias="jobId")
    attempt_id: Label = Field(alias="attemptId")

    action: Literal["CANCEL"]

    # 过期后取消指令失效，worker 可忽略
    expires_at: AwareDatetime = Field(alias="expiresAt")


# ============================================================================
# 死信段：Python 内部毒消息定位
# ============================================================================


class KnowledgeDeadLetter(RagModel):
    """毒消息只记录定位和摘要；原记录仍保留于原 Topic，避免复制正文或票据。

    当 Python 无法解析或校验 Kafka 消息时，将消息的 Kafka 元信息和哈希写入
    死信 Topic，供运维排查。原始消息体不复制，防止敏感信息扩散。
    """

    contract_version: Literal["knowledge-kafka-v1"] = Field(alias="contractVersion")

    # Kafka 来源定位
    source_topic: Label = Field(alias="sourceTopic")
    source_partition: Count = Field(alias="sourcePartition")
    source_offset: Annotated[int, Field(ge=0, le=9_223_372_036_854_775_807)] = Field(
        alias="sourceOffset"
    )

    # 原始消息的 SHA-256，用于在原始 Topic 中回溯确认
    payload_hash: Digest = Field(alias="payloadHash")

    # 死信原因
    reason: Literal["INVALID_MESSAGE", "KEY_MISMATCH", "UNSUPPORTED_RESULT"]