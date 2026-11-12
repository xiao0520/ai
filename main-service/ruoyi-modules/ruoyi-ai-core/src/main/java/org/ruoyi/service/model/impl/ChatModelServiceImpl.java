package org.ruoyi.service.model.impl;

import org.ruoyi.domain.bo.model.ModelBo;
import org.ruoyi.domain.entity.model.Model;
import org.ruoyi.domain.entity.model.ModelProfileRevision;
import org.ruoyi.domain.entity.model.ModelProvider;
import org.ruoyi.domain.vo.model.MyModelOptionVo;
import org.ruoyi.domain.vo.model.ModelVo;
import org.ruoyi.domain.vo.model.KnowledgeModelOptionVo;
import org.ruoyi.domain.vo.token.AiModelOptionVo;
import org.ruoyi.service.model.IChatModelService;
import org.ruoyi.service.agent.IAgentModelReferenceService;
import org.ruoyi.common.core.utils.MapstructUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.constant.AiSystemConfigKeys;
import org.ruoyi.constant.AiDictTypes;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.enums.ModelType;
import org.ruoyi.common.json.utils.JsonUtils;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ruoyi.mapper.model.ModelMapper;
import org.ruoyi.mapper.model.ModelProfileRevisionMapper;
import org.ruoyi.mapper.model.ModelProviderMapper;
import org.ruoyi.support.AiRuntimeConfig;
import org.ruoyi.support.AiBusinessDict;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Collection;
import java.util.Locale;

/**
 * 模型管理Service业务层处理
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ChatModelServiceImpl implements IChatModelService {

    private final ModelMapper baseMapper;

    private final ModelProfileRevisionMapper modelProfileRevisionMapper;

    private final ModelProviderMapper modelProviderMapper;

    private final IAgentModelReferenceService agentModelReferenceService;

    private final AiRuntimeConfig runtimeConfig;

    private final AiBusinessDict businessDict;

    @Override
    public ModelVo queryById(Long id) {
        return baseMapper.selectVoById(id);
    }

    @Override
    public List<ModelVo> queryByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return baseMapper.selectModelVoByIds(ids);
    }

    @Override
    public List<MyModelOptionVo> queryVisibleChatOptions(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return baseMapper.selectVisibleChatOptions(ids, ModelType.CHAT.getKey(),
            SystemConstants.NORMAL, SystemConstants.YES);
    }

    @Override
    public List<AiModelOptionVo> queryEnabledOptions() {
        return baseMapper.selectEnabledOptions(SystemConstants.NORMAL);
    }

    @Override
    public List<AiModelOptionVo> queryEnabledOptionsByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return baseMapper.selectEnabledOptionsByIds(ids, SystemConstants.NORMAL);
    }

    @Override
    public List<KnowledgeModelOptionVo> queryKnowledgeOptions(String category, String inputModality) {
        if (!ModelType.VECTOR.getKey().equals(category) && !ModelType.RERANK.getKey().equals(category)
            && !ModelType.IMAGE.getKey().equals(category)) {
            throw new ServiceException("知识库模型分类只支持 vector、rerank 或 image");
        }
        String requiredModality = StringUtils.isBlank(inputModality) ? null
            : inputModality.trim().toUpperCase(Locale.ROOT);
        if (requiredModality != null && !AiDomainConstants.MODEL_INPUT_MODALITIES.contains(requiredModality)) {
            throw new ServiceException("模型输入模态无效");
        }
        return baseMapper.selectKnowledgeOptions(category, SystemConstants.NORMAL).stream()
            .filter(this::isKnowledgeModelComplete)
            .filter(option -> requiredModality == null
                || parseInputModalities(option.getInputModalities()).contains(requiredModality))
            .toList();
    }

    @Override
    public void requireEnabledModels(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // 入参去重后与启用结果比对数量，不一致即说明存在失效模型。
        List<Long> modelIds = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (modelIds.size() != ids.size() || queryEnabledOptionsByIds(modelIds).size() != modelIds.size()) {
            throw new ServiceException("所选模型包含不存在、已删除或已停用的模型");
        }
    }

    @Override
    public boolean hasModelsByProviderCodes(Collection<String> providerCodes) {
        if (providerCodes == null || providerCodes.isEmpty()) {
            return false;
        }
        return baseMapper.existsByProviderCodes(providerCodes);
    }

    @Override
    public ModelVo selectModelByName(String modelName) {
        // 名称可能对应多条记录时只取首条，调用方需自行保证使用场景的唯一性。
        Long id = baseMapper.selectFirstIdByModelName(modelName);
        return id == null ? null : queryById(id);
    }

    @Override
    public TableDataInfo<ModelVo> queryPageList(ModelBo bo, PageQuery pageQuery) {
        // 列表筛选条件由 Mapper XML 统一维护，避免业务层直接拼装持久化查询。
        Page<ModelVo> page = pageQuery.build();
        page.setRecords(baseMapper.selectModelList(page, bo));
        return TableDataInfo.build(page);
    }

    @Override
    public List<ModelVo> queryList(ModelBo bo) {
        return baseMapper.selectModelList(null, bo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean insertByBo(ModelBo bo) {
        // 转换请求并补齐受系统参数控制的默认上下文配置。
        Model add = MapstructUtils.convert(bo, Model.class);
        add.setPublishedRevision(1);
        if (StringUtils.isBlank(add.getStatus())) {
            add.setStatus(SystemConstants.NORMAL);
        }
        if (StringUtils.isBlank(add.getModelShow())) {
            add.setModelShow(SystemConstants.YES);
        }
        if (add.getGrayWeight() == null) {
            add.setGrayWeight(100);
        }
        applyProviderEndpoint(add);
        applyContextDefaults(add);
        validEntityBeforeSave(add);
        // 保存成功后回填主键，供 Controller 和后续业务继续使用。
        boolean inserted = baseMapper.insert(add) > 0;
        if (inserted) {
            bo.setId(add.getId());
            saveRevision(add);
        }
        return inserted;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByBo(ModelBo bo) {
        // 锁定当前 Profile，使版本号递增与快照保存处于同一事务。
        Model current = baseMapper.selectByIdForUpdate(bo.getId());
        if (current == null) {
            throw new ServiceException("模型 Profile 不存在");
        }

        // 使用最新输入更新可编辑字段，并生成新的不可变发布修订。
        Model update = MapstructUtils.convert(bo, Model.class);
        update.setPublishedRevision(nextRevision(current));
        applyProviderEndpoint(update);
        applyContextDefaults(update);
        validEntityBeforeSave(update);
        boolean updated = baseMapper.updateById(update) > 0;
        if (updated) {
            // 重新读取数据库最终值，避免前端未提交的状态字段在发布快照中变成 null。
            Model persisted = baseMapper.selectById(bo.getId());
            if (persisted == null) {
                throw new ServiceException("模型更新后读取发布快照失败");
            }
            saveRevision(persisted);
        }
        return updated;
    }

    @Override
    public Boolean updateVisibility(Long id, String modelShow) {
        if (!SystemConstants.YES.equals(modelShow) && !SystemConstants.NO.equals(modelShow)) {
            throw new ServiceException("用户端可见性参数无效");
        }
        Model current = baseMapper.selectById(id);
        if (current == null) {
            throw new ServiceException("模型不存在或已删除");
        }
        Model update = new Model();
        update.setId(id);
        update.setModelShow(modelShow);
        return baseMapper.updateById(update) > 0;
    }

    /** 保存前校验能力标签格式、数值范围及厂商内的模型唯一性。 */
    private void validEntityBeforeSave(Model entity) {
        // 能力标签必须是 JSON 数组，灰度权重限制在 0-100 百分比区间。
        if (StringUtils.isNotBlank(entity.getCapabilities()) && !JsonUtils.isJsonArray(entity.getCapabilities())) {
            throw new ServiceException("模型能力标签必须是 JSON 数组");
        }
        if (entity.getGrayWeight() != null && (entity.getGrayWeight() < 0 || entity.getGrayWeight() > 100)) {
            throw new ServiceException("灰度权重必须在 0 到 100 之间");
        }

        // 上下文相关参数必须为正数，避免运行端计算出无效预算。
        validPositive(entity.getMaxContextTokens(), "最大上下文Token");
        validPositive(entity.getMaxInputTokens(), "最大输入Token");
        validPositive(entity.getMaxDocumentTokens(), "最大文档Token");
        validPositive(entity.getMaxOutputTokens(), "最大输出Token");
        validPositive(entity.getSafetyMarginTokens(), "上下文安全余量Token");
        validPositive(entity.getHistoryMaxTurns(), "最近历史最大轮数");
        validPositive(entity.getKnowledgeMaxChunks(), "知识库最大检索片段数");
        validPositive(entity.getKnowledgeMaxTokens(), "知识库资料最大Token");
        validPositive(entity.getToolResultMaxTokens(), "单次工具结果最大Token");
        if (ModelType.CHAT.getKey().equals(entity.getCategory()) && entity.getMaxOutputTokens() == null) {
            throw new ServiceException("聊天模型必须配置最大输出Token");
        }
        validateModelApiMode(entity);
        validateKnowledgeModel(entity);

        // 输入上限不能超过模型上下文窗口，但方舟等平台的最大输入与最大输出可分别等于独立官方上限。
        if (entity.getMaxContextTokens() != null && entity.getMaxInputTokens() != null
            && entity.getMaxInputTokens() > entity.getMaxContextTokens()) {
            throw new ServiceException("最大输入Token不能大于最大上下文Token");
        }
        if (entity.getMaxContextTokens() != null && entity.getMaxOutputTokens() != null
            && entity.getMaxContextTokens() <= entity.getMaxOutputTokens()) {
            throw new ServiceException("最大上下文Token必须大于最大输出Token");
        }
        // 唯一性校验与数据库联合唯一索引保持相同字段口径。
        boolean duplicate = baseMapper.existsByProfile(entity.getProviderCode(), entity.getModelName(),
            entity.getCategory(), entity.getId());
        if (duplicate) {
            throw new ServiceException("同一厂商下的模型名称和分类不能重复");
        }
    }

    /** API 模式必须与模型分类匹配，禁止把聊天协议固化到向量索引。 */
    private void validateModelApiMode(Model entity) {
        String category = entity.getCategory();
        String apiMode = entity.getApiMode();
        if (ModelType.CHAT.getKey().equals(category)
            && !AiDomainConstants.MODEL_CHAT_API_MODES.contains(apiMode)) {
            throw new ServiceException("聊天模型API调用模式必须为CHAT_COMPLETIONS或RESPONSES");
        }
        if (ModelType.IMAGE.getKey().equals(category)
            && !AiDomainConstants.MODEL_CHAT_API_MODES.contains(apiMode)) {
            throw new ServiceException("视觉模型API调用模式必须为CHAT_COMPLETIONS或RESPONSES");
        }
        if (ModelType.VECTOR.getKey().equals(category)
            && !AiDomainConstants.MODEL_EMBEDDING_API_MODES.contains(apiMode)) {
            throw new ServiceException("向量模型API调用模式必须为OPENAI_EMBEDDINGS或ARK_MULTIMODAL_EMBEDDINGS");
        }
    }

    /** 校验向量、视觉和重排模型的输入能力及执行参数。 */
    private void validateKnowledgeModel(Model entity) {
        boolean vector = ModelType.VECTOR.getKey().equals(entity.getCategory());
        boolean rerank = ModelType.RERANK.getKey().equals(entity.getCategory());
        boolean vision = ModelType.IMAGE.getKey().equals(entity.getCategory());
        if (!vector && !rerank && !vision) {
            return;
        }
        List<String> modalities = parseInputModalities(entity.getInputModalities());
        if (modalities.isEmpty()) {
            throw new ServiceException("知识库模型必须配置输入模态");
        }
        if (modalities.size() != modalities.stream().distinct().count()
            || modalities.stream().anyMatch(value -> !AiDomainConstants.MODEL_INPUT_MODALITIES.contains(value))) {
            throw new ServiceException("输入模态只能包含 TEXT、IMAGE 且不能重复");
        }
        entity.setInputModalities(JsonUtils.toJsonString(modalities));
        if (vector) {
            if (StringUtils.isBlank(entity.getApiMode())) {
                throw new ServiceException("向量模型必须配置API调用模式");
            }
            if (entity.getModelDimension() == null || entity.getModelDimension() < 1) {
                throw new ServiceException("向量模型必须配置有效维度");
            }
            if (entity.getMaxInputTokens() == null) {
                throw new ServiceException("向量模型必须配置最大输入Token");
            }
            if (entity.getNormalized() == null) {
                throw new ServiceException("向量模型必须明确是否归一化");
            }
        }
        if (vision && (StringUtils.isBlank(entity.getApiMode()) || entity.getMaxInputTokens() == null)) {
            throw new ServiceException("视觉模型必须配置API调用模式和最大输入Token");
        }
        if (rerank && (StringUtils.isBlank(entity.getAdapterCode()) || entity.getMaxDocumentTokens() == null)) {
            throw new ServiceException("Rerank 模型必须配置适配器编码和最大文档Token");
        }
        if (rerank && !AiDomainConstants.RERANK_ADAPTER_CODES.contains(entity.getAdapterCode())) {
            throw new ServiceException("Rerank 适配器编码不受支持");
        }
        if (rerank && AiDomainConstants.RERANK_ADAPTER_DASHSCOPE_TEXT.equals(entity.getAdapterCode())
            && !AiDomainConstants.MODEL_PROVIDER_QIANWEN.equals(entity.getProviderCode())) {
            throw new ServiceException("阿里云百炼重排序适配器只能用于阿里云百炼供应商");
        }
        if (rerank && AiDomainConstants.RERANK_ADAPTER_VOLCENGINE_KNOWLEDGE.equals(entity.getAdapterCode())
            && !AiDomainConstants.MODEL_PROVIDER_DOUBAO.equals(entity.getProviderCode())) {
            throw new ServiceException("火山引擎知识库重排序适配器只能用于豆包供应商");
        }
    }

    /** 只向上传页面暴露当前可完整固化为执行快照的模型。 */
    private boolean isKnowledgeModelComplete(KnowledgeModelOptionVo option) {
        if (option.getPublishedRevision() == null || option.getPublishedRevision() < 1
            || parseInputModalities(option.getInputModalities()).isEmpty()) {
            return false;
        }
        if (ModelType.VECTOR.getKey().equals(option.getCategory())) {
            return StringUtils.isNotBlank(option.getApiMode()) && option.getModelDimension() != null
                && option.getModelDimension() > 0 && option.getMaxInputTokens() != null
                && option.getMaxInputTokens() > 0 && option.getNormalized() != null;
        }
        if (ModelType.RERANK.getKey().equals(option.getCategory())) {
            return StringUtils.isNotBlank(option.getAdapterCode()) && option.getMaxDocumentTokens() != null
                && option.getMaxDocumentTokens() > 0;
        }
        return StringUtils.isNotBlank(option.getApiMode()) && option.getMaxInputTokens() != null
            && option.getMaxInputTokens() > 0;
    }

    private List<String> parseInputModalities(String value) {
        if (StringUtils.isBlank(value) || !JsonUtils.isJsonArray(value)) {
            return List.of();
        }
        try {
            return JsonUtils.parseArray(value, String.class).stream()
                .map(item -> item.trim().toUpperCase(Locale.ROOT))
                .toList();
        } catch (RuntimeException error) {
            return List.of();
        }
    }

    /** 对聊天模型补齐可由系统参数统一维护的上下文默认值。 */
    private void applyContextDefaults(Model entity) {
        if (!ModelType.CHAT.getKey().equals(entity.getCategory())) {
            return;
        }
        entity.setApiMode(businessDict.requireValue(AiDictTypes.MODEL_API_MODE, entity.getApiMode()));
        if (entity.getMaxContextTokens() == null) {
            entity.setMaxContextTokens(defaultValue(
                AiSystemConfigKeys.CHAT_CONTEXT_WINDOW_TOKENS, 16384, 2_000_000));
        }
        if (entity.getMaxInputTokens() == null) {
            entity.setMaxInputTokens(defaultValue(
                AiSystemConfigKeys.CHAT_MAX_INPUT_TOKENS, 16384, 2_000_000));
        }
        if (entity.getSafetyMarginTokens() == null) {
            entity.setSafetyMarginTokens(defaultValue(
                AiSystemConfigKeys.CHAT_SAFETY_MARGIN_TOKENS, 512, 100_000));
        }
        if (entity.getHistoryMaxTurns() == null) {
            entity.setHistoryMaxTurns(defaultValue(
                AiSystemConfigKeys.CHAT_HISTORY_MAX_TURNS, 10, 1_000));
        }
        if (entity.getKnowledgeMaxChunks() == null) {
            entity.setKnowledgeMaxChunks(defaultValue(
                AiSystemConfigKeys.CHAT_KNOWLEDGE_MAX_CHUNKS, 5, 100));
        }
        if (entity.getKnowledgeMaxTokens() == null) {
            entity.setKnowledgeMaxTokens(defaultValue(
                AiSystemConfigKeys.CHAT_KNOWLEDGE_MAX_TOKENS, 3000, 200_000));
        }
        if (entity.getToolResultMaxTokens() == null) {
            entity.setToolResultMaxTokens(defaultValue(
                AiSystemConfigKeys.CHAT_TOOL_RESULT_MAX_TOKENS, 1500, 200_000));
        }
    }

    /** 模型可固化独立执行地址；未填写时才继承厂商默认地址。 */
    private void applyProviderEndpoint(Model entity) {
        if ("custom_api".equalsIgnoreCase(entity.getProviderCode())) {
            if (StringUtils.isBlank(entity.getApiHost())) {
                throw new ServiceException("自定义厂商必须配置API地址");
            }
            return;
        }
        ModelProvider provider = modelProviderMapper.selectEnabledByProviderCode(entity.getProviderCode(),
            SystemConstants.NORMAL, SystemConstants.NORMAL);
        if (provider == null) {
            throw new ServiceException("所选厂商不存在或已停用");
        }
        if (StringUtils.isBlank(entity.getApiHost())) {
            if (StringUtils.isBlank(provider.getApiHost())) {
                throw new ServiceException("模型和厂商均未配置有效的API地址");
            }
            entity.setApiHost(provider.getApiHost());
        }
    }

    /** 校验可配置的正数值。 */
    private void validPositive(Integer value, String fieldName) {
        if (value != null && value <= 0) {
            throw new ServiceException(fieldName + "必须大于0");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        // 模型仍被智能体引用时禁止删除，避免运行时找不到默认或可选模型。
        if (Boolean.TRUE.equals(isValid)) {
            agentModelReferenceService.requireModelsUnreferenced(ids);
        }
        return baseMapper.deleteByIds(ids) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateCredentialKeyByProvider(String providerCode, String credentialKey) {
        // 凭证只保存别名；每个受影响 Profile 均生成修订快照，便于运行面精确加载。
        List<Model> models = baseMapper.selectByProviderCodeForUpdate(providerCode);
        if (models.isEmpty()) {
            return false;
        }
        for (Model model : models) {
            model.setCredentialKey(credentialKey);
            model.setPublishedRevision(nextRevision(model));
            if (baseMapper.updateById(model) != 1) {
                throw new ServiceException("更新模型凭证别名失败");
            }
            saveRevision(model);
        }
        return true;
    }

    /** 保存当前 Profile 的发布快照；快照仅含凭证别名，绝不包含实际 API Key。 */
    private void saveRevision(Model model) {
        ModelProfileRevision revision = new ModelProfileRevision();
        revision.setModelId(model.getId());
        revision.setRevision(model.getPublishedRevision());
        revision.setProfileSnapshot(JsonUtils.toJsonString(model));
        revision.setStatus(AiDomainConstants.CONFIG_REVISION_STATUS_PUBLISHED);
        modelProfileRevisionMapper.insert(revision);
    }

    /** 兼容旧数据中尚为 0 或为空的修订号。 */
    private int nextRevision(Model model) {
        return model.getPublishedRevision() == null ? 1 : model.getPublishedRevision() + 1;
    }

    /** 读取模型字段的系统默认值。 */
    private int defaultValue(String key, int defaultValue, int maximum) {
        return runtimeConfig.getPositiveInt(key, defaultValue, maximum);
    }
}
