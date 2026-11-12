package org.ruoyi.integration.kafka;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.constant.AiSystemConfigKeys;
import org.ruoyi.domain.dto.knowledge.RagContractJson;
import org.ruoyi.domain.dto.knowledge.rag.ExecutionUsageItem;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeArtifactsEvent;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeCancelledEvent;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeCleanupTask;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeDocumentTask;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeExecutionBudget;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeFailedEvent;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeJobCommand;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeJobCommandTask;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeJobControl;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeJobEvent;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeMaintenanceEvent;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeProgressEvent;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeReconcileTask;
import org.ruoyi.domain.entity.knowledge.KnowledgeArtifactGeneration;
import org.ruoyi.domain.entity.knowledge.KnowledgeDocument;
import org.ruoyi.domain.entity.knowledge.KnowledgeDocumentVersion;
import org.ruoyi.domain.entity.knowledge.KnowledgeIndexVersion;
import org.ruoyi.domain.entity.knowledge.KnowledgeParseJob;
import org.ruoyi.mapper.knowledge.KnowledgeArtifactMapper;
import org.ruoyi.mapper.knowledge.KnowledgeDocumentMapper;
import org.ruoyi.mapper.knowledge.KnowledgeDocumentVersionMapper;
import org.ruoyi.mapper.knowledge.KnowledgeIndexVersionMapper;
import org.ruoyi.mapper.knowledge.KnowledgeKafkaJobMapper;
import org.ruoyi.mapper.knowledge.KnowledgeOutboxMapper;
import org.ruoyi.mapper.knowledge.KnowledgeParseJobMapper;
import org.ruoyi.service.knowledge.KnowledgeArtifactMaintenanceService;
import org.ruoyi.service.knowledge.KnowledgeArtifactPublicationService;
import org.ruoyi.service.knowledge.KnowledgeExecutionSnapshotFactory;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.knowledge.KnowledgeJobUsageService;
import org.ruoyi.support.AiRuntimeConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Java 唯一维护任务状态；Outbox、产物生命周期和模型预算在短事务内推进。 */
@Service
@RequiredArgsConstructor
public class KnowledgeKafkaService {
    private static final Set<String> RETRYABLE = Set.of("TIMEOUT", "COMMAND_EXPIRED",
        "EMBEDDING_UNAVAILABLE", "VECTOR_UNAVAILABLE", "INTERNAL");
    private final KnowledgeKafkaJobMapper jobs;
    private final KnowledgeParseJobMapper parseJobs;
    private final KnowledgeOutboxMapper outbox;
    private final KnowledgeDocumentVersionMapper versions;
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeIndexVersionMapper indexVersions;
    private final KnowledgeArtifactMapper artifacts;
    private final IKnowledgeInfoService knowledgeInfoService;
    private final KnowledgeKafkaProperties properties;
    private final RagContractJson codec;
    private final KnowledgeExecutionSnapshotFactory snapshots;
    private final KnowledgeArtifactPublicationService publication;
    private final KnowledgeArtifactMaintenanceService maintenance;
    private final KnowledgeJobUsageService usage;
    private final AiRuntimeConfig runtimeConfig;

    public boolean enabled() { return properties.isEnabled(); }

    @Transactional(rollbackFor = Exception.class)
    public void confirmDelivery(KnowledgeOutboxMessage message, String token) {
        jobs.lockJob(message.jobId());
        if (outbox.markSent(message.id(), token) == 1 && message.topic().equals(properties.getCommandTopic())) {
            jobs.markQueued(message.jobId(), message.attemptId());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordDeliveryFailure(KnowledgeOutboxMessage message, String token) {
        KnowledgeParseJob job = jobs.lockJob(message.jobId());
        int delay = Math.min(300, 5 * (1 << Math.min(message.sendAttempts(), 6)));
        int updated = outbox.markFailure(message.id(), token, delay, properties.getPublishMaxAttempts());
        if (updated == 1 && message.sendAttempts() + 1 >= properties.getPublishMaxAttempts()
            && message.topic().equals(properties.getCommandTopic())
            && jobs.markDeliveryFailed(message.jobId(), message.attemptId()) == 1 && job != null) {
            usage.settle(job, List.of(), "FAILED", "KAFKA_DELIVERY_FAILED");
            updateDocumentState(job, "FAILED", "KAFKA_DELIVERY_FAILED", "消息投递失败，请恢复 Kafka 后重试");
            scheduleAttemptCleanup(job, KnowledgeCleanupTask.Reason.V_FAILED);
            refreshParent(job);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueInitial(KnowledgeParseJob job) {
        if (enabled()) prepareAttempt(job, 0);
    }

    /** 每个新 attempt 固化消息、快照和模型预算；Outbox 重发不重复预占。 */
    private void prepareAttempt(KnowledgeParseJob job, int delaySeconds) {
        if (job.getMaxAttempts() == null || job.getAttemptCount() >= job.getMaxAttempts()) {
            throw new ServiceException("任务已达到最大执行次数");
        }
        KnowledgeExecutionSnapshotFactory.SnapshotBundle execution = executionSnapshots(job);
        KnowledgeJobCommandTask task = task(job, execution.indexSnapshot().indexVersion());
        KnowledgeExecutionBudget budget = usage.reserve(job);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime deadline = now.plusSeconds(delaySeconds + properties.getCommandTtlSeconds());
        String attemptId = UUID.randomUUID().toString();
        long generationId = IdWorker.getId();
        String messageId = UUID.randomUUID().toString();
        KnowledgeJobCommand command = new KnowledgeJobCommand(KnowledgeJobCommand.ContractVersion.V_KNOWLEDGE_KAFKA_V1,
            messageId, job.getRequestId(), job.getId().toString(), job.getKnowledgeId().toString(), attemptId,
            Long.toString(generationId), job.getAttemptCount() + 1, now, deadline, task,
            execution.indexSnapshot(), execution.documentProcessingSnapshot(), budget);
        String payload = encode(command);
        job.setAttemptId(attemptId);
        job.setGenerationId(generationId);
        job.setAttemptCount(command.attemptNumber());
        job.setStatus(delaySeconds == 0 ? "PENDING" : "RETRY_WAIT");
        if (!(task instanceof KnowledgeDocumentTask)) job.setTaskPayload(encode(task));
        jobs.prepareAttempt(job, Date.from(deadline.toInstant()));
        outbox.insertMessage(new KnowledgeOutboxMessage(messageId, job.getId(), attemptId,
            properties.getCommandTopic(), job.getId().toString(), payload, 0), delaySeconds);
        updateDocumentState(job, "PENDING", null, null);
    }

    private KnowledgeJobCommandTask task(KnowledgeParseJob job, String targetIndexVersion) {
        if (AiDomainConstants.KNOWLEDGE_PARSE_JOB_TYPE_INGESTION.equals(job.getJobType())) {
            KnowledgeDocumentVersion version = versions.selectById(job.getDocumentVersionId());
            KnowledgeDocument document = documents.selectById(job.getDocumentId());
            if (version == null || document == null || "DELETED".equals(version.getParseStatus())
                || "DELETED".equals(document.getStatus())
                || !Objects.equals(version.getDocumentId(), job.getDocumentId())
                || !Objects.equals(version.getKnowledgeId(), job.getKnowledgeId())) {
                throw new ServiceException("任务关联的文档版本已失效");
            }
            if (version.getFileSize() == null || version.getFileSize() > Integer.MAX_VALUE
                || version.getOssId() == null || version.getFileHash() == null) {
                throw new ServiceException("文档文件信息不完整，请重新上传");
            }
            return new KnowledgeDocumentTask(KnowledgeDocumentTask.JobType.V_INGESTION,
                job.getDocumentId().toString(), version.getId().toString(), version.getOssId().toString(),
                version.getFileHash(), version.getMimeType() == null ? "application/octet-stream" : version.getMimeType(),
                version.getOriginalName(), Math.toIntExact(version.getFileSize()), targetIndexVersion);
        }
        if (AiDomainConstants.KNOWLEDGE_PARSE_JOB_TYPE_CLEANUP.equals(job.getJobType())) {
            KnowledgeCleanupTask frozen = decode(job.getTaskPayload(), KnowledgeCleanupTask.class);
            return new KnowledgeCleanupTask(frozen.jobType(), targetIndexVersion,
                frozen.targetGenerationId(), frozen.reason());
        }
        if (AiDomainConstants.KNOWLEDGE_PARSE_JOB_TYPE_RECONCILE.equals(job.getJobType())) {
            KnowledgeReconcileTask frozen = decode(job.getTaskPayload(), KnowledgeReconcileTask.class);
            return new KnowledgeReconcileTask(frozen.jobType(), targetIndexVersion,
                frozen.retainedGenerationIds());
        }
        throw new ServiceException("REBUILD 由 Java 父子任务编排，不投递到 Python");
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean retry(Long id) {
        KnowledgeParseJob candidate = parseJobs.selectById(id);
        if (candidate == null) throw new ServiceException("解析任务不存在");
        if (!AiDomainConstants.KNOWLEDGE_PARSE_JOB_TYPE_CLEANUP.equals(candidate.getJobType())) {
            knowledgeInfoService.lockForIndexActivation(candidate.getKnowledgeId());
        }
        KnowledgeParseJob job = jobs.lockJob(id);
        if (job == null) throw new ServiceException("解析任务不存在");
        requireRetryableIndex(job);
        if (!AiDomainConstants.KNOWLEDGE_PARSE_JOB_RETRYABLE_STATUSES.contains(job.getStatus())) {
            throw new ServiceException("只有失败或已取消的任务可以重试");
        }
        if (AiDomainConstants.KNOWLEDGE_PARSE_JOB_LIST_HIDDEN.equals(job.getListHidden())) {
            throw new ServiceException("已从列表删除的任务不能重试");
        }
        if (AiDomainConstants.KNOWLEDGE_PARSE_JOB_TYPE_REBUILD.equals(job.getJobType())) {
            throw new ServiceException("REBUILD 父任务请重新提交目标索引版本");
        }
        if (job.getAttemptId() != null) outbox.cancelCommands(id, job.getAttemptId(), properties.getCommandTopic());
        prepareAttempt(job, 0);
        refreshParent(job);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean cancel(Long id) {
        KnowledgeParseJob job = jobs.lockJob(id);
        if (job == null) throw new ServiceException("解析任务不存在");
        if (AiDomainConstants.KNOWLEDGE_PARSE_JOB_TERMINAL_STATUSES.contains(job.getStatus())) return false;
        if (AiDomainConstants.KNOWLEDGE_PARSE_JOB_TYPE_REBUILD.equals(job.getJobType())) {
            throw new ServiceException("请取消 REBUILD 的未完成子任务");
        }
        if (job.getAttemptId() == null) return false;
        enqueueCancel(job);
        jobs.requestCancel(id, properties.getExecutionLeaseSeconds());
        updateDocumentState(job, "PROCESSING", "CANCEL_REQUESTED", "正在停止解析任务");
        return true;
    }

    private void enqueueCancel(KnowledgeParseJob job) {
        outbox.cancelCommands(job.getId(), job.getAttemptId(), properties.getCommandTopic());
        String id = UUID.randomUUID().toString();
        KnowledgeJobControl control = new KnowledgeJobControl(KnowledgeJobControl.ContractVersion.V_KNOWLEDGE_KAFKA_V1,
            id, job.getId().toString(), job.getAttemptId(), KnowledgeJobControl.Action.V_CANCEL,
            OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(properties.getCommandTtlSeconds() + 300));
        outbox.insertMessage(new KnowledgeOutboxMessage(id, job.getId(), job.getAttemptId(),
            properties.getControlTopic(), job.getId().toString(), encode(control), 0), 0);
    }

    @Transactional(rollbackFor = Exception.class)
    public EventDisposition accept(KnowledgeJobEvent message) {
        KnowledgeParseJob job = jobs.lockJob(Long.valueOf(message.jobId()));
        if (job == null || !Objects.equals(job.getAttemptId(), message.attemptId())
            || !Objects.equals(String.valueOf(job.getGenerationId()), message.generationId())) return EventDisposition.STALE;
        if (!Objects.equals(job.getRequestId(), message.requestId())) return EventDisposition.REJECTED;
        if (job.getLeaseOwner() != null && !Objects.equals(job.getLeaseOwner(), message.workerId())) {
            return EventDisposition.STALE;
        }
        String hash = DigestUtil.sha256Hex(encode(message.event()));
        long last = job.getLastSequence() == null ? 0 : job.getLastSequence();
        if (message.sequence() <= last) {
            return message.sequence() == last && !Objects.equals(hash, job.getLastResultHash())
                ? EventDisposition.REJECTED : EventDisposition.DUPLICATE;
        }
        if (AiDomainConstants.KNOWLEDGE_PARSE_JOB_TERMINAL_STATUSES.contains(job.getStatus())) return EventDisposition.STALE;
        if (job.getLeaseExpireTime() != null && job.getLeaseExpireTime().before(new Date())) return EventDisposition.STALE;
        if ("CANCEL_REQUESTED".equals(job.getStatus())
            && (message.event() instanceof KnowledgeFailedEvent
                || message.event() instanceof KnowledgeArtifactsEvent
                || message.event() instanceof KnowledgeMaintenanceEvent)) {
            List<ExecutionUsageItem> terminalUsage = message.event() instanceof KnowledgeFailedEvent failed
                ? failed.usageItems() : message.event() instanceof KnowledgeArtifactsEvent ready
                    ? ready.result().usageItems() : List.of();
            usage.settle(job, terminalUsage, "CANCELED", "CANCELLED");
            jobs.finishCancelled(job.getId(), job.getAttemptId(), message.sequence(), hash, message.workerId());
            updateDocumentState(job, "FAILED", "CANCELLED", "解析任务已取消");
            scheduleAttemptCleanup(job, KnowledgeCleanupTask.Reason.V_CANCELLED);
            refreshParent(job);
            return EventDisposition.ACCEPTED;
        }

        if (message.event() instanceof KnowledgeFailedEvent failed) {
            fail(job, message.sequence(), hash, message.workerId(), failed.errorCode().value(), failed.usageItems());
        } else if (message.event() instanceof KnowledgeCancelledEvent cancelled) {
            usage.settle(job, cancelled.usageItems(), "CANCELED", "CANCELLED");
            jobs.finishCancelled(job.getId(), job.getAttemptId(), message.sequence(), hash, message.workerId());
            updateDocumentState(job, "FAILED", "CANCELLED", "解析任务已取消");
            scheduleAttemptCleanup(job, KnowledgeCleanupTask.Reason.V_CANCELLED);
            refreshParent(job);
        } else if (message.event() instanceof KnowledgeArtifactsEvent ready) {
            usage.settle(job, ready.result().usageItems(), "SUCCESS", null);
            publication.publish(job, ready.result());
            if (jobs.acceptEvent(job.getId(), job.getAttemptId(), message.sequence(), hash, "SUCCEEDED", 100,
                message.workerId(), properties.getExecutionLeaseSeconds(), null, null) != 1) {
                throw new ServiceException("知识库任务终态更新失败");
            }
            if (job.getDocumentVersionId() != null) {
                jobs.updateVersionState(job.getDocumentVersionId(), job.getDocumentId(), "READY", null, null);
            }
            refreshParent(job);
            publication.finalizePublication(job);
        } else if (message.event() instanceof KnowledgeMaintenanceEvent completed) {
            List<Long> cleanupIds = maintenance.accept(job, completed.result());
            for (Long generationId : cleanupIds) {
                enqueueCleanup(job, generationId, KnowledgeCleanupTask.Reason.V_ORPHANED);
            }
            if (jobs.acceptEvent(job.getId(), job.getAttemptId(), message.sequence(), hash, "SUCCEEDED", 100,
                message.workerId(), properties.getExecutionLeaseSeconds(), null, null) != 1) {
                throw new ServiceException("知识库维护任务终态更新失败");
            }
        } else {
            int progress = message.event() instanceof KnowledgeProgressEvent event ? event.progress() : 0;
            String next = "CANCEL_REQUESTED".equals(job.getStatus()) ? "CANCEL_REQUESTED" : "RUNNING";
            jobs.acceptEvent(job.getId(), job.getAttemptId(), message.sequence(), hash, next, progress,
                message.workerId(), properties.getExecutionLeaseSeconds(), null, null);
            if (!"CANCEL_REQUESTED".equals(next)) updateDocumentState(job, "PROCESSING", null, null);
        }
        return EventDisposition.ACCEPTED;
    }

    private void fail(KnowledgeParseJob job, long sequence, String hash, String worker, String code,
                      List<ExecutionUsageItem> usageItems) {
        String message = errorMessage(code);
        usage.settle(job, usageItems, "FAILED", code);
        jobs.acceptEvent(job.getId(), job.getAttemptId(), sequence, hash, "FAILED", 0, worker,
            properties.getExecutionLeaseSeconds(), code, message);
        updateDocumentState(job, "FAILED", code, message);
        scheduleAttemptCleanup(job, "TIMEOUT".equals(code)
            ? KnowledgeCleanupTask.Reason.V_EXPIRED : KnowledgeCleanupTask.Reason.V_FAILED);
        if (RETRYABLE.contains(code) && job.getAttemptCount() < job.getMaxAttempts()) {
            int delay = Math.min(1800, properties.getRetryBaseSeconds() * (1 << Math.min(job.getAttemptCount() - 1, 4)));
            try { prepareAttempt(job, delay); }
            catch (ServiceException invalidResource) { /* 已接受失败结果，资源失效时不制造毒消息。 */ }
        }
        refreshParent(job);
    }

    @Transactional(rollbackFor = Exception.class)
    public void expire(Long id) {
        KnowledgeParseJob job = jobs.lockJob(id);
        if (job == null || job.getAttemptId() == null || job.getLeaseExpireTime() == null
            || !job.getLeaseExpireTime().before(new Date())
            || !Set.of("RUNNING", "QUEUED", "PENDING", "RETRY_WAIT", "CANCEL_REQUESTED").contains(job.getStatus())) return;
        enqueueCancel(job);
        long sequence = (job.getLastSequence() == null ? 0 : job.getLastSequence()) + 1;
        String hash = DigestUtil.sha256Hex(job.getAttemptId() + ":lease-expired");
        if ("CANCEL_REQUESTED".equals(job.getStatus())) {
            usage.settle(job, List.of(), "CANCELED", "CANCELLED");
            jobs.finishCancelled(job.getId(), job.getAttemptId(), sequence, hash, "java-lease-monitor");
            scheduleAttemptCleanup(job, KnowledgeCleanupTask.Reason.V_CANCELLED);
            refreshParent(job);
        } else {
            fail(job, sequence, hash, "java-lease-monitor", "TIMEOUT", List.of());
        }
    }

    /** 重试普通任务前锁定并校验目标索引；清理任务允许继续访问已删除索引的冻结快照。 */
    private void requireRetryableIndex(KnowledgeParseJob job) {
        if (AiDomainConstants.KNOWLEDGE_PARSE_JOB_TYPE_CLEANUP.equals(job.getJobType())) {
            return;
        }
        KnowledgeIndexVersion index = indexVersions.selectByKnowledgeAndVersionForUpdate(
            job.getKnowledgeId(), job.getTargetIndexVersion());
        if (index == null || AiDomainConstants.KNOWLEDGE_STATUS_DELETED.equals(index.getStatus())) {
            throw new ServiceException("目标索引版本不存在或已删除，不能重试");
        }
    }

    /** 扫描退役 generation，并按小时冻结每个索引的 Qdrant 保留集合。 */
    @Transactional(rollbackFor = Exception.class)
    public void scheduleMaintenance() {
        for (KnowledgeArtifactGeneration generation : artifacts.selectCleanupCandidates(properties.getBatchSize())) {
            KnowledgeParseJob source = parseJobs.selectById(generation.getJobId());
            if (source != null) enqueueCleanup(source, generation.getGenerationId(),
                KnowledgeCleanupTask.Reason.V_SUPERSEDED);
        }
        String bucket = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
        for (KnowledgeIndexVersion candidate : indexVersions.selectMaintenanceCandidates(properties.getBatchSize())) {
            knowledgeInfoService.lockForIndexActivation(candidate.getKnowledgeId());
            KnowledgeIndexVersion index = indexVersions.selectByKnowledgeAndVersionForUpdate(
                candidate.getKnowledgeId(), candidate.getIndexVersion());
            if (index == null || !Set.of(AiDomainConstants.STATUS_BUILDING, AiDomainConstants.STATUS_READY,
                AiDomainConstants.STATUS_ACTIVE).contains(index.getStatus())) {
                continue;
            }
            String key = String.join(":", "RECONCILE", index.getId().toString(), bucket);
            if (parseJobs.selectByIdempotencyKey(key) != null) continue;
            KnowledgeParseJob job = maintenanceJob(AiDomainConstants.KNOWLEDGE_PARSE_JOB_TYPE_RECONCILE,
                index.getKnowledgeId(), index.getIndexVersion(), key);
            KnowledgeExecutionSnapshotFactory.SnapshotBundle frozen = snapshots.create(job);
            job.setIndexSnapshot(encode(frozen.indexSnapshot()));
            List<String> retained = artifacts.selectRetainedGenerationIds(index.getId()).stream()
                .map(String::valueOf).toList();
            job.setTaskPayload(encode(new KnowledgeReconcileTask(KnowledgeReconcileTask.JobType.V_RECONCILE,
                frozen.indexSnapshot().indexVersion(), retained)));
            parseJobs.insert(job);
            prepareAttempt(job, 0);
        }
    }

    private void scheduleAttemptCleanup(KnowledgeParseJob job, KnowledgeCleanupTask.Reason reason) {
        if (AiDomainConstants.KNOWLEDGE_PARSE_JOB_TYPE_INGESTION.equals(job.getJobType())
            && job.getGenerationId() != null) {
            artifacts.markGenerationOrphaned(job.getGenerationId(), new Date(), reason.value());
            try {
                enqueueCleanup(job, job.getGenerationId(), reason);
            } catch (ServiceException unavailableSnapshot) {
                // 终态回报优先提交；已有产物行仍由周期扫描在配置恢复后补建清理任务。
            }
        }
    }

    private void enqueueCleanup(KnowledgeParseJob source, Long generationId, KnowledgeCleanupTask.Reason reason) {
        String key = String.join(":", "CLEANUP", generationId.toString());
        if (parseJobs.selectByIdempotencyKey(key) != null) return;
        KnowledgeParseJob cleanup = maintenanceJob(AiDomainConstants.KNOWLEDGE_PARSE_JOB_TYPE_CLEANUP,
            source.getKnowledgeId(), source.getTargetIndexVersion(), key);
        cleanup.setIndexSnapshot(source.getIndexSnapshot());
        KnowledgeExecutionSnapshotFactory.SnapshotBundle frozen = executionSnapshots(cleanup);
        cleanup.setTaskPayload(encode(new KnowledgeCleanupTask(KnowledgeCleanupTask.JobType.V_CLEANUP,
            frozen.indexSnapshot().indexVersion(), generationId.toString(), reason)));
        parseJobs.insert(cleanup);
        prepareAttempt(cleanup, 0);
    }

    private KnowledgeParseJob maintenanceJob(String type, Long knowledgeId, String targetIndexVersion, String key) {
        KnowledgeParseJob job = new KnowledgeParseJob();
        job.setRequestId(UUID.randomUUID().toString());
        job.setJobType(type);
        job.setKnowledgeId(knowledgeId);
        job.setTargetIndexVersion(targetIndexVersion);
        job.setIdempotencyKey(key);
        job.setStatus("PENDING");
        job.setProgress(0);
        job.setAttemptCount(0);
        job.setMaxAttempts(runtimeConfig.getRequiredPositiveInt(AiSystemConfigKeys.KNOWLEDGE_PARSE_MAX_ATTEMPTS,
            AiDomainConstants.KNOWLEDGE_PARSE_JOB_MAX_ATTEMPTS_LIMIT));
        job.setLastSequence(0L);
        job.setBudgetStatus("NONE");
        return job;
    }

    private void refreshParent(KnowledgeParseJob job) {
        if (job.getParentJobId() != null) jobs.refreshRebuildParent(job.getParentJobId());
    }

    private void updateDocumentState(KnowledgeParseJob job, String status, String code, String message) {
        if (job.getDocumentVersionId() != null) {
            jobs.updateVersionState(job.getDocumentVersionId(), job.getDocumentId(), status, code, message);
            jobs.updateUnpublishedDocument(job.getDocumentId(), status);
        }
    }

    private KnowledgeExecutionSnapshotFactory.SnapshotBundle executionSnapshots(KnowledgeParseJob job) {
        if (StringUtils.isBlank(job.getIndexSnapshot())) {
            KnowledgeExecutionSnapshotFactory.SnapshotBundle created = snapshots.create(job);
            job.setIndexSnapshot(encode(created.indexSnapshot()));
            return created;
        }
        try {
            var index = codec.read(job.getIndexSnapshot().getBytes(StandardCharsets.UTF_8),
                org.ruoyi.domain.dto.knowledge.rag.IndexSnapshot.class);
            var documentProcessing = StringUtils.isBlank(job.getDocumentProcessingSnapshot()) ? null
                : codec.read(job.getDocumentProcessingSnapshot().getBytes(StandardCharsets.UTF_8),
                    org.ruoyi.domain.dto.knowledge.rag.DocumentProcessingSnapshot.class);
            return new KnowledgeExecutionSnapshotFactory.SnapshotBundle(index, documentProcessing);
        } catch (Exception error) { throw new ServiceException("任务绑定的索引快照损坏"); }
    }

    private <T> T decode(String value, Class<T> type) {
        if (StringUtils.isBlank(value)) throw new ServiceException("维护任务参数缺失");
        try { return codec.read(value.getBytes(StandardCharsets.UTF_8), type); }
        catch (Exception error) { throw new ServiceException("维护任务参数损坏"); }
    }

    private String encode(Object value) {
        try { return new String(codec.write(value), StandardCharsets.UTF_8); }
        catch (JsonProcessingException | IllegalArgumentException error) {
            throw new ServiceException("知识库任务契约不完整，请检查文件和索引配置");
        }
    }

    private String errorMessage(String code) {
        return switch (code) {
            case "DOCUMENT_UNSUPPORTED" -> "当前文件格式暂不支持解析";
            case "PARSE_FAILED" -> "文档解析失败，请检查文件后重试";
            case "MODEL_UNAVAILABLE" -> "任务模型不存在、配置无效或当前密钥无调用权限";
            case "INDEX_MISMATCH" -> "任务索引与产物配置不一致";
            case "BUDGET_EXCEEDED" -> "任务执行预算不足";
            case "TIMEOUT", "COMMAND_EXPIRED" -> "任务超时，按执行次数限制重试";
            default -> "知识库执行服务暂不可用，请稍后重试";
        };
    }

    public enum EventDisposition { ACCEPTED, DUPLICATE, STALE, REJECTED }
}
