package org.ruoyi.service.knowledge.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.common.core.utils.MapstructUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.json.utils.JsonUtils;
import org.ruoyi.domain.bo.knowledge.KnowledgeIndexVersionBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeArtifactGeneration;
import org.ruoyi.domain.entity.knowledge.KnowledgeIndexVersion;
import org.ruoyi.domain.vo.knowledge.KnowledgeIndexVersionVo;
import org.ruoyi.mapper.knowledge.KnowledgeArtifactMapper;
import org.ruoyi.integration.kafka.KnowledgeKafkaProperties;
import org.ruoyi.mapper.knowledge.KnowledgeIndexVersionMapper;
import org.ruoyi.service.knowledge.IKnowledgeIndexVersionService;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.model.IModelProfileSnapshotService;
import org.ruoyi.enums.ModelType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 知识库索引版本管理Service实现类
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Service
@RequiredArgsConstructor
public class KnowledgeIndexVersionServiceImpl implements IKnowledgeIndexVersionService {
    private final KnowledgeIndexVersionMapper baseMapper;
    private final KnowledgeArtifactMapper artifactMapper;
    private final IKnowledgeInfoService knowledgeInfoService;
    private final IModelProfileSnapshotService modelSnapshots;
    private final KnowledgeKafkaProperties kafkaProperties;
    private final ObjectMapper objectMapper;

    @Override
    public List<KnowledgeIndexVersionVo> list(Long knowledgeId) {
        return baseMapper.selectVoListByKnowledgeId(knowledgeId, AiDomainConstants.KNOWLEDGE_STATUS_DELETED);
    }

    @Override
    public KnowledgeIndexVersionVo query(Long id) {
        return baseMapper.selectVoById(id);
    }

    @Override
    public Boolean save(KnowledgeIndexVersionBo bo) {
        // 切分配置必须是 JSON 对象，且同一知识库内索引版本号不允许重复。
        if (bo.getSplitterConfig() != null && !JsonUtils.isJsonObject(bo.getSplitterConfig())) {
            throw new ServiceException("splitterConfig 必须是 JSON 对象");
        }
        freezeModelRevisions(bo);
        boolean duplicate = baseMapper.existsByKnowledgeAndVersion(
            bo.getKnowledgeId(), bo.getIndexVersion(), bo.getId());
        if (duplicate) {
            throw new ServiceException("同一知识库的索引版本不能重复");
        }

        // 新建版本默认置为构建中，等待解析任务推进为可用状态。
        KnowledgeIndexVersion entity = MapstructUtils.convert(bo, KnowledgeIndexVersion.class);
        if (bo.getId() == null) {
            if (entity.getStatus() == null) entity.setStatus(AiDomainConstants.STATUS_BUILDING);
            return baseMapper.insert(entity) > 0;
        }

        // 生效中的版本直接参与检索，不允许再被编辑。
        KnowledgeIndexVersion current = baseMapper.selectById(bo.getId());
        if (current == null) {
            throw new ServiceException("索引版本不存在");
        }
        if (AiDomainConstants.STATUS_ACTIVE.equals(current.getStatus())) {
            throw new ServiceException("生效中的索引版本不可编辑");
        }
        return baseMapper.updateById(entity) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean remove(Long id) {
        if (!kafkaProperties.isEnabled()) {
            throw new ServiceException("彻底删除索引依赖 Kafka Worker 和周期清理任务，请先启用执行服务");
        }
        KnowledgeIndexVersion current = baseMapper.selectById(id);
        if (current == null || AiDomainConstants.KNOWLEDGE_STATUS_DELETED.equals(current.getStatus())) {
            throw new ServiceException("索引版本不存在");
        }

        // 与激活操作使用同一把知识库行锁，避免删除和版本切换互相覆盖。
        knowledgeInfoService.lockForIndexActivation(current.getKnowledgeId());
        current = baseMapper.selectByKnowledgeAndVersionForUpdate(
            current.getKnowledgeId(), current.getIndexVersion());
        if (current == null || AiDomainConstants.KNOWLEDGE_STATUS_DELETED.equals(current.getStatus())) {
            throw new ServiceException("索引版本不存在");
        }
        // 当前版本只有在真实成员已经清空时才能删除，同时解除知识库当前索引指针。
        if (AiDomainConstants.STATUS_ACTIVE.equals(current.getStatus())) {
            if (artifactMapper.countIndexDocuments(id) > 0) {
                throw new ServiceException("当前生效索引仍包含文档，请先删除文档或激活其他版本");
            }
            knowledgeInfoService.clearCurrentIndexVersion(current.getKnowledgeId(), current.getIndexVersion());
        }

        int removed = baseMapper.softDeleteRemovableById(id, AiDomainConstants.KNOWLEDGE_STATUS_DELETED,
            AiDomainConstants.STATUS_ACTIVE, AiDomainConstants.KNOWLEDGE_PARSE_JOB_ACTIVE_STATUSES);
        if (removed == 0) {
            throw new ServiceException("索引版本仍包含文档、正在被知识库使用或存在运行中的任务，不能删除");
        }

        // 先解除发布成员关系，再退役产物；维护调度会异步删除 Qdrant 向量和 OSS 清单。
        List<KnowledgeArtifactGeneration> generations = artifactMapper.selectGenerationsByIndex(id);
        artifactMapper.deleteIndexDocuments(id);
        Date cleanupAfter = new Date();
        for (KnowledgeArtifactGeneration generation : generations) {
            if (isCleaned(generation.getStatus())) {
                continue;
            }
            if (artifactMapper.markGenerationRetired(generation.getGenerationId(), cleanupAfter) == 0) {
                throw new ServiceException("索引产物仍被引用，删除失败");
            }
        }
        return true;
    }

    private boolean isCleaned(String status) {
        return "VECTOR_CLEANED".equals(status) || "CLEANED".equals(status);
    }

    /** 保存索引时固定当前模型发布修订，后续模型更新不改变已建任务。 */
    private void freezeModelRevisions(KnowledgeIndexVersionBo bo) {
        if (bo.getEmbeddingProfileId() == null) {
            throw new ServiceException("Embedding 配置不能为空");
        }
        if (bo.getMultimodalEmbeddingProfileId() != null
            && !bo.getEmbeddingProfileId().equals(bo.getMultimodalEmbeddingProfileId())) {
            throw new ServiceException("多模态索引只能选择一个同时支持文本和图片的 Embedding 模型");
        }
        var embedding = modelSnapshots.requireCurrent(bo.getEmbeddingProfileId(), ModelType.VECTOR.getKey());
        requireEmbeddingModalities(embedding, bo.getMultimodalEmbeddingProfileId() != null);
        bo.setEmbeddingProfileRevision(embedding.revision());
        if (bo.getMultimodalEmbeddingProfileId() == null) {
            bo.setMultimodalEmbeddingProfileRevision(null);
        } else {
            bo.setMultimodalEmbeddingProfileRevision(embedding.revision());
        }
        // Rerank 属于文档版本处理策略，索引版本不再保存或覆盖该选择。
        bo.setRerankProfileId(null);
        bo.setRerankProfileRevision(null);
    }

    /** 新建索引时立即校验模型模态，避免任务执行后才发现向量模型不兼容。 */
    private void requireEmbeddingModalities(IModelProfileSnapshotService.ModelProfileSnapshot model,
                                            boolean multimodal) {
        if (StringUtils.isBlank(model.inputModalities())) {
            throw new ServiceException("Embedding 模型未配置有效的输入模态");
        }
        JsonNode modalities;
        try {
            modalities = objectMapper.readTree(model.inputModalities());
        } catch (Exception error) {
            throw new ServiceException("Embedding 模型输入模态配置无效");
        }
        if (modalities == null || !modalities.isArray()) {
            throw new ServiceException("Embedding 模型输入模态配置无效");
        }
        boolean supportsText = false;
        boolean supportsImage = false;
        for (JsonNode modality : modalities) {
            if (!modality.isTextual()) {
                throw new ServiceException("Embedding 模型输入模态配置无效");
            }
            supportsText |= AiDomainConstants.MODEL_INPUT_MODALITY_TEXT.equalsIgnoreCase(modality.asText());
            supportsImage |= AiDomainConstants.MODEL_INPUT_MODALITY_IMAGE.equalsIgnoreCase(modality.asText());
        }
        if (!supportsText) {
            throw new ServiceException("索引 Embedding 模型必须支持文本输入");
        }
        if (multimodal && !supportsImage) {
            throw new ServiceException("图文混合索引的 Embedding 模型必须同时支持文本和图片输入");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean activate(Long id) {
        // 先定位目标知识库，再锁定其主记录，避免并发切换互相覆盖当前索引版本。
        KnowledgeIndexVersion version = baseMapper.selectById(id);
        if (version == null) {
            throw new ServiceException("索引版本不存在");
        }
        knowledgeInfoService.lockForIndexActivation(version.getKnowledgeId());

        // 锁获取期间目标版本可能已被其他请求改变，必须重新读取并校验最新状态。
        version = baseMapper.selectByKnowledgeAndVersionForUpdate(
            version.getKnowledgeId(), version.getIndexVersion());
        if (version == null) {
            throw new ServiceException("索引版本不存在");
        }
        if (!AiDomainConstants.STATUS_READY.equals(version.getStatus())
            && !AiDomainConstants.STATUS_ARCHIVED.equals(version.getStatus())
            && !AiDomainConstants.STATUS_ACTIVE.equals(version.getStatus())) {
            throw new ServiceException("只有 READY 或 ARCHIVED 状态的完整索引可以切换为生效版本");
        }

        // 先归档旧生效版本，再激活目标版本，确保每个知识库仅保留一个当前索引。
        baseMapper.archiveActiveByKnowledgeId(version.getKnowledgeId(), AiDomainConstants.STATUS_ACTIVE,
            AiDomainConstants.STATUS_ARCHIVED, id);
        boolean activated = baseMapper.activateAvailableById(id, AiDomainConstants.STATUS_READY,
            AiDomainConstants.STATUS_ARCHIVED, AiDomainConstants.STATUS_ACTIVE, new Date()) > 0;
        if (activated) {
            knowledgeInfoService.activateIndexVersion(version.getKnowledgeId(), version.getIndexVersion());
            baseMapper.publishMemberDocuments(id);
        }
        return activated;
    }
}
