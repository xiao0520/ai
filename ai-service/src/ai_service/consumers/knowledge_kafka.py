"""知识库 Kafka 消费编排：终态投递成功后才提交 offset，重平衡不冒充业务取消。

架构概述
--------
每个进程运行一个 KnowledgeKafkaWorker，内部持有两个 AIOKafkaConsumer：
  commands — 消费命令 Topic，按 jobId 分区，同一 job 的所有 attempt 进入同一分区。
  controls — 消费控制 Topic，使用独立随机 group_id，确保取消广播到每个实例。

核心消费循环 _consume_commands 遵循严格的一次处理一次提交策略：
  1. 从 Kafka 拉取一条消息（max_poll_records=1）。
  2. 校验消息格式 → 解析为 KnowledgeJobCommand。
  3. 调用 _execute 执行任务（阻塞直到完成/超时/取消/重平衡）。
  4. 投递结果事件到 result_topic（KnowledgeJobEvent）。
  5. 仅当以上全部成功时才 commit offset + 1。

重平衡安全：
  - partitions_revoked 时递增 ownership_epoch，取消当前执行。
  - commit 前检查 ownership_epoch 和分区归属，已撤回的分区绝不 commit。
  - 取消通过独立 control topic 广播，不排在同一分区长任务之后。

优雅停机：
  - stop() 设置 SHUTDOWN 标记后取消循环任务，不 commit 未完成消息。
  - 未完成的消息由新消费者从原 offset 重放（auto_offset_reset="earliest"）。
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import ssl
import uuid
from datetime import UTC, datetime

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer, ConsumerRebalanceListener
from aiokafka.errors import CommitFailedError, KafkaError
from aiokafka.structs import ConsumerRecord, TopicPartition
from pydantic import ValidationError

from ai_service.contracts.knowledge_kafka import (
    KnowledgeCancelledEvent,
    KnowledgeDeadLetter,
    KnowledgeFailedEvent,
    KnowledgeJobCommand,
    KnowledgeJobControl,
    KnowledgeJobEvent,
    KnowledgeProgressEvent,
    KnowledgeStartedEvent,
)
from ai_service.domain.knowledge_execution import (
    KnowledgeExecutionContext,
    KnowledgeJobExecutor,
    KnowledgeTerminal,
)
from ai_service.infra.knowledge_kafka_settings import KnowledgeKafkaSettings

logger = logging.getLogger(__name__)

# ============================================================================
# 重平衡监听器
# ============================================================================


class KnowledgeRebalanceListener(ConsumerRebalanceListener):
    """分区撤回时先停止当前执行，禁止旧消费者继续提交新归属分区的 offset。

    Kafka 触发 rebalance 时先回调 on_partitions_revoked，新消费者分配后
    回调 on_partitions_assigned。已撤销分区的消息由新消费者从已提交位置重放。
    """

    def __init__(self, worker: KnowledgeKafkaWorker) -> None:
        self.worker = worker

    async def on_partitions_revoked(self, revoked: set[TopicPartition]) -> None:
        """停止被撤回分区的工作，保留原消息供新消费者重放。

        调用 worker.revoke() 递增 ownership_epoch 并取消当前执行任务，
        确保后续 commit 检查能正确跳过已不属于本消费者的分区。
        """
        await self.worker.revoke(revoked)

    async def on_partitions_assigned(self, assigned: set[TopicPartition]) -> None:
        """新分区从 Kafka 已提交的位置继续消费。

        不需要额外操作，_consume_commands 循环会自动从 committed offset
        开始拉取新分区的消息。
        """


# ============================================================================
# Kafka Worker：命令消费 + 控制消费 + 结果投递
# ============================================================================


class KnowledgeKafkaWorker:
    """每进程有界串行执行；命令按 jobId 分区，业务状态只通过结果 Topic 回到 Java。

    实例持有三个 Kafka 客户端：
      producer   — 幂等 Producer，投递 KnowledgeJobEvent 到 result_topic 和死信。
      commands   — 消费 command_topic，一次拉一条，串行执行。
      controls   — 消费 control_topic（独立随机 group），广播取消指令。

    内部状态：
      _active     — 当前正在执行的 asyncio.Task（最多一个，串行保证）。
      _command    — 当前正在处理的 KnowledgeJobCommand。
      _partition  — 当前消息所属分区（commit 目标和重平衡检查目标）。
      _cancelled  — 已收到取消指令的 (job_id, attempt_id) → expires_at 映射。
      _ownership_epoch — 重平衡计数器，revoke 时递增，commit 前校验。
    """

    def __init__(self, settings: KnowledgeKafkaSettings, executor: KnowledgeJobExecutor) -> None:
        self.settings = settings
        self.executor = executor

        # --- 构建 SSL 上下文和 SASL 密码 ---
        context = (
            ssl.create_default_context(cafile=settings.ssl_ca_file)
            if "SSL" in settings.security_protocol
            else None
        )
        password = settings.sasl_password.get_secret_value() if settings.sasl_password else None

        # --- 幂等 Producer：投递结果事件和死信 ---
        # enable_idempotence=True + acks="all" 保证结果事件不重复不丢失
        self.producer = AIOKafkaProducer(
            bootstrap_servers=settings.bootstrap_servers,
            client_id=settings.worker_id + "-result",
            enable_idempotence=True,
            acks="all",
            request_timeout_ms=settings.send_timeout_seconds * 1000,
            security_protocol=settings.security_protocol,
            sasl_mechanism=settings.sasl_mechanism,
            sasl_plain_username=settings.sasl_username,
            sasl_plain_password=password,
            ssl_context=context,
            max_request_size=settings.max_message_bytes,
        )

        # --- 命令 Consumer：按 jobId 分区，串行执行 ---
        # 使用固定 group_id，同 group 内各消费者按分区分配
        self.commands = self._consumer(settings.group_id, settings.worker_id, context, password)

        # --- 控制 Consumer：独立随机 group，广播取消到每个实例 ---
        # 取消需要广播到每个实例；独立组避免取消被分配给没有执行该任务的其他实例。
        self.controls = self._consumer(
            settings.group_id + "-control-" + uuid.uuid4().hex,
            settings.worker_id + "-control",
            context,
            password,
        )

        # --- Topic 订阅 ---
        # commands 订阅时绑定重平衡监听器，controls 不需要（取消与分区无关）
        self.commands.subscribe([settings.command_topic], listener=KnowledgeRebalanceListener(self))
        self.controls.subscribe([settings.control_topic])

        # --- 运行时状态 ---
        self._tasks: list[asyncio.Task[None]] = []
        self._active: asyncio.Task[KnowledgeTerminal] | None = None
        self._command: KnowledgeJobCommand | None = None
        self._partition: TopicPartition | None = None
        self._abort_reason: str | None = None
        self._cancelled: dict[tuple[str, str], datetime] = {}
        self._stopping = False
        self._ownership_epoch = 0

    def _consumer(
        self, group: str, client: str, context: ssl.SSLContext | None, password: str | None
    ) -> AIOKafkaConsumer:
        """创建统一配置的 Kafka Consumer。

        关键配置说明：
          enable_auto_commit=False — 手动 commit，确保只确认已处理完成的消息。
          auto_offset_reset="earliest" — 新 group 或 offset 过期时从最早消息重放。
          max_poll_records=1 — 每次只拉一条，实现严格的一次处理一次提交。
          max_poll_interval_ms — 处理超时 + 300s 缓冲，防止长任务触发 rebalance。
        """
        return AIOKafkaConsumer(
            bootstrap_servers=self.settings.bootstrap_servers,
            group_id=group,
            client_id=client,
            enable_auto_commit=False,
            auto_offset_reset="earliest",
            max_poll_records=1,
            max_poll_interval_ms=(self.settings.processing_timeout_seconds + 300) * 1000,
            request_timeout_ms=self.settings.send_timeout_seconds * 1000,
            security_protocol=self.settings.security_protocol,
            sasl_mechanism=self.settings.sasl_mechanism,
            sasl_plain_username=self.settings.sasl_username,
            sasl_plain_password=password,
            ssl_context=context,
            max_partition_fetch_bytes=self.settings.max_message_bytes,
        )

    # ========================================================================
    # 生命周期管理
    # ========================================================================

    async def start(self) -> None:
        """所有客户端连接成功后才启动循环；部分启动失败也会释放连接。

        启动顺序：
          1. producer.start() — 先启动 Producer，后续结果投递立即可用。
          2. controls.start() — 启动控制 Consumer。
          3. _restore_controls() — 追平启动前的取消记录，防止离线期间取消丢失。
          4. commands.start() — 最后启动命令 Consumer，确保取消记录就绪后再消费。
        """
        try:
            await self.producer.start()
            await self.controls.start()
            await self._restore_controls()
            await self.commands.start()
        except BaseException:
            # 任一步骤失败都确保释放已连接的资源
            await self.stop()
            raise
        # 启动两个后台消费循环
        self._tasks = [
            asyncio.create_task(self._consume_commands(), name="knowledge-kafka-commands"),
            asyncio.create_task(self._consume_controls(), name="knowledge-kafka-controls"),
        ]
        # 注册异常回调：任一循环异常退出时取消兄弟循环
        for task in self._tasks:
            task.add_done_callback(self._task_done)
        logger.info("知识库 Kafka 消费已启动 workerId=%s", self.settings.worker_id)

    @property
    def healthy(self) -> bool:
        """后台循环异常停止后必须对运维可见，不能保持假就绪。

        健康检查逻辑：_tasks 非空且所有后台循环仍在运行。
        """
        return bool(self._tasks) and all(not task.done() for task in self._tasks)

    def _task_done(self, task: asyncio.Task[None]) -> None:
        """任意消费循环异常退出时，取消兄弟循环，触发级联停止。

        仅在非主动停止（_stopping=False）且非取消（task.cancelled()=False）
        且有未捕获异常时触发级联取消。
        """
        if not self._stopping and not task.cancelled() and task.exception() is not None:
            logger.error("知识库 Kafka 消费已停止，请检查连接并重启；未完成消息不会确认")
            for sibling in self._tasks:
                if sibling is not task:
                    sibling.cancel()

    async def stop(self) -> None:
        """停机保留未完成 command，取消执行和循环后关闭网络客户端。

        停机顺序：
          1. 设置 _stopping=True + SHUTDOWN 标记，防止 _task_done 级联取消。
          2. 取消当前执行任务和后台循环。
          3. 等待所有任务完成（忽略异常）。
          4. 关闭 Consumer 和 Producer 连接。
        未完成的消息不 commit，由新消费者从原 offset 重放。
        """
        self._stopping = True
        self._abort_reason = "SHUTDOWN"
        if self._active:
            self._active.cancel()
        for task in self._tasks:
            task.cancel()
        await asyncio.gather(*self._tasks, return_exceptions=True)
        if self._active:
            await asyncio.gather(self._active, return_exceptions=True)
        await asyncio.gather(
            self.commands.stop(), self.controls.stop(), self.producer.stop(), return_exceptions=True
        )

    # ========================================================================
    # 重平衡处理
    # ========================================================================

    async def revoke(self, partitions: set[TopicPartition]) -> None:
        """取消被撤回分区的执行；已发出结果仍由 Java 幂等处理。

        递增 _ownership_epoch 使后续 commit 检查自动跳过已撤回分区。
        如果当前正在执行被撤回分区的消息，取消执行任务。
        """
        self._ownership_epoch += 1
        if self._partition in partitions:
            self._abort_reason = "REBALANCE"
            if self._active:
                self._active.cancel()
                await asyncio.gather(self._active, return_exceptions=True)

    # ========================================================================
    # 结果投递与死信
    # ========================================================================

    async def _send(self, topic: str, value: bytes, key: bytes | None) -> None:
        """向指定 Topic 投递消息，带大小校验和超时保护。

        幂等 Producer（enable_idempotence=True）的内部重试可能不自行过期，
        外层加 asyncio.timeout，超时后保留 command 供重放。
        """
        if len(value) > self.settings.max_message_bytes:
            raise ValueError("Kafka 消息超过配置上限")
        # 幂等 Producer 的内部重试可能不自行过期，外层超时后保留 command 供重放。
        try:
            await asyncio.wait_for(
                self.producer.send_and_wait(topic, value=value, key=key),
                timeout=self.settings.send_timeout_seconds,
            )
        except (TimeoutError, KafkaError) as exc:
            raise KafkaDeliveryError("Kafka 投递未确认") from exc

    async def _dead_letter(self, record: ConsumerRecord, reason: str) -> None:
        """将无法处理的毒消息写入死信队列，包含来源定位和内容哈希。

        死信记录保存原始消息的 topic/partition/offset 和 payload 哈希，
        供运维排查和人工重放。死信写入失败会抛出 KafkaDeliveryError 中断消费。
        """
        payload = KnowledgeDeadLetter.model_validate(
            {
                "contractVersion": "knowledge-kafka-v1",
                "sourceTopic": record.topic,
                "sourcePartition": record.partition,
                "sourceOffset": record.offset,
                "payloadHash": hashlib.sha256(record.value or b"").hexdigest(),
                "reason": reason,
            }
        )
        await self._send(
            self.settings.dlq_topic, payload.model_dump_json(by_alias=True).encode(), record.key
        )

    # ========================================================================
    # 命令消费主循环
    # ========================================================================

    async def _consume_commands(self) -> None:
        """命令消费主循环：拉取 → 校验 → 执行 → commit。

        每次只拉取一条消息，处理流程：
          1. 从 commands 拉取一条 ConsumerRecord。
          2. 校验消息格式（JSON → KnowledgeJobCommand + 大小检查）。
          3. 校验 key 一致性（record.key == command.job_id）。
          4. 调用 _execute 执行任务（阻塞到终态）。
          5. 仅当执行成功时 commit offset + 1。

        重平衡安全：
          - commit 前检查 ownership_epoch，被 revoke 后绝不 commit。
          - commit 前检查分区归属，已不属于本消费者的分区不提交。
        """
        while True:
            # --- 步骤 1：拉取消息 ---
            record = await self.commands.getone()
            partition = TopicPartition(record.topic, record.partition)
            ownership_epoch = self._ownership_epoch  # 快照 epoch，用于退出前比较

            # --- 步骤 2-3：校验消息 ---
            try:
                command = KnowledgeJobCommand.model_validate_json(record.value or b"")
                if len(record.value or b"") > self.settings.max_message_bytes:
                    raise ValueError("消息过大")
            except ValidationError, ValueError:
                # 格式校验失败 → 写入死信，不阻塞后续消息
                await self._dead_letter(record, "INVALID_MESSAGE")
            else:
                if record.key != command.job_id.encode():
                    # key 不一致 → 分区路由异常，写入死信
                    await self._dead_letter(record, "KEY_MISMATCH")
                else:
                    # --- 步骤 4：执行任务 ---
                    self._partition = partition
                    accepted = await self._execute(command)
                    self._partition = None
                    if not accepted:
                        # _execute 返回 False 表示消息应重放（如重平衡），不 commit
                        continue

            # --- 步骤 5：commit offset（仅当前分区仍归属本消费者时）---
            # 每次只确认已处理的这一条，不能提交客户端预取的更后位置。
            if (
                ownership_epoch != self._ownership_epoch
                or partition not in self.commands.assignment()
            ):
                continue
            try:
                await self.commands.commit({partition: record.offset + 1})
            except CommitFailedError:
                logger.info("知识库消费分区已重分配，消息将按已提交位置重放")

    # ========================================================================
    # 任务执行状态机
    # ========================================================================

    async def _execute(self, command: KnowledgeJobCommand) -> bool:
        """执行单个知识库任务的状态机，返回 True 表示已确认处理（可 commit）。

        状态机流程：
          1. 检查 deadline 是否已过期 → FAILED / COMMAND_EXPIRED
          2. 检查是否已被取消 → CANCELLED
          3. 发送 STARTED 事件
          4. 再次检查取消状态（启动后可能收到取消指令）
          5. 检查 SHUTDOWN / REBALANCE → 终止执行
          6. 创建异步执行任务，带超时和心跳
          7. 等待执行完成 / 超时 / 被取消 → 确定终态事件
          8. 发送终态事件到 result_topic

        REBALANCE 或 SHUTDOWN 时返回 False，保留消息供重放；
        KafkaDeliveryError 会向上抛出，中断消费且不提交当前 offset。
        """
        self._command = command
        self._abort_reason = None
        sequence = 0
        # execution_owner 标识当前执行实例，用于 Java 侧去重和审计
        execution_owner = self.settings.worker_id + "-" + uuid.uuid4().hex
        send_lock = asyncio.Lock()

        async def emit(
            event: KnowledgeStartedEvent | KnowledgeProgressEvent | KnowledgeTerminal,
        ) -> None:
            """序列化投递事件到 result_topic，带自增序号和消息 ID。

            sequence 每次投递自增，message_id 由 attempt_id + sequence 哈希生成，
            Java 侧据此去重。send_lock 保证并发安全。
            """
            nonlocal sequence
            async with send_lock:
                sequence += 1
                message = KnowledgeJobEvent(
                    contractVersion="knowledge-kafka-v1",
                    messageId=hashlib.sha256(
                        f"{command.attempt_id}:{sequence}".encode()
                    ).hexdigest(),
                    requestId=command.request_id,
                    jobId=command.job_id,
                    attemptId=command.attempt_id,
                    generationId=command.generation_id,
                    workerId=execution_owner,
                    sequence=sequence,
                    event=event,
                )
                await self._send(
                    self.settings.result_topic,
                    message.model_dump_json(by_alias=True).encode(),
                    command.job_id.encode(),
                )

        async def progress(event: KnowledgeProgressEvent) -> None:
            """发送进度事件（心跳）。"""
            await emit(event)

        try:
            # --- 步骤 1：deadline 检查 ---
            remaining = (command.deadline - datetime.now(UTC)).total_seconds()
            if remaining <= 0:
                await emit(
                    KnowledgeFailedEvent(
                        eventType="FAILED", errorCode="COMMAND_EXPIRED", usageItems=[]
                    )
                )
                return True

            # --- 步骤 2：启动前取消检查 ---
            if self._is_cancelled(command):
                await emit(KnowledgeCancelledEvent(eventType="CANCELLED", usageItems=[]))
                return True

            # --- 步骤 3：发送 STARTED ---
            await emit(KnowledgeStartedEvent(eventType="STARTED"))

            # --- 步骤 4：启动后取消检查 ---
            if self._is_cancelled(command):
                await emit(KnowledgeCancelledEvent(eventType="CANCELLED", usageItems=[]))
                return True

            # --- 步骤 5：SHUTDOWN / REBALANCE 检查 ---
            if self._abort_reason in ("REBALANCE", "SHUTDOWN"):
                return False

            # --- 步骤 6：创建异步执行任务 ---
            # 超时时间取 deadline 剩余和配置最大值的最小值
            self._active = asyncio.create_task(
                self.executor.execute(
                    command, KnowledgeExecutionContext(worker_id=execution_owner), progress
                )
            )

            # --- 步骤 7：等待执行完成（带超时和心跳）---
            try:
                async with asyncio.timeout(
                    min(remaining, self.settings.processing_timeout_seconds)
                ):
                    # 心跳循环：每 heartbeat_seconds 发送一次 PROGRESS 事件
                    while not self._active.done():
                        await asyncio.wait([self._active], timeout=self.settings.heartbeat_seconds)
                        if not self._active.done():
                            await progress(
                                KnowledgeProgressEvent(
                                    eventType="PROGRESS", stage="PREPARING", progress=0
                                )
                            )
                    terminal = await self._active
            except asyncio.CancelledError:
                # 任务被取消 → 根据 _abort_reason 确定终态
                if self._abort_reason == "USER_CANCEL":
                    terminal = KnowledgeCancelledEvent(eventType="CANCELLED", usageItems=[])
                elif self._abort_reason == "REBALANCE":
                    # 重平衡取消 → 不发送终态，返回 False 让消息重放
                    return False
                else:
                    raise
            except TimeoutError:
                # 执行超时 → 取消任务，发送 FAILED / TIMEOUT
                self._active.cancel()
                await asyncio.gather(self._active, return_exceptions=True)
                terminal = KnowledgeFailedEvent(
                    eventType="FAILED", errorCode="TIMEOUT", usageItems=[]
                )
            except KafkaDeliveryError:
                # 投递失败 → 向上抛出，不发送终态，消息重放
                raise
            except RuntimeError, ValueError, TypeError, OSError:
                # 非预期异常 → FAILED / INTERNAL
                terminal = KnowledgeFailedEvent(
                    eventType="FAILED", errorCode="INTERNAL", usageItems=[]
                )

            # --- 步骤 8：终态前再次检查 SHUTDOWN / REBALANCE ---
            if self._abort_reason in ("REBALANCE", "SHUTDOWN"):
                return False
            await emit(terminal)
            logger.info(
                "知识库任务已回报 jobId=%s attemptId=%s event=%s",
                command.job_id,
                command.attempt_id,
                terminal.event_type,
            )
            return True
        finally:
            # 确保 active 任务被取消并清理，避免资源泄漏
            if self._active and not self._active.done():
                self._active.cancel()
                await asyncio.gather(self._active, return_exceptions=True)
            self._active = None
            self._command = None

    # ========================================================================
    # 取消管理
    # ========================================================================

    def _is_cancelled(self, command: KnowledgeJobCommand) -> bool:
        """检查当前任务是否已被取消。

        先清理过期的取消记录，再检查 (job_id, attempt_id) 是否在缓存中。
        """
        self._prune_cancellations()
        return (command.job_id, command.attempt_id) in self._cancelled

    def _prune_cancellations(self) -> None:
        """清理已过期的取消记录，防止内存无限增长。

        取消记录携带 expires_at，过期后自动清除。
        """
        now = datetime.now(UTC)
        self._cancelled = {key: expiry for key, expiry in self._cancelled.items() if expiry > now}

    # ========================================================================
    # 控制消费
    # ========================================================================

    async def _apply_control(self, record: ConsumerRecord) -> None:
        """解析并应用控制消息（取消指令）。

        处理流程：
          1. 校验格式 → 解析为 KnowledgeJobControl。
          2. 校验 key 一致性。
          3. 检查是否已过期（expires_at > now）。
          4. 将取消记录写入 _cancelled 缓存。
          5. 如果当前命令匹配，取消执行任务。
          6. commit 控制消息 offset。
        """
        try:
            control = KnowledgeJobControl.model_validate_json(record.value or b"")
            if len(record.value or b"") > self.settings.max_message_bytes:
                raise ValueError("消息过大")
        except ValidationError, ValueError:
            await self._dead_letter(record, "INVALID_MESSAGE")
        else:
            if record.key != control.job_id.encode():
                await self._dead_letter(record, "KEY_MISMATCH")
            elif control.expires_at > datetime.now(UTC):
                # --- 缓存取消记录 ---
                self._prune_cancellations()
                self._cancelled[(control.job_id, control.attempt_id)] = control.expires_at
                # 防止取消缓存无限增长（异常积压保护）
                if len(self._cancelled) > 10_000:
                    raise ValueError("取消缓存达到上限，需检查过期时间与积压")
                # --- 如果当前命令匹配，立即取消执行 ---
                if self._command and (control.job_id, control.attempt_id) == (
                    self._command.job_id,
                    self._command.attempt_id,
                ):
                    self._abort_reason = "USER_CANCEL"
                    if self._active:
                        self._active.cancel()
        # 控制消息始终 commit，不阻塞后续取消指令
        await self.controls.commit(
            {TopicPartition(record.topic, record.partition): record.offset + 1}
        )

    async def _consume_controls(self) -> None:
        """控制消费循环：串行处理每条控制消息。

        与命令消费不同，控制消息处理不依赖分区归属，
        且始终 commit（即使写入死信）。
        """
        while True:
            await self._apply_control(await self.controls.getone())

    async def _restore_controls(self) -> None:
        """先追平启动时的取消记录，再允许执行命令，避免离线期间的取消丢失。

        流程：
          1. 获取当前分配分区的 end_offsets。
          2. 循环拉取控制消息直到所有分区消费到 end_offsets。
          3. 每条消息通过 _apply_control 写入 _cancelled 缓存。
        超时保护：整体追平操作不超过 send_timeout_seconds。
        """
        boundaries = await self.controls.end_offsets(self.controls.assignment())
        async with asyncio.timeout(self.settings.send_timeout_seconds):
            while True:
                # 检查所有分区是否已消费到边界
                caught_up = all(
                    [
                        await self.controls.position(partition) >= offset
                        for partition, offset in boundaries.items()
                    ]
                )
                if caught_up:
                    return
                # 批量拉取控制消息
                batches = await self.controls.getmany(timeout_ms=1000, max_records=1000)
                for records in batches.values():
                    for record in records:
                        await self._apply_control(record)


# ============================================================================
# 基础设施异常
# ============================================================================


class KafkaDeliveryError(RuntimeError):
    """投递结果不确定时停止消费，不把基础设施故障伪装成可确认的业务失败。

    当 Producer 投递结果事件超时或 Kafka 返回错误时抛出此异常，
    消费循环捕获后不 commit 当前消息，消息将在重新分配后重放。
    """
