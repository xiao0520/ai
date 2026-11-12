package org.ruoyi.service.knowledge.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ruoyi.domain.dto.knowledge.KnowledgeRetrievalConfig;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.common.core.utils.MapstructUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.json.utils.JsonUtils;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.knowledge.KnowledgeInfoBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeInfo;
import org.ruoyi.domain.vo.knowledge.KnowledgeDocumentCountVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.mapper.knowledge.KnowledgeDocumentMapper;
import org.ruoyi.mapper.knowledge.KnowledgeInfoMapper;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.knowledge.KnowledgeDeletionService;
import org.ruoyi.service.model.IModelProfileSnapshotService;
import org.ruoyi.enums.ModelType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.UUID;

/**
 * 知识库信息管理Service实现类
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Service
@RequiredArgsConstructor
public class KnowledgeInfoServiceImpl implements IKnowledgeInfoService {

    private final KnowledgeInfoMapper baseMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDeletionService deletionService;
    private final ObjectMapper objectMapper;
    private final IModelProfileSnapshotService modelSnapshots;

    @Override
    public KnowledgeInfoVo queryById(Long id) {
        return baseMapper.selectVoById(id);
    }

    @Override
    public KnowledgeRetrievalConfig queryRetrievalConfig(Long id) {
        KnowledgeRetrievalConfig saved = savedRetrievalConfig(id);
        return saved == null ? KnowledgeRetrievalConfig.defaults() : saved;
    }

    @Override
    public KnowledgeRetrievalConfig savedRetrievalConfig(Long id) {
        KnowledgeInfo entity = baseMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException("知识库不存在");
        }
        if (StringUtils.isBlank(entity.getRetrievalConfig())) {
            return null;
        }
        try {
            KnowledgeRetrievalConfig config = objectMapper.readValue(
                entity.getRetrievalConfig(), KnowledgeRetrievalConfig.class);
            config.validate();
            return config;
        } catch (Exception error) {
            throw new ServiceException("知识库检索配置无效，请重新保存");
        }
    }

    @Override
    public Boolean updateRetrievalConfig(Long id, KnowledgeRetrievalConfig config) {
        if (config == null) {
            throw new ServiceException("检索策略不能为空");
        }
        try {
            config.validate();
        } catch (IllegalArgumentException error) {
            throw new ServiceException(error.getMessage());
        }
        if (baseMapper.selectById(id) == null) {
            throw new ServiceException("知识库不存在");
        }
        if (config.rerankEnabled()) {
            requireTextRerankModel(config.rerankModelId());
        }
        KnowledgeInfo update = new KnowledgeInfo();
        update.setId(id);
        try {
            update.setRetrievalConfig(objectMapper.writeValueAsString(config));
        } catch (Exception error) {
            throw new ServiceException("检索策略序列化失败");
        }
        update.setUpdateTime(new Date());
        return baseMapper.updateById(update) > 0;
    }

    private void requireTextRerankModel(Long modelId) {
        var model = modelSnapshots.requireCurrent(modelId, ModelType.RERANK.getKey());
        if (StringUtils.isAnyBlank(model.providerCode(), model.modelName(),
                model.apiHost(), model.adapterCode(), model.credentialKey())
            || model.maxDocumentTokens() == null || model.maxDocumentTokens() < 1
            || model.inputModalities() == null || !model.inputModalities().contains("TEXT")) {
            throw new ServiceException("所选重排序模型未发布有效的文本重排配置");
        }
    }

    @Override
    public List<KnowledgeInfoVo> queryByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return baseMapper.selectKnowledgeVoByIds(ids);
    }

    @Override
    public TableDataInfo<KnowledgeInfoVo> queryAuthorizedPageList(KnowledgeInfoBo bo, PageQuery pageQuery,
                                                                   Collection<Long> authorizedIds) {
        // 授权范围作为强类型参数传入 Mapper，避免将数据权限藏在通用 params 容器中。
        Page<KnowledgeInfoVo> page = pageQuery.build();
        page.setRecords(baseMapper.selectKnowledgeList(page, bo, authorizedIds));

        // 当前页知识库统一加载文档数量，避免逐条统计产生 N+1 查询。
        fillDocumentCount(page.getRecords());
        return TableDataInfo.build(page);
    }

    @Override
    public List<KnowledgeInfoVo> queryList(KnowledgeInfoBo bo) {
        return baseMapper.selectKnowledgeList(null, bo, null);
    }

    /** 批量回填各知识库的文档数量，避免逐条统计产生 N+1 查询。 */
    private void fillDocumentCount(List<KnowledgeInfoVo> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Long> ids = records.stream().map(KnowledgeInfoVo::getId).toList();
        Map<Long, Integer> counts = documentMapper.selectDocumentCounts(ids, AiDomainConstants.KNOWLEDGE_STATUS_DELETED)
            .stream().collect(Collectors.toMap(KnowledgeDocumentCountVo::getKnowledgeId,
                KnowledgeDocumentCountVo::getDocumentCount, (left, right) -> left));
        records.forEach(vo -> vo.setDocumentCount(counts.getOrDefault(vo.getId(), 0)));
    }

    @Override
    public Boolean insertByBo(KnowledgeInfoBo bo) {
        // 初始化可编辑数据与不可变的知识库编码。
        KnowledgeInfo entity = MapstructUtils.convert(bo, KnowledgeInfo.class);
        validate(entity);
        if (StringUtils.isBlank(entity.getLibraryCode())) entity.setLibraryCode("knowledge-" + UUID.randomUUID());
        if (StringUtils.isBlank(entity.getAccessScope())) {
            entity.setAccessScope(AiDomainConstants.KNOWLEDGE_SCOPE_ACL_ONLY);
        }
        if (StringUtils.isBlank(entity.getStatus())) entity.setStatus(SystemConstants.NORMAL);
        try {
            entity.setRetrievalConfig(objectMapper.writeValueAsString(
                KnowledgeRetrievalConfig.defaults()));
        } catch (Exception error) {
            throw new ServiceException("知识库默认检索策略初始化失败");
        }

        // 创建首个权限修订号，供 Python 检索端识别授权配置变更。
        entity.setPermissionRevision(1);
        boolean inserted = baseMapper.insert(entity) > 0;
        if (inserted) bo.setId(entity.getId());
        return inserted;
    }

    @Override
    public Boolean updateByBo(KnowledgeInfoBo bo) {
        // 查询并保留不可由页面修改的知识库编码与当前索引版本。
        KnowledgeInfo current = baseMapper.selectById(bo.getId());
        if (current == null) {
            throw new ServiceException("知识库不存在");
        }
        KnowledgeInfo entity = MapstructUtils.convert(bo, KnowledgeInfo.class);
        validate(entity);
        entity.setLibraryCode(current.getLibraryCode());
        if (StringUtils.isBlank(entity.getAccessScope())) entity.setAccessScope(current.getAccessScope());
        entity.setCurrentIndexVersion(current.getCurrentIndexVersion());
        entity.setRetrievalConfig(current.getRetrievalConfig());
        // 每次配置更新均递增权限修订号，避免运行端继续使用旧授权缓存。
        int currentRevision = current.getPermissionRevision() == null ? 0 : current.getPermissionRevision();
        entity.setPermissionRevision(currentRevision + 1);
        entity.setUpdateTime(new Date());
        return baseMapper.updateById(entity) > 0;
    }

    @Override
    public Boolean updateStatus(Long id, String status) {
        // 状态仅允许在框架标准启用、停用值之间切换。
        if (!Set.of(SystemConstants.NORMAL, SystemConstants.DISABLE).contains(status)) {
            throw new ServiceException("知识库状态不正确");
        }
        KnowledgeInfo current = baseMapper.selectById(id);
        if (current == null) {
            throw new ServiceException("知识库不存在");
        }
        // 状态变化同样会改变可访问范围，必须递增权限修订号。
        KnowledgeInfo update = new KnowledgeInfo();
        update.setId(id);
        update.setStatus(status);
        update.setUpdateTime(new Date());
        int currentRevision = current.getPermissionRevision() == null ? 0 : current.getPermissionRevision();
        update.setPermissionRevision(currentRevision + 1);
        return baseMapper.updateById(update) > 0;
    }

    @Override
    public Boolean deleteById(Long id) {
        return deletionService.requestKnowledgeDeletion(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException("请选择要删除的知识库");
        }
        List<Long> orderedIds = ids.stream().filter(java.util.Objects::nonNull)
            .distinct().sorted().toList();
        if (orderedIds.isEmpty()) {
            throw new ServiceException("请选择要删除的知识库");
        }
        for (Long id : orderedIds) {
            deletionService.requestKnowledgeDeletion(id);
        }
        return true;
    }

    @Override
    public void lockForIndexActivation(Long id) {
        if (baseMapper.selectIdForUpdate(id) == null) {
            throw new ServiceException("知识库不存在");
        }
    }

    /** 校验访问范围白名单与切分配置的 JSON 格式。 */
    private void validate(KnowledgeInfo entity) {
        if (StringUtils.isNotBlank(entity.getAccessScope())
            && !Set.of(AiDomainConstants.KNOWLEDGE_SCOPE_ALL_INTERNAL,
                AiDomainConstants.KNOWLEDGE_SCOPE_ACL_ONLY).contains(entity.getAccessScope())) {
            throw new ServiceException("知识库访问范围不正确");
        }
        if (StringUtils.isNotBlank(entity.getSplitterConfig()) && !JsonUtils.isJsonObject(entity.getSplitterConfig())) {
            throw new ServiceException("splitterConfig 必须是JSON对象");
        }
    }

    @Override
    public Boolean activateIndexVersion(Long id, String indexVersion) {
        return baseMapper.updateCurrentIndexVersion(id, indexVersion) > 0;
    }

    @Override
    public Boolean clearCurrentIndexVersion(Long id, String expectedIndexVersion) {
        return baseMapper.clearCurrentIndexVersion(id, expectedIndexVersion) > 0;
    }
}
