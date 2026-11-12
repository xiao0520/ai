package org.ruoyi.service.knowledge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.service.OssService;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.domain.entity.knowledge.KnowledgeArtifactGeneration;
import org.ruoyi.domain.entity.knowledge.KnowledgeDocument;
import org.ruoyi.domain.entity.knowledge.KnowledgeInfo;
import org.ruoyi.integration.kafka.KnowledgeKafkaProperties;
import org.ruoyi.mapper.knowledge.KnowledgeArtifactMapper;
import org.ruoyi.mapper.knowledge.KnowledgeDeletionMapper;
import org.ruoyi.mapper.knowledge.KnowledgeDocumentMapper;
import org.ruoyi.mapper.knowledge.KnowledgeDocumentVersionMapper;
import org.ruoyi.mapper.knowledge.KnowledgeInfoMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.List;

/**
 * 编排知识库、文档和索引版本的完整删除生命周期。
 *
 * <p>删除请求先阻断检索并解除索引成员，再把 generation 交给 Kafka/Python 清理 Qdrant。
 * 源文件和产物清单 OSS 均确认删除后，周期维护才物理清理数据库记录。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDeletionService {

    private static final String GENERATION_CLEANED = "CLEANED";

    private final KnowledgeInfoMapper knowledgeMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentVersionMapper versionMapper;
    private final KnowledgeArtifactMapper artifactMapper;
    private final KnowledgeDeletionMapper deletionMapper;
    private final KnowledgeKafkaProperties kafkaProperties;
    private final OssService ossService;

    /** 请求彻底删除一个知识库及其全部关联资源。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean requestKnowledgeDeletion(Long knowledgeId) {
        KnowledgeInfo current = knowledgeMapper.selectById(knowledgeId);
        if (current == null) {
            throw new ServiceException("知识库不存在");
        }
        if (knowledgeMapper.selectIdForUpdate(knowledgeId) == null) {
            throw new ServiceException("知识库不存在");
        }
        requireNoActiveKnowledgeJobs(knowledgeId);

        List<KnowledgeArtifactGeneration> generations =
            deletionMapper.selectGenerationsByKnowledge(knowledgeId);
        requireCleanupExecutor();
        List<Long> ossIds = versionMapper.selectOssIdsByKnowledgeId(knowledgeId)
            .stream().distinct().toList();

        // 先让所有内容立即退出检索，并解除 generation 的发布引用。
        deletionMapper.markDocumentsDeletedByKnowledge(
            knowledgeId, AiDomainConstants.KNOWLEDGE_STATUS_DELETED);
        deletionMapper.markDocumentVersionsDeletedByKnowledge(
            knowledgeId, AiDomainConstants.KNOWLEDGE_STATUS_DELETED);
        deletionMapper.markIndexVersionsDeletedByKnowledge(
            knowledgeId, AiDomainConstants.KNOWLEDGE_STATUS_DELETED);
        deletionMapper.deleteIndexDocumentsByKnowledge(knowledgeId);
        deletionMapper.deleteAclByKnowledge(knowledgeId);
        deletionMapper.deleteAgentBindingsByKnowledge(knowledgeId);
        if (knowledgeMapper.deleteById(knowledgeId) == 0) {
            throw new ServiceException("知识库删除状态更新失败");
        }

        retireGenerations(generations);
        deleteOssFilesAfterCommit(ossIds);
        return true;
    }

    /** 请求彻底删除一个逻辑文档及其全部版本和索引产物。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean requestDocumentDeletion(Long documentId) {
        KnowledgeDocument snapshot = documentMapper.selectById(documentId);
        if (snapshot == null || AiDomainConstants.KNOWLEDGE_STATUS_DELETED.equals(snapshot.getStatus())) {
            return false;
        }
        if (knowledgeMapper.selectIdForUpdate(snapshot.getKnowledgeId()) == null) {
            throw new ServiceException("知识库不存在");
        }
        KnowledgeDocument document = documentMapper.selectByIdForUpdate(documentId);
        if (document == null || AiDomainConstants.KNOWLEDGE_STATUS_DELETED.equals(document.getStatus())) {
            return false;
        }
        requireNoActiveDocumentJobs(documentId);

        List<Long> indexIds = deletionMapper.selectIndexVersionIdsByDocument(documentId);
        List<KnowledgeArtifactGeneration> generations =
            deletionMapper.selectGenerationsByDocument(documentId);
        requireCleanupExecutor();
        List<Long> ossIds = versionMapper.selectOssIdsByDocumentId(documentId)
            .stream().distinct().toList();

        // 文档和全部版本先退出在线状态，再解除各索引版本中的成员关系。
        if (documentMapper.updateStatus(
            documentId, AiDomainConstants.KNOWLEDGE_STATUS_DELETED) == 0) {
            throw new ServiceException("文档删除状态更新失败");
        }
        if (versionMapper.updateParseStatusByDocumentId(
            documentId, AiDomainConstants.KNOWLEDGE_STATUS_DELETED) == 0) {
            throw new ServiceException("文档版本删除状态更新失败");
        }
        deletionMapper.deleteIndexDocumentsByDocument(documentId);
        indexIds.forEach(artifactMapper::refreshIndexStats);

        retireGenerations(generations);
        deleteOssFilesAfterCommit(ossIds);
        return true;
    }

    /** 重试删除已进入删除状态的文档源文件。 */
    public void cleanupDeletedSourceFiles(int limit) {
        for (Long ossId : deletionMapper.selectDeletedSourceOssIds(
            limit, AiDomainConstants.KNOWLEDGE_STATUS_DELETED)) {
            deleteOssFile(ossId);
        }
    }

    /** 在外部资源均确认清理后物理删除剩余数据库记录。 */
    @Transactional(rollbackFor = Exception.class)
    public void purgeDeletedResources(int limit) {
        for (Long documentId : deletionMapper.selectPurgeableDeletedDocumentIds(
            limit, AiDomainConstants.KNOWLEDGE_STATUS_DELETED, GENERATION_CLEANED,
            AiDomainConstants.KNOWLEDGE_PARSE_JOB_ACTIVE_STATUSES)) {
            purgeDocument(documentId);
        }
        for (Long indexVersionId : deletionMapper.selectPurgeableDeletedIndexVersionIds(
            limit, AiDomainConstants.KNOWLEDGE_STATUS_DELETED, GENERATION_CLEANED,
            AiDomainConstants.KNOWLEDGE_PARSE_JOB_ACTIVE_STATUSES)) {
            deletionMapper.deleteOutboxByIndex(indexVersionId);
            deletionMapper.deleteJobsByIndex(indexVersionId);
            deletionMapper.deleteCleanedGenerationsByIndex(indexVersionId, GENERATION_CLEANED);
            deletionMapper.deleteIndexVersion(
                indexVersionId, AiDomainConstants.KNOWLEDGE_STATUS_DELETED);
        }
        for (Long knowledgeId : deletionMapper.selectPurgeableDeletedKnowledgeIds(
            limit, AiDomainConstants.KNOWLEDGE_STATUS_DELETED, GENERATION_CLEANED,
            AiDomainConstants.KNOWLEDGE_PARSE_JOB_ACTIVE_STATUSES)) {
            purgeKnowledge(knowledgeId);
        }
    }

    private void purgeDocument(Long documentId) {
        deletionMapper.deleteOutboxByDocument(documentId);
        deletionMapper.deleteJobsByDocument(documentId);
        deletionMapper.deleteCleanedGenerationsByDocument(documentId, GENERATION_CLEANED);
        deletionMapper.deleteDocumentVersions(documentId);
        deletionMapper.deleteDocument(
            documentId, AiDomainConstants.KNOWLEDGE_STATUS_DELETED);
    }

    private void purgeKnowledge(Long knowledgeId) {
        deletionMapper.deleteOutboxByKnowledge(knowledgeId);
        deletionMapper.deleteCleanedGenerationsByKnowledge(knowledgeId, GENERATION_CLEANED);
        deletionMapper.deleteJobsByKnowledge(knowledgeId);
        deletionMapper.deleteAclByKnowledge(knowledgeId);
        deletionMapper.deleteAgentBindingsByKnowledge(knowledgeId);
        deletionMapper.deleteIndexVersionsByKnowledge(
            knowledgeId, AiDomainConstants.KNOWLEDGE_STATUS_DELETED);
        deletionMapper.deleteKnowledgePhysically(knowledgeId);
    }

    private void requireNoActiveKnowledgeJobs(Long knowledgeId) {
        if (deletionMapper.countActiveJobsByKnowledge(
            knowledgeId, AiDomainConstants.KNOWLEDGE_PARSE_JOB_ACTIVE_STATUSES) > 0) {
            throw new ServiceException("知识库仍有运行中的解析或清理任务，请等待任务结束后再删除");
        }
    }

    private void requireNoActiveDocumentJobs(Long documentId) {
        if (deletionMapper.countActiveJobsByDocument(
            documentId, AiDomainConstants.KNOWLEDGE_PARSE_JOB_ACTIVE_STATUSES) > 0) {
            throw new ServiceException("文档仍有运行中的解析任务，请等待任务结束或取消后再删除");
        }
    }

    private void requireCleanupExecutor() {
        if (!kafkaProperties.isEnabled()) {
            throw new ServiceException("彻底删除依赖 Kafka Worker 和周期清理任务，请先启用执行服务再删除");
        }
    }

    private void retireGenerations(List<KnowledgeArtifactGeneration> generations) {
        Date cleanupAfter = new Date();
        for (KnowledgeArtifactGeneration generation : generations) {
            if (isCleaned(generation.getStatus())) {
                continue;
            }
            if (artifactMapper.markGenerationRetired(
                generation.getGenerationId(), cleanupAfter) == 0) {
                throw new ServiceException("索引产物仍被引用，删除失败");
            }
        }
    }

    private boolean isCleaned(String status) {
        return "VECTOR_CLEANED".equals(status) || GENERATION_CLEANED.equals(status);
    }

    private void deleteOssFilesAfterCommit(List<Long> ossIds) {
        if (ossIds.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            ossIds.forEach(this::deleteOssFile);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ossIds.forEach(KnowledgeDeletionService.this::deleteOssFile);
            }
        });
    }

    private void deleteOssFile(Long ossId) {
        try {
            ossService.deleteFile(ossId);
        } catch (RuntimeException error) {
            log.warn("知识库删除后的源文件 OSS 清理失败，将由周期任务重试 ossId={}", ossId, error);
        }
    }
}
