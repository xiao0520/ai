package org.ruoyi.service.knowledge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.domain.dto.knowledge.RagContractJson;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeCleanupTask;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeMaintenanceResult;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeReconcileTask;
import org.ruoyi.domain.entity.knowledge.KnowledgeArtifactGeneration;
import org.ruoyi.domain.entity.knowledge.KnowledgeParseJob;
import org.ruoyi.mapper.knowledge.KnowledgeArtifactMapper;
import org.ruoyi.system.service.ISysOssService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 知识库索引产物维护服务。
 *
 * <p>先验收 Python 对 Qdrant 的维护结果，再以可重试的第二阶段清理 OSS 清单，避免数据库状态
 * 早于向量数据变化。所有回报都必须与任务冻结范围一致，维护任务不能扩大清理范围。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeArtifactMaintenanceService {

    private static final String JOB_TYPE_CLEANUP = "CLEANUP";
    private static final String JOB_TYPE_RECONCILE = "RECONCILE";

    private final KnowledgeArtifactMapper artifacts;
    private final RagContractJson codec;
    private final ISysOssService ossService;

    /**
     * 验收一次 Qdrant 维护结果并推进本地产物状态。
     *
     * @param job 当前维护任务
     * @param result Python 返回的维护结果
     * @return 对账发现的 Qdrant 孤儿 generation，清理任务返回空列表
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Long> accept(KnowledgeParseJob job, KnowledgeMaintenanceResult result) {
        if (JOB_TYPE_CLEANUP.equals(job.getJobType())) {
            acceptCleanup(job, result);
            return List.of();
        }
        if (JOB_TYPE_RECONCILE.equals(job.getJobType())) {
            return acceptReconcile(job, result);
        }
        throw new ServiceException("非维护任务不能回报维护结果");
    }

    /**
     * 验收精确清理结果。Worker 必须只观察并删除任务指定的单个 generation。
     */
    private void acceptCleanup(KnowledgeParseJob job, KnowledgeMaintenanceResult result) {
        KnowledgeCleanupTask task = decode(job.getTaskPayload(), KnowledgeCleanupTask.class);
        if (result.jobType() != KnowledgeMaintenanceResult.JobType.V_CLEANUP
            || !Objects.equals(task.targetGenerationId(), result.targetGenerationId())) {
            throw new ServiceException("清理结果与冻结任务不一致");
        }
        Long generationId = Long.valueOf(task.targetGenerationId());
        Set<Long> observed = parseIds(result.observedGenerationIds());
        Set<Long> deleted = parseIds(result.deletedGenerationIds());
        if (observed.stream().anyMatch(id -> !generationId.equals(id))
            || deleted.stream().anyMatch(id -> !generationId.equals(id))) {
            throw new ServiceException("精确清理结果包含任务范围外的 generation");
        }
        boolean hadPoints = result.observedPointCount() > 0;
        if (!observed.equals(deleted)
            || !Objects.equals(result.observedPointCount(), result.deletedPointCount())
            || hadPoints != observed.contains(generationId)) {
            throw new ServiceException("精确清理结果的 generation 与点数不一致");
        }

        // 发布成员是最终保护条件，即使任务较旧也不能删除仍在使用的 generation。
        if (artifacts.countIndexReferences(generationId) > 0) {
            throw new ServiceException("generation 仍被已发布索引引用，拒绝清理");
        }
        KnowledgeArtifactGeneration generation = artifacts.selectGeneration(generationId);
        if (generation != null && artifacts.markGenerationVectorCleaned(generationId) != 1
            && !"VECTOR_CLEANED".equals(generation.getStatus()) && !"CLEANED".equals(generation.getStatus())) {
            throw new ServiceException("generation 状态不允许完成清理");
        }
    }

    /**
     * 验收只读对账结果，并返回未被当前索引保留的 Qdrant generation。
     */
    private List<Long> acceptReconcile(KnowledgeParseJob job, KnowledgeMaintenanceResult result) {
        KnowledgeReconcileTask task = decode(job.getTaskPayload(), KnowledgeReconcileTask.class);
        if (result.jobType() != KnowledgeMaintenanceResult.JobType.V_RECONCILE
            || result.targetGenerationId() != null || !result.deletedGenerationIds().isEmpty()
            || result.deletedPointCount() != 0) {
            throw new ServiceException("RECONCILE 只能盘点，不能直接删除 generation");
        }
        Long indexVersionId = Long.valueOf(task.targetIndexVersion());
        Set<Long> observed = parseIds(result.observedGenerationIds());
        List<Long> published = artifacts.selectIndexMemberGenerationIds(indexVersionId);
        if (!observed.containsAll(published)) {
            throw new ServiceException("Qdrant 缺少已发布索引成员，请阻止该索引继续发布");
        }

        // 数据库已不保留且 Qdrant 也不存在的数据，可直接推进到向量已清理状态。
        Set<Long> retainedNow = new HashSet<>(artifacts.selectRetainedGenerationIds(indexVersionId));
        for (KnowledgeArtifactGeneration generation : artifacts.selectGenerationsByIndex(indexVersionId)) {
            if (!retainedNow.contains(generation.getGenerationId())
                && !observed.contains(generation.getGenerationId())
                && artifacts.countIndexReferences(generation.getGenerationId()) == 0) {
                artifacts.markGenerationVectorCleaned(generation.getGenerationId());
            }
        }
        return observed.stream().filter(id -> !retainedNow.contains(id)).sorted().toList();
    }

    /**
     * 补偿删除已清理向量对应的 OSS 清单。
     *
     * <p>OSS 删除失败时保留 {@code VECTOR_CLEANED} 状态，由下一轮调度继续补偿。</p>
     *
     * @param limit 单轮最多处理的 generation 数量
     */
    public void cleanupManifests(int limit) {
        for (KnowledgeArtifactGeneration generation : artifacts.selectManifestCleanupCandidates(limit)) {
            try {
                if (generation.getManifestOssId() != null
                    && ossService.getById(generation.getManifestOssId()) != null
                    && !Boolean.TRUE.equals(ossService.deleteWithValidByIds(
                        List.of(generation.getManifestOssId()), false))) {
                    throw new ServiceException("OSS 清单删除返回失败");
                }
                markManifestCleaned(generation.getGenerationId());
            } catch (RuntimeException error) {
                artifacts.markCleanupFailed(generation.getGenerationId(), summarize(error));
                log.warn("知识库产物清单补偿删除失败 generationId={}", generation.getGenerationId(), error);
            }
        }
    }

    /**
     * 在确认 generation 未被索引引用后，将其标记为完全清理。
     *
     * @param generationId 产物批次主键
     */
    @Transactional(rollbackFor = Exception.class)
    public void markManifestCleaned(Long generationId) {
        if (artifacts.countIndexReferences(generationId) > 0
            || artifacts.markGenerationCleaned(generationId) != 1) {
            throw new ServiceException("OSS 清单清理时 generation 状态已变化");
        }
    }

    private <T> T decode(String json, Class<T> type) {
        if (json == null) {
            throw new ServiceException("维护任务缺少冻结参数");
        }
        try {
            return codec.read(json.getBytes(StandardCharsets.UTF_8), type);
        } catch (Exception error) {
            throw new ServiceException("维护任务参数损坏");
        }
    }

    private Set<Long> parseIds(List<String> values) {
        Set<Long> ids = new HashSet<>();
        for (String value : values) {
            ids.add(Long.valueOf(value));
        }
        return ids;
    }

    private String summarize(RuntimeException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}