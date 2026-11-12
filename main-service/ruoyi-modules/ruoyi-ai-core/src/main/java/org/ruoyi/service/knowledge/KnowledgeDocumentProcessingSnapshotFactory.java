package org.ruoyi.service.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.domain.bo.knowledge.KnowledgeDocumentProcessingBo;
import org.ruoyi.domain.dto.knowledge.RagContractJson;
import org.ruoyi.domain.dto.knowledge.rag.DocumentProcessingSnapshot;
import org.ruoyi.domain.dto.knowledge.rag.DocumentRerankPolicy;
import org.ruoyi.domain.dto.knowledge.rag.EmbeddingProfile;
import org.ruoyi.domain.dto.knowledge.rag.IndexSnapshot;
import org.ruoyi.domain.dto.knowledge.rag.ProfileRef;
import org.ruoyi.domain.dto.knowledge.rag.RerankProfile;
import org.ruoyi.domain.dto.knowledge.rag.VisionProfile;
import org.ruoyi.domain.entity.knowledge.KnowledgeDocumentVersion;
import org.ruoyi.domain.entity.knowledge.KnowledgeIndexVersion;
import org.ruoyi.enums.ModelType;
import org.ruoyi.mapper.knowledge.KnowledgeIndexVersionMapper;
import org.ruoyi.service.model.IModelProfileSnapshotService;
import org.ruoyi.service.model.IModelProfileSnapshotService.ModelProfileSnapshot;
import org.ruoyi.support.KnowledgeIndexSnapshotProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;

/**
 * 文档处理快照工厂。
 *
 * <p>校验管理端选择的处理模式和模型，并为物理文档版本冻结精确发布修订。快照一经提交，
 * 后续模型配置变更不得影响正在执行或重试的任务。</p>
 */
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentProcessingSnapshotFactory {
    private final KnowledgeIndexVersionMapper indexVersions;
    private final IModelProfileSnapshotService modelSnapshots;
    private final KnowledgeIndexSnapshotProperties properties;
    private final ObjectMapper objectMapper;
    private final RagContractJson codec;

    /**
     * 索引决定向量空间，文档版本只能选择与目标索引完全一致的 Embedding。
     * Vision 与 Rerank 按当前发布修订冻结，后续模型编辑不会改变已提交任务。
     */
    public DocumentProcessingSnapshot apply(KnowledgeDocumentVersion version,
                                             KnowledgeDocumentProcessingBo request,
                                             int configurationRevision) {
        if (version == null || request == null) {
            throw new ServiceException("文档处理配置不能为空");
        }
        if (!AiDomainConstants.KNOWLEDGE_PROCESSING_MODES.contains(request.getProcessingMode())) {
            throw new ServiceException("文档处理模式无效");
        }
        if (!AiDomainConstants.DOCUMENT_RERANK_MODES.contains(request.getRerankMode())) {
            throw new ServiceException("Rerank 模式无效");
        }
        KnowledgeIndexVersion index = indexVersions.selectByKnowledgeAndVersion(
            version.getKnowledgeId(), request.getTargetIndexVersion());
        if (index == null) {
            throw new ServiceException("目标索引版本不存在");
        }
        if (request.getTextEmbeddingProfileId() == null
            || !request.getTextEmbeddingProfileId().equals(index.getEmbeddingProfileId())
            || index.getEmbeddingProfileRevision() == null) {
            throw new ServiceException("文本 Embedding 必须与目标索引绑定模型一致");
        }

        ModelProfileSnapshot textModel = modelSnapshots.requireRevision(index.getEmbeddingProfileId(),
            index.getEmbeddingProfileRevision(), ModelType.VECTOR.getKey());
        EmbeddingProfile textEmbedding = embedding(textModel,
            EmbeddingProfile.DocumentInputMode.V_TEXT_SINGLE, AiDomainConstants.MODEL_INPUT_MODALITY_TEXT);

        boolean multimodal = AiDomainConstants.KNOWLEDGE_PROCESSING_MODE_MULTIMODAL
            .equals(request.getProcessingMode());
        EmbeddingProfile multimodalEmbedding = null;
        ModelProfileSnapshot multimodalModel = null;
        if (multimodal) {
            if (request.getMultimodalEmbeddingProfileId() == null
                || !request.getMultimodalEmbeddingProfileId().equals(index.getMultimodalEmbeddingProfileId())
                || index.getMultimodalEmbeddingProfileRevision() == null) {
                throw new ServiceException("多模态 Embedding 必须与目标索引绑定模型一致");
            }
            multimodalModel = modelSnapshots.requireRevision(index.getMultimodalEmbeddingProfileId(),
                index.getMultimodalEmbeddingProfileRevision(), ModelType.VECTOR.getKey());
            multimodalEmbedding = embedding(multimodalModel,
                EmbeddingProfile.DocumentInputMode.V_MULTIMODAL_SINGLE,
                AiDomainConstants.MODEL_INPUT_MODALITY_IMAGE);
        } else if (request.getMultimodalEmbeddingProfileId() != null || request.getVisionProfileId() != null
            || request.getMultimodalRerankProfileId() != null) {
            throw new ServiceException("纯文本模式不能配置多模态模型");
        }

        if (multimodal && request.getVisionProfileId() == null) {
            throw new ServiceException("多模态模式必须配置视觉/OCR 模型");
        }
        ModelProfileSnapshot visionModel = request.getVisionProfileId() == null ? null
            : modelSnapshots.requireCurrent(request.getVisionProfileId(), ModelType.IMAGE.getKey());
        VisionProfile vision = visionModel == null ? null : vision(visionModel);
        if (!multimodal && vision != null) {
            throw new ServiceException("纯文本模式不能配置视觉模型");
        }

        RerankProfile textRerank = null;
        RerankProfile multimodalRerank = null;
        if (AiDomainConstants.DOCUMENT_RERANK_MODE_PROFILE.equals(request.getRerankMode())) {
            if (request.getTextRerankProfileId() == null) {
                throw new ServiceException("文本 Rerank 模型不能为空");
            }
            ModelProfileSnapshot textRerankModel = modelSnapshots.requireCurrent(
                request.getTextRerankProfileId(), ModelType.RERANK.getKey());
            textRerank = rerank(textRerankModel, RerankProfile.InputMode.V_TEXT,
                AiDomainConstants.MODEL_INPUT_MODALITY_TEXT);
            if (multimodal) {
                if (request.getMultimodalRerankProfileId() == null) {
                    throw new ServiceException("多模态模式必须配置多模态 Rerank 模型");
                }
                ModelProfileSnapshot multimodalRerankModel = modelSnapshots.requireCurrent(
                    request.getMultimodalRerankProfileId(), ModelType.RERANK.getKey());
                multimodalRerank = rerank(multimodalRerankModel, RerankProfile.InputMode.V_MULTIMODAL,
                    AiDomainConstants.MODEL_INPUT_MODALITY_IMAGE);
            }
        } else if (request.getTextRerankProfileId() != null || request.getMultimodalRerankProfileId() != null) {
            throw new ServiceException("关闭 Rerank 时不能绑定 Rerank 模型");
        }

        DocumentRerankPolicy policy = new DocumentRerankPolicy(
            AiDomainConstants.DOCUMENT_RERANK_MODE_PROFILE.equals(request.getRerankMode())
                ? DocumentRerankPolicy.Mode.V_PROFILE : DocumentRerankPolicy.Mode.V_NONE,
            textRerank, multimodalRerank);
        DocumentProcessingSnapshot snapshot = new DocumentProcessingSnapshot(configurationRevision,
            multimodal ? DocumentProcessingSnapshot.ProcessingMode.V_MULTIMODAL
                : DocumentProcessingSnapshot.ProcessingMode.V_TEXT,
            textEmbedding, multimodalEmbedding, vision, policy);

        version.setTargetIndexVersion(request.getTargetIndexVersion());
        version.setConfigurationRevision(configurationRevision);
        version.setProcessingMode(request.getProcessingMode());
        version.setTextEmbeddingProfileId(textModel.id());
        version.setTextEmbeddingProfileRevision(textModel.revision());
        version.setMultimodalEmbeddingProfileId(multimodalModel == null ? null : multimodalModel.id());
        version.setMultimodalEmbeddingProfileRevision(multimodalModel == null ? null : multimodalModel.revision());
        version.setVisionProfileId(visionModel == null ? null : visionModel.id());
        version.setVisionProfileRevision(visionModel == null ? null : visionModel.revision());
        version.setRerankMode(request.getRerankMode());
        version.setTextRerankProfileId(textRerank == null ? null : textRerankModelId(textRerank));
        version.setTextRerankProfileRevision(textRerank == null ? null : textRerank.profile().revision());
        version.setMultimodalRerankProfileId(multimodalRerank == null ? null : textRerankModelId(multimodalRerank));
        version.setMultimodalRerankProfileRevision(
            multimodalRerank == null ? null : multimodalRerank.profile().revision());
        try {
            version.setDocumentProcessingSnapshot(new String(codec.write(snapshot), StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new ServiceException("文档处理快照不符合机器契约");
        }
        return snapshot;
    }

    /**
     * 为 REBUILD 任务重新定向文档处理快照。
     *
     * <p>保留文档的视觉与 Rerank 选择，只把 Embedding 向量空间切换到目标索引快照。</p>
     *
     * @param sourceJson 原文档处理快照 JSON
     * @param target 目标索引快照
     * @return 适用于目标索引的文档处理快照
     */
    public DocumentProcessingSnapshot retarget(String sourceJson, IndexSnapshot target) {
        if (StringUtils.isBlank(sourceJson) || target == null) {
            throw new ServiceException("重建任务处理快照不能为空");
        }
        try {
            DocumentProcessingSnapshot source = codec.read(sourceJson.getBytes(StandardCharsets.UTF_8),
                DocumentProcessingSnapshot.class);
            EmbeddingProfile multimodal = null;
            if (source.processingMode() == DocumentProcessingSnapshot.ProcessingMode.V_MULTIMODAL) {
                multimodal = target.multimodalEmbedding();
                if (multimodal == null) {
                    throw new ServiceException("多模态文档不能重建到纯文本索引");
                }
            }
            return new DocumentProcessingSnapshot(source.configurationRevision(), source.processingMode(),
                target.embedding(), multimodal, source.visionProfile(), source.rerankPolicy());
        } catch (ServiceException error) {
            throw error;
        } catch (Exception error) {
            throw new ServiceException("文档处理快照损坏，无法执行重建");
        }
    }
    /**
     * 解析文本 Rerank 模型标识，关闭 Rerank 时返回空。
     */
    private Long textRerankModelId(RerankProfile profile) {
        try {
            return Long.valueOf(profile.profile().id());
        } catch (NumberFormatException error) {
            throw new ServiceException("Rerank 模型 ID 无效");
        }
    }

    /**
     * 把模型发布快照转换为执行端 Embedding 契约并校验输入模态。
     */
    private EmbeddingProfile embedding(ModelProfileSnapshot model,
                                       EmbeddingProfile.DocumentInputMode inputMode,
                                       String requiredModality) {
        requireModelFields(model, "Embedding");
        requireModality(model, requiredModality, "Embedding");
        if (StringUtils.isBlank(model.apiMode()) || model.dimensions() == null
            || model.dimensions() < 1 || model.normalized() == null
            || model.maxInputTokens() == null || model.maxInputTokens() < 1) {
            throw new ServiceException("Embedding 调用模式、维度、归一化或输入上限未配置");
        }
        return new EmbeddingProfile(new ProfileRef(model.id().toString(), model.revision()),
            model.providerCode(), model.modelName(), model.apiHost(), model.apiMode(),
            model.credentialKey(), model.dimensions(),
            inputMode, EmbeddingProfile.QueryInputMode.V_TEXT_SINGLE, model.normalized(),
            model.maxInputTokens(), properties.getEmbeddingTimeoutSeconds());
    }

    /**
     * 把视觉模型发布快照转换为 OCR/视觉理解执行契约。
     */
    private VisionProfile vision(ModelProfileSnapshot model) {
        requireModelFields(model, "视觉");
        requireModality(model, AiDomainConstants.MODEL_INPUT_MODALITY_IMAGE, "视觉");
        if (StringUtils.isBlank(model.apiMode()) || model.maxInputTokens() == null
            || model.maxInputTokens() < 1) throw new ServiceException("视觉模型调用模式或输入上限未配置");
        return new VisionProfile(new ProfileRef(model.id().toString(), model.revision()), model.providerCode(),
            model.modelName(), model.apiHost(), model.apiMode(), model.credentialKey(),
            model.maxInputTokens(), properties.getEmbeddingTimeoutSeconds());
    }

    /**
     * 把 Rerank 发布快照转换为指定输入模态的执行契约。
     */
    private RerankProfile rerank(ModelProfileSnapshot model, RerankProfile.InputMode inputMode,
                                  String requiredModality) {
        requireModelFields(model, "Rerank");
        requireModality(model, requiredModality, "Rerank");
        if (StringUtils.isBlank(model.adapterCode()) || model.maxDocumentTokens() == null
            || model.maxDocumentTokens() < 1) throw new ServiceException("Rerank 适配器或文档输入上限未配置");
        return new RerankProfile(new ProfileRef(model.id().toString(), model.revision()), model.providerCode(),
            model.modelName(), model.apiHost(), model.adapterCode(), model.credentialKey(), inputMode,
            model.maxDocumentTokens(), properties.getRerankTimeoutSeconds());
    }

    /**
     * 校验所有执行适配器共同依赖的模型字段。
     */
    private void requireModelFields(ModelProfileSnapshot model, String label) {
        if (model.id() == null || StringUtils.isAnyBlank(model.providerCode(), model.modelName(),
            model.apiHost(), model.credentialKey())) throw new ServiceException(label + " 模型发布配置不完整");
    }

    /**
     * 校验模型声明支持任务要求的输入模态。
     */
    private void requireModality(ModelProfileSnapshot model, String modality, String label) {
        if (!modalities(model.inputModalities()).contains(modality)) {
            throw new ServiceException(label + " 模型不支持所选输入模态");
        }
    }

    /**
     * 解析并标准化模型输入模态集合。
     */
    private Set<String> modalities(String value) {
        if (StringUtils.isBlank(value)) {
            return Collections.emptySet();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<Set<String>>() { });
        } catch (Exception error) {
            throw new ServiceException("模型输入模态配置损坏");
        }
    }
}
