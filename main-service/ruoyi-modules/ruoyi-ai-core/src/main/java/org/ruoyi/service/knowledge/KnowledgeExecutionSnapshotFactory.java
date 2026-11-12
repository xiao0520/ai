package org.ruoyi.service.knowledge;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.domain.dto.knowledge.RagContractJson;
import org.ruoyi.domain.dto.knowledge.rag.DocumentProcessingSnapshot;
import org.ruoyi.domain.dto.knowledge.rag.EmbeddingProfile;
import org.ruoyi.domain.dto.knowledge.rag.IndexSnapshot;
import org.ruoyi.domain.dto.knowledge.rag.ProfileRef;
import org.ruoyi.domain.entity.knowledge.KnowledgeIndexVersion;
import org.ruoyi.domain.entity.knowledge.KnowledgeParseJob;
import org.ruoyi.enums.ModelType;
import org.ruoyi.mapper.knowledge.KnowledgeIndexVersionMapper;
import org.ruoyi.service.model.IModelProfileSnapshotService;
import org.ruoyi.service.model.IModelProfileSnapshotService.ModelProfileSnapshot;
import org.ruoyi.support.KnowledgeIndexSnapshotProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 知识库任务执行快照工厂。
 *
 * <p>从 Java 已发布配置构造任务级不可变索引和文档处理快照，确保 Kafka 重投、Worker 重启与
 * 人工重试始终使用首次派发时的精确模型修订和切片配置。</p>
 */
@Service
@RequiredArgsConstructor
public class KnowledgeExecutionSnapshotFactory {
    private static final String EMPTY_HASH = "0".repeat(64);
    private static final Pattern INDEX_VERSION_NAME_PATTERN =
        Pattern.compile(AiDomainConstants.KNOWLEDGE_INDEX_VERSION_PATTERN);
    private final KnowledgeIndexVersionMapper indexVersions;
    private final IModelProfileSnapshotService modelSnapshots;
    private final KnowledgeIndexSnapshotProperties properties;
    private final ObjectMapper objectMapper;
    private final RagContractJson codec;

    /**
     * 构造首次执行所需的索引与文档处理快照。
     *
     * <p>任务重试应直接复用已持久化 JSON，不得再次调用本方法读取可变配置。</p>
     *
     * @param job 待派发的知识库任务
     * @return 索引快照和可选文档处理快照
     */
    public SnapshotBundle create(KnowledgeParseJob job) {
        KnowledgeIndexVersion index = resolveIndex(job);
        EmbeddingProfile textEmbedding = embedding(modelSnapshots.requireRevision(index.getEmbeddingProfileId(),
            index.getEmbeddingProfileRevision(), ModelType.VECTOR.getKey()),
            EmbeddingProfile.DocumentInputMode.V_TEXT_SINGLE);
        EmbeddingProfile multimodalEmbedding = index.getMultimodalEmbeddingProfileId() == null ? null
            : embedding(modelSnapshots.requireRevision(index.getMultimodalEmbeddingProfileId(),
                index.getMultimodalEmbeddingProfileRevision(), ModelType.VECTOR.getKey()),
                EmbeddingProfile.DocumentInputMode.V_MULTIMODAL_SINGLE);
        RuntimeSplit split = split(index.getSplitterConfig());
        String indexId = index.getId().toString();
        String collection = collectionName(job, index);
        String denseVisionName = multimodalEmbedding == null ? null : properties.getDenseVisionVectorName();
        IndexSnapshot unsigned = new IndexSnapshot(job.getKnowledgeId().toString(), indexId, collection, distance(),
            properties.getDenseVectorName(), denseVisionName, properties.getSparseVectorName(),
            textEmbedding, multimodalEmbedding, properties.getParserRevision(), properties.getSplitterRevision(),
            properties.getTokenizerRevision(), properties.getSparseRevision(),
            properties.getSparseCorpusRevision() + "-" + indexId, split.chunkSizeTokens(),
            split.chunkOverlapTokens(), EMPTY_HASH, properties.getManifestRevision());
        String configHash;
        try {
            configHash = DigestUtil.sha256Hex(codec.write(unsigned));
        } catch (Exception error) {
            throw new ServiceException("索引快照配置不符合机器契约");
        }
        IndexSnapshot snapshot = new IndexSnapshot(unsigned.knowledgeId(), unsigned.indexVersion(),
            unsigned.collectionName(), unsigned.distance(), unsigned.denseVectorName(),
            unsigned.denseVisionVectorName(), unsigned.sparseVectorName(), unsigned.embedding(),
            unsigned.multimodalEmbedding(),
            unsigned.parserRevision(), unsigned.splitterRevision(), unsigned.tokenizerRevision(),
            unsigned.sparseRevision(), unsigned.sparseCorpusRevision(), unsigned.chunkSizeTokens(),
            unsigned.chunkOverlapTokens(), configHash, unsigned.manifestRevision());
        return new SnapshotBundle(snapshot, documentSnapshot(job));
    }

    /**
     * 使用知识库 ID 与业务索引版本名称生成稳定的 Qdrant collection 名称。
     */
    private String collectionName(KnowledgeParseJob job, KnowledgeIndexVersion index) {
        String indexVersion = index.getIndexVersion();
        if (StringUtils.isBlank(indexVersion)
            || !INDEX_VERSION_NAME_PATTERN.matcher(indexVersion).matches()) {
            throw new ServiceException("索引版本名称不符合 Qdrant collection 命名规则");
        }
        String collection = AiDomainConstants.KNOWLEDGE_COLLECTION_PREFIX + "_" + job.getKnowledgeId()
            + "_" + indexVersion;
        if (collection.length() > 128) {
            throw new ServiceException("索引 collection 名称超过契约限制");
        }
        return collection;
    }

    /**
     * 解析任务目标索引并校验模型修订及索引状态。
     */
    private KnowledgeIndexVersion resolveIndex(KnowledgeParseJob job) {
        if (StringUtils.isBlank(job.getTargetIndexVersion())) {
            throw new ServiceException("任务没有绑定目标索引版本");
        }
        KnowledgeIndexVersion index = indexVersions.selectByKnowledgeAndVersion(
            job.getKnowledgeId(), job.getTargetIndexVersion());
        if (index == null || index.getId() == null) {
            throw new ServiceException("任务目标索引版本不存在");
        }
        if (AiDomainConstants.KNOWLEDGE_STATUS_DELETED.equals(index.getStatus())
            && !AiDomainConstants.KNOWLEDGE_PARSE_JOB_TYPE_CLEANUP.equals(job.getJobType())) {
            throw new ServiceException("已删除索引不能创建新的执行任务");
        }
        if (AiDomainConstants.KNOWLEDGE_PARSE_JOB_TYPE_REBUILD.equals(job.getJobType())
            && AiDomainConstants.STATUS_ARCHIVED.equals(index.getStatus())) {
            throw new ServiceException("已归档索引不能重新构建");
        }
        if (index.getEmbeddingProfileId() == null || index.getEmbeddingProfileRevision() == null) {
            throw new ServiceException("目标索引未绑定文本 Embedding 发布修订");
        }
        if ((index.getMultimodalEmbeddingProfileId() == null)
            != (index.getMultimodalEmbeddingProfileRevision() == null)) {
            throw new ServiceException("目标索引多模态 Embedding 发布修订不完整");
        }
        return index;
    }

    /**
     * 从精确模型发布修订构造不可变 Embedding 执行参数。
     */
    private EmbeddingProfile embedding(ModelProfileSnapshot model,
                                       EmbeddingProfile.DocumentInputMode documentInputMode) {
        requireModelFields(model, "Embedding");
        if (StringUtils.isBlank(model.apiMode()) || model.dimensions() == null
            || model.dimensions() < 1 || model.normalized() == null
            || model.maxInputTokens() == null || model.maxInputTokens() < 1) {
            throw new ServiceException("Embedding 调用模式、维度、归一化或输入上限未配置");
        }
        return new EmbeddingProfile(new ProfileRef(model.id().toString(), model.revision()),
            model.providerCode(), model.modelName(), model.apiHost(), model.apiMode(), model.credentialKey(),
            model.dimensions(), documentInputMode, EmbeddingProfile.QueryInputMode.V_TEXT_SINGLE,
            model.normalized(), model.maxInputTokens(), properties.getEmbeddingTimeoutSeconds());
    }

    /**
     * 读取入库任务冻结的文档处理快照；维护任务不需要该快照。
     */
    private DocumentProcessingSnapshot documentSnapshot(KnowledgeParseJob job) {
        if (!AiDomainConstants.KNOWLEDGE_PARSE_JOB_TYPE_INGESTION.equals(job.getJobType())) {
            return null;
        }
        if (StringUtils.isBlank(job.getDocumentProcessingSnapshot())) {
            throw new ServiceException("任务没有绑定文档处理快照");
        }
        try {
            return codec.read(job.getDocumentProcessingSnapshot().getBytes(StandardCharsets.UTF_8),
                DocumentProcessingSnapshot.class);
        } catch (Exception error) {
            throw new ServiceException("任务绑定的文档处理快照损坏");
        }
    }

    /**
     * 校验执行端必需的模型连接信息。
     */
    private void requireModelFields(ModelProfileSnapshot model, String label) {
        if (model.id() == null || StringUtils.isAnyBlank(model.providerCode(), model.modelName(),
            model.apiHost(), model.credentialKey())) {
            throw new ServiceException(label + " 模型发布配置不完整");
        }
    }

    /**
     * 解析切片 JSON，并以系统默认值补齐缺失字段。
     */
    private RuntimeSplit split(String value) {
        int size = properties.getDefaultChunkSizeTokens();
        int overlap = properties.getDefaultChunkOverlapTokens();
        if (StringUtils.isNotBlank(value)) {
            try {
                JsonNode node = objectMapper.readTree(value);
                size = integer(node, "chunkSizeTokens", integer(node, "chunkSize", size));
                overlap = integer(node, "chunkOverlapTokens", integer(node, "overlap", overlap));
            } catch (ServiceException error) {
                throw error;
            } catch (Exception error) {
                throw new ServiceException("索引切分配置不是有效 JSON");
            }
        }
        if (size < 1 || overlap < 0 || overlap >= size) {
            throw new ServiceException("索引切分配置要求 0 <= overlap < chunkSize");
        }
        return new RuntimeSplit(size, overlap);
    }

    /**
     * 读取切片整数字段，缺失时使用回退值。
     */
    private int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        if (value == null) {
            return fallback;
        }
        if (!value.canConvertToInt()) {
            throw new ServiceException("索引切分配置字段必须是整数：" + field);
        }
        return value.intValue();
    }

    /**
     * 把运行配置中的距离名称转换为机器契约枚举。
     */
    private IndexSnapshot.Distance distance() {
        return switch (properties.getDistance()) {
            case "COSINE" -> IndexSnapshot.Distance.V_COSINE;
            case "DOT" -> IndexSnapshot.Distance.V_DOT;
            case "EUCLID" -> IndexSnapshot.Distance.V_EUCLID;
            default -> throw new ServiceException("索引距离配置无效");
        };
    }

    private record RuntimeSplit(int chunkSizeTokens, int chunkOverlapTokens) { }
    public record SnapshotBundle(IndexSnapshot indexSnapshot,
                                 DocumentProcessingSnapshot documentProcessingSnapshot) { }
}
