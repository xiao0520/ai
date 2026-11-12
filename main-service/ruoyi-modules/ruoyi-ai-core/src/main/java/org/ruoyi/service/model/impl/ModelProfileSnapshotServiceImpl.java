package org.ruoyi.service.model.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.domain.entity.model.Model;
import org.ruoyi.domain.entity.model.ModelProfileRevision;
import org.ruoyi.enums.ModelType;
import org.ruoyi.mapper.model.ModelMapper;
import org.ruoyi.mapper.model.ModelProfileRevisionMapper;
import org.ruoyi.service.model.IModelProfileSnapshotService;
import org.springframework.stereotype.Service;

/**
 * 模型发布快照读取服务。
 *
 * <p>模型修订一经发布，只允许按模型主键和修订号精确读取。缺失修订时拒绝回退到可变模型主表，
 * 以保证知识库任务重试和聊天执行可复现。</p>
 */
@Service
@RequiredArgsConstructor
public class ModelProfileSnapshotServiceImpl implements IModelProfileSnapshotService {
    private final ModelMapper models;
    private final ModelProfileRevisionMapper revisions;
    private final ObjectMapper objectMapper;

    @Override
    public ModelProfileSnapshot requireCurrent(Long modelId, String expectedCategory) {
        Model current = models.selectById(modelId);
        if (current == null || current.getPublishedRevision() == null || current.getPublishedRevision() < 1) {
            throw new ServiceException("模型尚未发布有效修订");
        }
        return requireRevision(modelId, current.getPublishedRevision(), expectedCategory);
    }

    @Override
    public ModelProfileSnapshot requireRevision(Long modelId, Integer revision, String expectedCategory) {
        if (modelId == null || revision == null || revision < 1) {
            throw new ServiceException("模型发布修订不能为空");
        }
        ModelProfileRevision saved = revisions.selectPublished(modelId, revision);
        if (saved == null) {
            throw new ServiceException("模型发布修订不存在");
        }
        // 快照反序列化后再次校验主键、修订、分类和状态，防止损坏数据越过领域边界。
        Model model;
        try {
            model = objectMapper.readValue(saved.getProfileSnapshot(), Model.class);
        } catch (Exception error) {
            throw new ServiceException("模型发布快照损坏");
        }
        if (!modelId.equals(model.getId()) || !revision.equals(model.getPublishedRevision())
            || !expectedCategory.equals(model.getCategory())
            || !SystemConstants.NORMAL.equals(model.getStatus())) {
            throw new ServiceException("模型发布快照类型或状态无效");
        }
        validateApiMode(model, expectedCategory);
        return new ModelProfileSnapshot(model.getId(), revision, model.getCategory(), model.getProviderCode(),
            model.getModelName(), model.getApiHost(), model.getApiMode(), model.getCredentialKey(),
            model.getInputModalities(), model.getAdapterCode(), model.getModelDimension(), model.getNormalized(),
            model.getMaxInputTokens(), model.getMaxDocumentTokens());
    }

    /** 历史修订也必须使用与模型分类匹配的执行协议。 */
    private void validateApiMode(Model model, String expectedCategory) {
        String apiMode = model.getApiMode();
        if (ModelType.VECTOR.getKey().equals(expectedCategory)
            && !AiDomainConstants.MODEL_EMBEDDING_API_MODES.contains(apiMode)) {
            throw new ServiceException("索引绑定的向量模型修订协议无效，请新建索引版本选择最新模型修订");
        }
        if ((ModelType.CHAT.getKey().equals(expectedCategory)
            || ModelType.IMAGE.getKey().equals(expectedCategory))
            && !AiDomainConstants.MODEL_CHAT_API_MODES.contains(apiMode)) {
            throw new ServiceException("模型发布修订的API调用模式与模型分类不匹配");
        }
    }
}
