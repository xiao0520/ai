package org.ruoyi.service.knowledge;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.constant.AiSystemConfigKeys;
import org.ruoyi.domain.dto.knowledge.rag.IngestionResult;
import org.ruoyi.domain.entity.knowledge.KnowledgeArtifactGeneration;
import org.ruoyi.domain.entity.knowledge.KnowledgeIndexDocument;
import org.ruoyi.domain.entity.knowledge.KnowledgeParseJob;
import org.ruoyi.mapper.knowledge.KnowledgeArtifactMapper;
import org.ruoyi.support.AiRuntimeConfig;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Objects;

/**
 * 知识库索引产物发布服务。
 *
 * <p>在 Kafka 结果事务内验收清单摘要并发布索引成员。该服务只修改本地数据库，禁止访问
 * Qdrant 或 OSS，确保消息消费事务可以完整回滚。</p>
 */
@Service
@RequiredArgsConstructor
public class KnowledgeArtifactPublicationService {
    private final KnowledgeArtifactMapper artifacts;
    private final AiRuntimeConfig runtimeConfig;

    /**
     * 验收本次入库产物并替换索引中的文档成员。
     *
     * @param job 当前解析任务
     * @param result Python 返回的入库结果
     */
    public void publish(KnowledgeParseJob job, IngestionResult result) {
        KnowledgeArtifactGeneration generation = artifacts.selectGeneration(job.getGenerationId());
        if (generation == null || !matches(job, result, generation)) {
            throw new ServiceException("产物清单与当前执行批次不一致");
        }
        if (artifacts.markGenerationReady(generation.getGenerationId(), result.denseCount(),
            result.sparseCount(), result.visionCount()) != 1) {
            throw new ServiceException("产物批次状态不允许发布");
        }
        // 先记录新成员，再把旧 generation 延迟退休，确保查询始终至少保留一个可用版本。
        KnowledgeIndexDocument previous = artifacts.selectIndexDocument(generation.getIndexVersionId(),
            job.getDocumentId());
        KnowledgeIndexDocument member = new KnowledgeIndexDocument();
        member.setId(IdWorker.getId());
        member.setKnowledgeId(job.getKnowledgeId());
        member.setIndexVersionId(generation.getIndexVersionId());
        member.setDocumentId(job.getDocumentId());
        member.setDocumentVersionId(job.getDocumentVersionId());
        member.setGenerationId(job.getGenerationId());
        member.setManifestHash(result.manifestHash());
        member.setChunkCount(result.chunkCount());
        artifacts.upsertIndexDocument(member);
        if (previous != null && !Objects.equals(previous.getGenerationId(), job.getGenerationId())) {
            int hours = runtimeConfig.getRequiredPositiveInt(AiSystemConfigKeys.KNOWLEDGE_ARTIFACT_RETENTION_HOURS,
                24 * 365);
            Date cleanupAfter = Date.from(Instant.now().plus(hours, ChronoUnit.HOURS));
            artifacts.markGenerationRetired(previous.getGenerationId(), cleanupAfter);
        }
        artifacts.refreshIndexStats(generation.getIndexVersionId());
    }

    /**
     * 在任务及其 REBUILD 父任务已推进后，尝试完成索引发布。
     *
     * @param job 已成功发布产物的解析任务
     */
    public void finalizePublication(KnowledgeParseJob job) {
        KnowledgeArtifactGeneration generation = artifacts.selectGeneration(job.getGenerationId());
        if (generation == null) {
            throw new ServiceException("产物批次不存在");
        }
        artifacts.markIndexReadyIfComplete(generation.getIndexVersionId(), job.getKnowledgeId());
        artifacts.publishDocumentForActiveIndex(generation.getIndexVersionId(), job.getDocumentId(),
            job.getDocumentVersionId());
    }

    private boolean matches(KnowledgeParseJob job, IngestionResult result,
                            KnowledgeArtifactGeneration generation) {
        return Objects.equals(generation.getJobId(), job.getId())
            && Objects.equals(generation.getAttemptId(), job.getAttemptId())
            && Objects.equals(generation.getKnowledgeId(), job.getKnowledgeId())
            && Objects.equals(generation.getDocumentId(), job.getDocumentId())
            && Objects.equals(generation.getDocumentVersionId(), job.getDocumentVersionId())
            && Objects.equals(generation.getManifestOssId().toString(), result.manifestObjectId())
            && Objects.equals(generation.getManifestHash(), result.manifestHash())
            && Objects.equals(generation.getChunkCount(), result.chunkCount())
            && Objects.equals(generation.getVectorDimensions(), result.vectorDimensions())
            && Objects.equals(generation.getVisionVectorDimensions(), result.visionVectorDimensions())
            && Objects.equals(generation.getVisionCount(), result.visionCount())
            && Objects.equals(result.chunkCount(), result.denseCount())
            && Objects.equals(result.chunkCount(), result.sparseCount())
            && ("UPLOADED".equals(generation.getStatus()) || "READY".equals(generation.getStatus()));
    }
}
