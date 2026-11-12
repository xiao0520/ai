package org.ruoyi.service.knowledge.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.constant.AiSystemConfigKeys;
import org.ruoyi.domain.bo.knowledge.KnowledgeParseJobBo;
import org.ruoyi.domain.dto.knowledge.RagContractJson;
import org.ruoyi.domain.dto.knowledge.rag.DocumentProcessingSnapshot;
import org.ruoyi.domain.entity.knowledge.KnowledgeDocument;
import org.ruoyi.domain.entity.knowledge.KnowledgeDocumentVersion;
import org.ruoyi.domain.entity.knowledge.KnowledgeParseJob;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeParseJobVo;
import org.ruoyi.integration.kafka.KnowledgeKafkaService;
import org.ruoyi.mapper.knowledge.KnowledgeArtifactMapper;
import org.ruoyi.mapper.knowledge.KnowledgeDocumentMapper;
import org.ruoyi.mapper.knowledge.KnowledgeDocumentVersionMapper;
import org.ruoyi.mapper.knowledge.KnowledgeIndexVersionMapper;
import org.ruoyi.mapper.knowledge.KnowledgeKafkaJobMapper;
import org.ruoyi.mapper.knowledge.KnowledgeParseJobMapper;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.knowledge.IKnowledgeParseJobService;
import org.ruoyi.service.knowledge.KnowledgeDocumentProcessingSnapshotFactory;
import org.ruoyi.service.knowledge.KnowledgeExecutionSnapshotFactory;
import org.ruoyi.support.AiRuntimeConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** 知识库解析任务管理与 REBUILD 父子编排。 */
@Service
@RequiredArgsConstructor
public class KnowledgeParseJobServiceImpl implements IKnowledgeParseJobService {
    private final KnowledgeParseJobMapper baseMapper;
    private final KnowledgeKafkaJobMapper kafkaJobs;
    private final KnowledgeArtifactMapper artifacts;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentVersionMapper documentVersionMapper;
    private final KnowledgeIndexVersionMapper indexVersionMapper;
    private final IKnowledgeInfoService knowledgeInfoService;
    private final AiRuntimeConfig runtimeConfig;
    private final KnowledgeKafkaService kafkaService;
    private final KnowledgeExecutionSnapshotFactory executionSnapshots;
    private final KnowledgeDocumentProcessingSnapshotFactory processingSnapshots;
    private final RagContractJson codec;

    @Override
    public KnowledgeParseJobVo queryById(Long id) { return baseMapper.selectVoById(id); }

    @Override
    public TableDataInfo<KnowledgeParseJobVo> queryPageList(KnowledgeParseJobBo bo, PageQuery pageQuery) {
        Page<KnowledgeParseJobVo> page = pageQuery.build();
        page.setRecords(baseMapper.selectJobList(page, bo));
        return TableDataInfo.build(page);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeParseJobVo submitDocumentVersion(Long documentVersionId) {
        KnowledgeDocumentVersion version = documentVersionMapper.selectById(documentVersionId);
        if (version == null || AiDomainConstants.KNOWLEDGE_STATUS_DELETED.equals(version.getParseStatus())) {
            throw new ServiceException("知识库文档版本不存在");
        }
        KnowledgeDocument document = documentMapper.selectById(version.getDocumentId());
        if (document == null || AiDomainConstants.KNOWLEDGE_STATUS_DELETED.equals(document.getStatus())) {
            throw new ServiceException("知识库文档不存在");
        }
        knowledgeInfoService.lockForIndexActivation(version.getKnowledgeId());
        // 获取知识库行锁后重新读取，防止删除请求与任务提交并发时使用锁前的旧状态。
        version = documentVersionMapper.selectById(documentVersionId);
        document = version == null ? null : documentMapper.selectById(version.getDocumentId());
        if (version == null || AiDomainConstants.KNOWLEDGE_STATUS_DELETED.equals(version.getParseStatus())
            || document == null || AiDomainConstants.KNOWLEDGE_STATUS_DELETED.equals(document.getStatus())) {
            throw new ServiceException("知识库文档或版本已删除");
        }
        requireTargetIndex(version.getKnowledgeId(), version.getTargetIndexVersion());
        KnowledgeInfoVo knowledge = knowledgeInfoService.queryById(version.getKnowledgeId());
        if (knowledge == null || !SystemConstants.NORMAL.equals(knowledge.getStatus())) {
            throw new ServiceException("知识库不存在或已停用");
        }
        if (StringUtils.isBlank(version.getTargetIndexVersion())
            || StringUtils.isBlank(version.getDocumentProcessingSnapshot())
            || version.getConfigurationRevision() == null) {
            throw new ServiceException("文档版本尚未配置处理模型");
        }
        return submit(AiDomainConstants.KNOWLEDGE_PARSE_JOB_TYPE_INGESTION, version.getKnowledgeId(),
            document.getId(), version.getId(), version.getTargetIndexVersion(),
            version.getConfigurationRevision(), version.getDocumentProcessingSnapshot(), null, null);
    }

    /** Java 固化当前发布文档清单，并为每个版本创建普通 INGESTION 子任务。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeParseJobVo submitRebuild(Long knowledgeId, String targetIndexVersion) {
        knowledgeInfoService.lockForIndexActivation(knowledgeId);
        requireTargetIndex(knowledgeId, targetIndexVersion);
        KnowledgeInfoVo knowledge = knowledgeInfoService.queryById(knowledgeId);
        if (knowledge == null || !SystemConstants.NORMAL.equals(knowledge.getStatus())) {
            throw new ServiceException("知识库不存在或已停用");
        }
        if (StringUtils.isBlank(targetIndexVersion)) {
            throw new ServiceException("目标索引版本不能为空");
        }
        String idempotency = String.join(":", "REBUILD", knowledgeId.toString(), targetIndexVersion);
        KnowledgeParseJob existing = baseMapper.selectByIdempotencyKey(idempotency);
        if (existing != null) {
            return baseMapper.selectVoById(existing.getId());
        }

        KnowledgeParseJob parent = newJob(AiDomainConstants.KNOWLEDGE_PARSE_JOB_TYPE_REBUILD, knowledgeId,
            null, null, targetIndexVersion, idempotency, null, null);
        parent.setStatus("RUNNING");
        parent.setStartedAt(new Date());
        parent.setBudgetStatus("NONE");
        baseMapper.insert(parent);
        KnowledgeExecutionSnapshotFactory.SnapshotBundle frozen = executionSnapshots.create(parent);
        parent.setIndexSnapshot(encode(frozen.indexSnapshot()));
        baseMapper.updateById(parent);

        List<KnowledgeDocumentVersion> versions =
            documentVersionMapper.selectCurrentPublishedByKnowledgeId(knowledgeId);
        for (KnowledgeDocumentVersion version : versions) {
            DocumentProcessingSnapshot retargeted = processingSnapshots.retarget(
                version.getDocumentProcessingSnapshot(), frozen.indexSnapshot());
            String childKey = String.join(":", "REBUILD_CHILD", parent.getId().toString(), version.getId().toString());
            KnowledgeParseJob child = newJob(AiDomainConstants.KNOWLEDGE_PARSE_JOB_TYPE_INGESTION, knowledgeId,
                version.getDocumentId(), version.getId(), targetIndexVersion, childKey,
                parent.getId(), encode(retargeted));
            child.setIndexSnapshot(parent.getIndexSnapshot());
            child.setCreateBy(parent.getCreateBy());
            child.setCreateDept(parent.getCreateDept());
            baseMapper.insert(child);
            kafkaService.enqueueInitial(child);
        }
        if (versions.isEmpty()) {
            parent.setStatus("SUCCEEDED");
            parent.setProgress(100);
            parent.setFinishedAt(new Date());
            baseMapper.updateById(parent);
            artifacts.markIndexReadyIfComplete(Long.valueOf(frozen.indexSnapshot().indexVersion()), knowledgeId);
        } else {
            kafkaJobs.refreshRebuildParent(parent.getId());
        }
        return baseMapper.selectVoById(parent.getId());
    }

    /**
     * 按业务唯一键幂等创建任务，并仅在首次创建时投递 Kafka。
     */
    private KnowledgeParseJobVo submit(String type, Long knowledgeId, Long documentId, Long documentVersionId,
                                       String targetIndexVersion, Integer configurationRevision,
                                       String documentProcessingSnapshot, Long parentJobId, String forcedKey) {
        String idempotencyKey = forcedKey == null ? String.join(":", type, String.valueOf(knowledgeId),
            String.valueOf(documentId == null ? 0 : documentId),
            String.valueOf(documentVersionId == null ? 0 : documentVersionId),
            StringUtils.defaultIfBlank(targetIndexVersion, AiDomainConstants.KNOWLEDGE_PARSE_JOB_NO_ACTIVE_INDEX),
            String.valueOf(configurationRevision == null ? 0 : configurationRevision)) : forcedKey;
        KnowledgeParseJob existing = baseMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return baseMapper.selectVoById(existing.getId());
        }
        KnowledgeParseJob job = newJob(type, knowledgeId, documentId, documentVersionId,
            targetIndexVersion, idempotencyKey, parentJobId, documentProcessingSnapshot);
        KnowledgeExecutionSnapshotFactory.SnapshotBundle frozen = executionSnapshots.create(job);
        job.setIndexSnapshot(encode(frozen.indexSnapshot()));
        baseMapper.insert(job);
        kafkaService.enqueueInitial(job);
        return baseMapper.selectVoById(job.getId());
    }

    /** 锁内校验目标索引仍可接收新任务。 */
    private void requireTargetIndex(Long knowledgeId, String targetIndexVersion) {
        if (StringUtils.isBlank(targetIndexVersion)) {
            throw new ServiceException("目标索引版本不能为空");
        }
        var index = indexVersionMapper.selectByKnowledgeAndVersionForUpdate(knowledgeId, targetIndexVersion);
        if (index == null || AiDomainConstants.KNOWLEDGE_STATUS_DELETED.equals(index.getStatus())) {
            throw new ServiceException("目标索引版本不存在或已删除");
        }
    }

    /**
     * 构造统一的任务初始状态和请求追踪字段。
     */
    private KnowledgeParseJob newJob(String type, Long knowledgeId, Long documentId, Long documentVersionId,
                                     String targetIndexVersion, String idempotencyKey, Long parentJobId,
                                     String documentProcessingSnapshot) {
        KnowledgeParseJob job = new KnowledgeParseJob();
        job.setRequestId(UUID.randomUUID().toString());
        job.setJobType(type);
        job.setKnowledgeId(knowledgeId);
        job.setDocumentId(documentId);
        job.setDocumentVersionId(documentVersionId);
        job.setTargetIndexVersion(targetIndexVersion);
        job.setDocumentProcessingSnapshot(documentProcessingSnapshot);
        job.setParentJobId(parentJobId);
        job.setIdempotencyKey(idempotencyKey);
        job.setStatus(AiDomainConstants.STATUS_PENDING);
        job.setProgress(0);
        job.setAttemptCount(0);
        job.setMaxAttempts(runtimeConfig.getRequiredPositiveInt(AiSystemConfigKeys.KNOWLEDGE_PARSE_MAX_ATTEMPTS,
            AiDomainConstants.KNOWLEDGE_PARSE_JOB_MAX_ATTEMPTS_LIMIT));
        job.setLastSequence(0L);
        return job;
    }

    /**
     * 按共享机器契约序列化维护任务冻结参数。
     */
    private String encode(Object value) {
        try { return new String(codec.write(value), StandardCharsets.UTF_8); }
        catch (Exception error) { throw new ServiceException("知识库任务快照不符合机器契约"); }
    }

    @Override
    public Boolean cancel(Long id) {
        if (kafkaService.enabled()) {
            return kafkaService.cancel(id);
        }
        KnowledgeParseJob job = baseMapper.selectById(id);
        if (job == null) {
            throw new ServiceException("解析任务不存在");
        }
        if (AiDomainConstants.KNOWLEDGE_PARSE_JOB_TERMINAL_STATUSES.contains(job.getStatus())) {
            return false;
        }
        return baseMapper.cancelIfActive(id, AiDomainConstants.KNOWLEDGE_PARSE_JOB_STATUS_CANCELLED,
            AiDomainConstants.KNOWLEDGE_PARSE_JOB_ACTIVE_STATUSES) > 0;
    }

    @Override
    public Boolean retry(Long id) {
        if (kafkaService.enabled()) {
            return kafkaService.retry(id);
        }
        KnowledgeParseJob job = baseMapper.selectById(id);
        if (job == null) {
            throw new ServiceException("解析任务不存在");
        }
        if (!AiDomainConstants.KNOWLEDGE_PARSE_JOB_RETRYABLE_STATUSES.contains(job.getStatus())) {
            throw new ServiceException("只有失败或已取消的任务可以重试");
        }
        if (job.getAttemptCount() != null && job.getMaxAttempts() != null
            && job.getAttemptCount() >= job.getMaxAttempts()) {
            throw new ServiceException("任务已达到最大重试次数");
        }
        return baseMapper.retryIfRetryable(id, AiDomainConstants.STATUS_PENDING,
            (job.getAttemptCount() == null ? 0 : job.getAttemptCount()) + 1,
            AiDomainConstants.KNOWLEDGE_PARSE_JOB_LIST_VISIBLE,
            AiDomainConstants.KNOWLEDGE_PARSE_JOB_RETRYABLE_STATUSES) > 0;
    }

    @Override
    public Boolean hideFromList(Long id) {
        KnowledgeParseJob job = baseMapper.selectById(id);
        if (job == null) {
            throw new ServiceException("解析任务不存在");
        }
        if (!AiDomainConstants.KNOWLEDGE_PARSE_JOB_TERMINAL_STATUSES.contains(job.getStatus())) {
            throw new ServiceException("只有已成功、已失败或已取消的任务可以删除");
        }
        return baseMapper.hideTerminalById(
            id,
            AiDomainConstants.KNOWLEDGE_PARSE_JOB_LIST_VISIBLE,
            AiDomainConstants.KNOWLEDGE_PARSE_JOB_LIST_HIDDEN,
            AiDomainConstants.KNOWLEDGE_PARSE_JOB_TERMINAL_STATUSES) > 0;
    }
}
