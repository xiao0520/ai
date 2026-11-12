package org.ruoyi.service.skill.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.domain.dto.OssDTO;
import org.ruoyi.common.core.service.OssService;
import org.ruoyi.common.json.utils.JsonUtils;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.constant.AiDictTypes;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.constant.AiSystemConfigKeys;
import org.ruoyi.domain.bo.skill.SkillDefinitionBo;
import org.ruoyi.domain.entity.skill.SkillDefinition;
import org.ruoyi.domain.vo.skill.SkillDefinitionVo;
import org.ruoyi.domain.vo.skill.SkillBoundAgentVo;
import org.ruoyi.domain.vo.skill.SkillOptionVo;
import org.ruoyi.mapper.skill.SkillDefinitionMapper;
import org.ruoyi.service.skill.ISkillDefinitionService;
import org.ruoyi.service.agent.IAgentSkillReferenceService;
import org.ruoyi.support.AiBusinessDict;
import org.ruoyi.support.AiRuntimeConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Skill 定义管理服务。
 *
 * 第一阶段仅管理 Python 后续运行所需的契约和资源配置，不执行 Skill，也不维护 Skill 权限或发布版本。
 * Agent 只能绑定当前已启用的 Skill；删除前必须先解除全部 Agent 绑定。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SkillDefinitionServiceImpl implements ISkillDefinitionService {

    private final SkillDefinitionMapper baseMapper;
    private final IAgentSkillReferenceService agentSkillReferenceService;
    private final OssService ossService;
    private final AiBusinessDict businessDict;
    private final AiRuntimeConfig runtimeConfig;

    @Override
    public TableDataInfo<SkillDefinitionVo> queryPageList(SkillDefinitionBo bo, PageQuery pageQuery) {
        // 列表筛选与稳定排序由 Mapper XML 管理，服务层仅负责补充 Agent 绑定数量。
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<SkillDefinition> page = pageQuery.build();
        page.setRecords(baseMapper.selectSkillPage(page, bo));
        return new TableDataInfo<>(toVoList(page.getRecords()), page.getTotal());
    }

    @Override
    public List<SkillDefinitionVo> queryList(SkillDefinitionBo bo) {
        return toVoList(baseMapper.selectSkillList(bo));
    }

    @Override
    public SkillDefinitionVo query(Long id) {
        return toVo(requireSkill(id), Collections.emptyMap());
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean save(SkillDefinitionBo bo) {
        // 先加载既有配置并执行完整标准化，保证新建与修改遵循同一运行契约。
        SkillDefinition current = bo.getId() == null ? null : requireSkill(bo.getId());
        normalizeAndValidate(bo, current);
        SkillDefinition entity = toEntity(bo);
        if (current == null) {
            return baseMapper.insert(entity) > 0;
        }

        // 允许制品字段清空，更新成功后才考虑清理旧制品。
        boolean artifactChanged = !java.util.Objects.equals(current.getArtifactOssId(), entity.getArtifactOssId());
        boolean updated = baseMapper.updateWithNullableArtifact(entity) > 0;
        if (updated && artifactChanged && current.getArtifactOssId() != null) {
            // 外部 OSS 删除延迟到本地事务提交后，避免回滚导致仍在引用的制品丢失。
            deleteArtifactAfterCommit(current.getArtifactOssId());
        }
        return updated;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean changeStatus(Long id, String status) {
        // 仅允许框架定义的启停状态，避免无效状态进入 Agent 可绑定条件。
        if (!SystemConstants.NORMAL.equals(status) && !SystemConstants.DISABLE.equals(status)) {
            throw new ServiceException("Skill状态不正确");
        }
        SkillDefinition current = requireSkill(id);
        if (SystemConstants.NORMAL.equals(status)) {
            // 启用前复用保存校验，阻止残缺配置进入运行时。
            SkillDefinitionBo bo = toBo(current);
            normalizeAndValidate(bo, current);
        }
        SkillDefinition update = new SkillDefinition();
        update.setId(id);
        update.setStatus(status);
        return baseMapper.updateById(update) > 0;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean delete(Long id) {
        // 已绑定的 Skill 不能删除，先返回清晰的关联 Agent 名称供管理员处理。
        SkillDefinition current = requireSkill(id);
        List<String> agentNames = listBoundAgentNames(current.getId());
        if (!agentNames.isEmpty()) {
            throw new ServiceException("Skill已绑定Agent：" + String.join("、", agentNames) + "，请先在Agent中解除绑定");
        }
        boolean deleted = baseMapper.deleteById(current.getId()) > 0;
        if (deleted && current.getArtifactOssId() != null) {
            // 删除记录提交成功后再清理未被引用的制品，保留失败后的补偿机会。
            deleteArtifactAfterCommit(current.getArtifactOssId());
        }
        return deleted;
    }

    @Override
    public List<SkillBoundAgentVo> listBoundAgents(Long id) {
        // 绑定关系以 Skill 编码关联，已删除的 Agent 由引用服务过滤后不再返回。
        SkillDefinition skill = requireSkill(id);
        return agentSkillReferenceService.listBindings(skill.getSkillName()).stream().map(binding -> {
            SkillBoundAgentVo vo = new SkillBoundAgentVo();
            vo.setAgentId(binding.getAgentId());
            vo.setAgentCode(binding.getAgentCode());
            vo.setAgentName(binding.getAgentName());
            vo.setStatus(binding.getStatus());
            vo.setConfigJson(binding.getConfigJson());
            return vo;
        }).toList();
    }

    /**
     * 保留删除校验使用的绑定 Agent 名称列表
     *
     * @param id 主键
     * @return Agent 名称列表
     */
    private List<String> listBoundAgentNames(Long id) {
        return listBoundAgents(id).stream().map(SkillBoundAgentVo::getAgentName)
            .filter(StringUtils::hasText).distinct().toList();
    }

    @Override
    public List<SkillOptionVo> options() {
        // name 始终返回稳定编码 skillName，显示名称仅用于界面展示。
        return baseMapper.selectEnabledOptions();
    }

    @Override
    public OssDTO uploadArtifact(MultipartFile file) {
        // 仅允许上传 Markdown 制品，存储目录由系统参数指定而非前端文件名。
        if (file == null || file.isEmpty()) {
            throw new ServiceException("Skill文档不能为空");
        }
        String suffix = StringUtils.getFilenameExtension(file.getOriginalFilename());
        if (!AiDomainConstants.SKILL_ARTIFACT_EXTENSION.equalsIgnoreCase(suffix)) {
            throw new ServiceException("Skill仅支持上传 Markdown（.md）文档");
        }
        String businessPath = runtimeConfig.getRequiredString(AiSystemConfigKeys.SKILL_ARTIFACT_OSS_CONFIG_KEY);
        return ossService.uploadBusinessFile(file, businessPath);
    }

    /** 标准化并校验 Skill 配置，新建与修改遵循同一运行契约。 */
    private void normalizeAndValidate(SkillDefinitionBo bo, SkillDefinition current) {
        // Skill 编码是 Agent 运行时的稳定契约，修改时禁止更换且必须保持全局唯一。
        String skillName = trim(bo.getSkillName());
        if (!skillName.matches(AiDomainConstants.SKILL_NAME_PATTERN)) {
            throw new ServiceException("Skill编码仅支持小写字母、数字和短横线，且必须以字母开头");
        }
        if (current != null && !current.getSkillName().equals(skillName)) {
            throw new ServiceException("Skill编码创建后不可修改");
        }
        if (baseMapper.existsBySkillName(skillName, bo.getId())) {
            throw new ServiceException("Skill编码已存在");
        }

        // 运行时类型受系统字典约束，入口、摘要和 JSON 配置需在入库前完成格式校验。
        String runtime = trim(bo.getRuntime());
        if (!StringUtils.hasText(runtime)) {
            throw new ServiceException("Skill运行时不能为空");
        }
        runtime = businessDict.requireValue(AiDictTypes.SKILL_RUNTIME, runtime.toUpperCase(Locale.ROOT));
        if (!StringUtils.hasText(bo.getEntrypoint())) {
            throw new ServiceException("Skill入口不能为空");
        }
        String digest = trim(bo.getDigest());
        if (StringUtils.hasText(digest) && !digest.matches(AiDomainConstants.SHA_256_HEX_PATTERN)) {
            throw new ServiceException("资源摘要必须是64位SHA-256十六进制字符串");
        }
        validateJsonObject(bo.getParameterSchema(), "输入参数Schema");
        validateJsonObject(bo.getOutputSchema(), "输出参数Schema");
        validateJsonObject(bo.getResourceLimits(), "资源限制");

        // 最后回填规范化字段与默认状态，后续实体转换无需再重复处理空白值。
        String status = defaultValue(trim(bo.getStatus()),
            current == null ? SystemConstants.DISABLE : current.getStatus());
        if (!SystemConstants.NORMAL.equals(status) && !SystemConstants.DISABLE.equals(status)) {
            throw new ServiceException("Skill状态不正确");
        }
        bo.setSkillName(skillName);
        bo.setDisplayName(defaultValue(trim(bo.getDisplayName()), skillName));
        bo.setRuntime(runtime);
        bo.setEntrypoint(trim(bo.getEntrypoint()));
        bo.setDigest(emptyToNull(digest));
        bo.setStatus(status);
    }

    /** 校验非空配置必须是合法的 JSON 对象。 */
    private void validateJsonObject(String value, String fieldName) {
        if (StringUtils.hasText(value) && !JsonUtils.isJsonObject(value)) {
            throw new ServiceException(fieldName + "必须是JSON对象");
        }
    }

    /** 查询 Skill 并在不存在时抛出业务异常。 */
    private SkillDefinition requireSkill(Long id) {
        SkillDefinition skill = baseMapper.selectById(id);
        if (skill == null) {
            throw new ServiceException("Skill不存在或已删除");
        }
        return skill;
    }

    /** 批量转换视图对象，并统一回填各 Skill 的 Agent 绑定数量。 */
    private List<SkillDefinitionVo> toVoList(List<SkillDefinition> skills) {
        if (skills.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> skillNames = skills.stream().map(SkillDefinition::getSkillName).toList();
        Map<String, Long> counts = agentSkillReferenceService.countBindings(skillNames);
        return skills.stream().map(skill -> toVo(skill, counts)).toList();
    }

    /** 转换单个 Skill 视图，工具白名单由 JSON 反序列化恢复。 */
    private SkillDefinitionVo toVo(SkillDefinition source, Map<String, Long> agentCounts) {
        SkillDefinitionVo target = new SkillDefinitionVo();
        target.setId(source.getId());
        target.setSkillName(source.getSkillName());
        target.setDisplayName(source.getDisplayName());
        target.setDescription(source.getDescription());
        target.setRuntime(source.getRuntime());
        target.setEntrypoint(source.getEntrypoint());
        target.setArtifactOssId(source.getArtifactOssId());
        target.setDigest(source.getDigest());
        target.setParameterSchema(source.getParameterSchema());
        target.setOutputSchema(source.getOutputSchema());
        target.setAllowedToolIds(parseLongArray(source.getAllowedToolIds()));
        target.setResourceLimits(source.getResourceLimits());
        target.setStatus(source.getStatus());
        target.setAgentCount(agentCounts.getOrDefault(source.getSkillName(), 0L));
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getUpdateTime());
        return target;
    }

    /** 解析 JSON 数组为长整型列表，空值返回空列表。 */
    private List<Long> parseLongArray(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        return JsonUtils.parseArray(json, Long.class);
    }

    /** 将不可逆的 OSS 清理延迟到数据库事务提交后执行。 */
    private void deleteArtifactAfterCommit(Long ossId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteArtifactIfUnreferenced(ossId);
            }
        });
    }

    /** OSS 清理失败仅记录补偿线索，不影响已提交的 Skill 配置事务。 */
    private void deleteArtifactIfUnreferenced(Long ossId) {
        if (baseMapper.existsByArtifactOssId(ossId)) {
            return;
        }
        try {
            ossService.deleteFile(ossId);
        } catch (Exception e) {
            log.error("Skill 配置提交后清理 OSS 制品失败，ossId={}", ossId, e);
        }
    }

    /** 将请求对象转换为持久化实体，工具白名单序列化为 JSON 存储。 */
    private SkillDefinition toEntity(SkillDefinitionBo source) {
        SkillDefinition target = new SkillDefinition();
        target.setId(source.getId());
        target.setSkillName(source.getSkillName());
        target.setDisplayName(source.getDisplayName());
        target.setDescription(source.getDescription());
        target.setRuntime(source.getRuntime());
        target.setEntrypoint(source.getEntrypoint());
        target.setArtifactOssId(source.getArtifactOssId());
        target.setDigest(source.getDigest());
        target.setParameterSchema(source.getParameterSchema());
        target.setOutputSchema(source.getOutputSchema());
        target.setAllowedToolIds(source.getAllowedToolIds() == null
            ? null : JsonUtils.toJsonString(source.getAllowedToolIds()));
        target.setResourceLimits(source.getResourceLimits());
        target.setStatus(source.getStatus());
        return target;
    }

    /** 将既有实体还原为请求对象，供启用前的完整校验复用。 */
    private SkillDefinitionBo toBo(SkillDefinition source) {
        SkillDefinitionBo target = new SkillDefinitionBo();
        target.setId(source.getId());
        target.setSkillName(source.getSkillName());
        target.setDisplayName(source.getDisplayName());
        target.setDescription(source.getDescription());
        target.setRuntime(source.getRuntime());
        target.setEntrypoint(source.getEntrypoint());
        target.setArtifactOssId(source.getArtifactOssId());
        target.setDigest(source.getDigest());
        target.setParameterSchema(source.getParameterSchema());
        target.setOutputSchema(source.getOutputSchema());
        target.setAllowedToolIds(parseLongArray(source.getAllowedToolIds()));
        target.setResourceLimits(source.getResourceLimits());
        target.setStatus(source.getStatus());
        return target;
    }

    /** 去除首尾空白，空值按空字符串处理以便统一校验。 */
    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /** 空白字符串归一为空值，避免向数据库写入无意义空串。 */
    private String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    /** 原值为空白时返回回退值。 */
    private String defaultValue(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
