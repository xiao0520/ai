package org.ruoyi.service.agent.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ruoyi.domain.vo.model.ModelVo;
import org.ruoyi.constant.AiDictTypes;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.service.model.IChatModelService;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.common.core.utils.MapstructUtils;
import org.ruoyi.common.json.utils.JsonUtils;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.domain.bo.agent.AgentAclItemBo;
import org.ruoyi.domain.bo.agent.AgentAclSaveBo;
import org.ruoyi.domain.bo.agent.AgentBo;
import org.ruoyi.domain.entity.agent.Agent;
import org.ruoyi.domain.entity.agent.AgentAcl;
import org.ruoyi.domain.entity.agent.AgentKnowledge;
import org.ruoyi.domain.entity.agent.AgentModel;
import org.ruoyi.domain.entity.agent.AgentSkill;
import org.ruoyi.domain.entity.mcp.AgentMcpTool;
import org.ruoyi.domain.vo.agent.AgentAclVo;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.domain.vo.mcp.McpToolVo;
import org.ruoyi.domain.vo.skill.SkillOptionVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.domain.vo.token.AiModelOptionVo;
import org.ruoyi.mapper.agent.AgentAclMapper;
import org.ruoyi.mapper.agent.AgentKnowledgeMapper;
import org.ruoyi.mapper.agent.AgentMapper;
import org.ruoyi.mapper.agent.AgentModelMapper;
import org.ruoyi.mapper.agent.AgentSkillMapper;
import org.ruoyi.mapper.mcp.AgentMcpToolMapper;
import org.ruoyi.service.agent.IAgentService;
import org.ruoyi.service.mcp.IMcpToolService;
import org.ruoyi.service.skill.ISkillDefinitionService;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.token.IAiRoleModelPermissionService;
import org.ruoyi.support.AiBusinessDict;
import org.ruoyi.system.domain.bo.SysDeptBo;
import org.ruoyi.system.domain.bo.SysRoleBo;
import org.ruoyi.system.domain.vo.SysDeptVo;
import org.ruoyi.system.domain.vo.SysRoleVo;
import org.ruoyi.system.domain.vo.SysUserVo;
import org.ruoyi.system.service.ISysDeptService;
import org.ruoyi.system.service.ISysRoleService;
import org.ruoyi.system.service.ISysUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 智能体配置、绑定关系与访问权限管理服务。
 *
 * Agent 主表保存稳定标识和运行策略，模型、MCP 工具、Skill、知识库及 ACL 通过关系表维护。
 * 保存时在同一事务内全量替换绑定，查询选项时同时计算 Agent ACL 与用户模型权限交集。
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements IAgentService {

    private final AgentMapper baseMapper;
    private final AgentKnowledgeMapper agentKnowledgeMapper;
    private final AgentModelMapper agentModelMapper;
    private final AgentSkillMapper agentSkillMapper;
    private final AgentAclMapper agentAclMapper;
    private final AgentMcpToolMapper agentMcpToolMapper;
    private final IMcpToolService mcpToolService;
    private final IKnowledgeInfoService knowledgeInfoService;
    private final IChatModelService chatModelService;
    private final ISkillDefinitionService skillDefinitionService;
    private final IAiRoleModelPermissionService roleModelPermissionService;
    private final ISysRoleService roleService;
    private final ISysDeptService deptService;
    private final ISysUserService userService;
    private final AiBusinessDict businessDict;

    @Override
    public TableDataInfo<AgentVo> queryPageList(AgentBo bo, PageQuery pageQuery) {
        // 筛选与排序规则由 Mapper XML 管理，查询后的关联数据再由业务层统一装配。
        Page<Agent> page = pageQuery.build();
        page.setRecords(baseMapper.selectAgentList(page, bo));

        // 当前页智能体逐项转换时只读取已限定范围的关联数据。
        List<AgentVo> records = page.getRecords() == null ? List.of()
            : page.getRecords().stream().map(this::toVo).toList();
        Page<AgentVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(records);
        return TableDataInfo.build(voPage);
    }

    @Override
    public List<AgentVo> queryList(AgentBo bo) {
        return baseMapper.selectAgentList(null, bo).stream().map(this::toVo).toList();
    }

    @Override
    public AgentVo queryById(Long id) {
        Agent entity = baseMapper.selectById(id);
        return entity == null ? null : toVo(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean insertByBo(AgentBo bo) {
        // 校验请求并标准化字典值，防止无效配置进入智能体运行面。
        validateAgent(bo);
        Agent entity = MapstructUtils.convert(bo, Agent.class);
        normalizeBusinessOptions(entity);

        // 兼容旧版本 JSON 字段，同时以关联表作为后续运行时的权威来源。
        writeLegacyJson(entity, bo);
        boolean inserted = baseMapper.insert(entity) > 0;
        if (inserted) {
            // 主表写入成功后同步保存模型、工具、Skill 和知识库绑定。
            saveBindings(entity.getId(), bo);
        }
        return inserted;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByBo(AgentBo bo) {
        // 保持 Agent 编码稳定，避免外部调用或历史会话指向失效标识。
        Agent current = baseMapper.selectById(bo.getId());
        if (current == null) {
            throw new ServiceException("Agent 不存在");
        }
        if (!current.getAgentCode().equals(bo.getAgentCode() == null ? null : bo.getAgentCode().trim())) {
            throw new ServiceException("Agent编码创建后不允许修改");
        }

        // 校验输入并对可维护选项做字典标准化后更新主表。
        validateAgent(bo);
        Agent entity = MapstructUtils.convert(bo, Agent.class);
        normalizeBusinessOptions(entity);
        writeLegacyJson(entity, bo);
        boolean updated = baseMapper.updateById(entity) > 0;
        if (updated) {
            // 仅在主表更新成功后整体替换关联配置，保证事务内状态一致。
            saveBindings(entity.getId(), bo);
        }
        return updated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteByIds(Collection<Long> ids) {
        // 先解除全部关联表记录，防止删除主表后遗留无归属授权或工具绑定。
        agentMcpToolMapper.deleteByAgentIds(ids);
        agentKnowledgeMapper.deleteByAgentIds(ids);
        agentModelMapper.deleteByAgentIds(ids);
        agentSkillMapper.deleteByAgentIds(ids);
        agentAclMapper.deleteByAgentIds(ids);

        // 所有关联清理完成后删除智能体主记录。
        return baseMapper.deleteByIds(ids) > 0;
    }

    @Override
    public List<AgentVo> queryEnabledOptions() {
        // 未登录用户不暴露任何选项，管理员直接可见全部启用智能体。
        if (!LoginHelper.isLogin()) {
            return Collections.emptyList();
        }
        List<Agent> enabledAgents = baseMapper.selectEnabledList(SystemConstants.NORMAL);
        Long userId = LoginHelper.getUserId();
        if (LoginHelper.isAdmin(userId)) {
            return enabledAgents.stream().map(this::toVo).toList();
        }

        // 普通用户同时按 ACL 授权与有效模型权限过滤，避免出现无可用模型的选项。
        Set<Long> accessibleAgentIds = queryAccessibleAgentIds(userId);
        Set<Long> effectiveModelIds = roleModelPermissionService.queryEffectiveModels(userId).stream()
            .map(AiModelOptionVo::getModelId).collect(Collectors.toSet());
        return enabledAgents.stream()
            .filter(agent -> accessibleAgentIds.contains(agent.getId()))
            .filter(agent -> isModelSelectable(agent.getId(), agent.getModelId(),
                agent.getModelSelectPolicy(), effectiveModelIds))
            .map(this::toVo).toList();
    }

    @Override
    public List<AiModelOptionVo> queryAvailableModels(Long id) {
        // 未登录、智能体不存在或未启用时均不暴露模型选项。
        if (!LoginHelper.isLogin()) {
            return Collections.emptyList();
        }
        Agent agent = baseMapper.selectById(id);
        if (agent == null || !SystemConstants.NORMAL.equals(agent.getStatus())) {
            return Collections.emptyList();
        }
        Long userId = LoginHelper.getUserId();
        if (!LoginHelper.isAdmin(userId) && !queryAccessibleAgentIds(userId).contains(id)) {
            return Collections.emptyList();
        }
        // 固定策略下只返回默认模型；可选策略取绑定范围与用户有效模型的交集。
        List<AiModelOptionVo> effectiveModels = roleModelPermissionService.queryEffectiveModels(userId);
        if (AiDomainConstants.AGENT_MODEL_SELECT_POLICY_FIXED.equals(agent.getModelSelectPolicy())) {
            return effectiveModels.stream().filter(model -> agent.getModelId().equals(model.getModelId())).toList();
        }
        // 未配置绑定时回退到默认模型，保证历史智能体仍可继续会话。
        List<Long> boundModelIds = agentModelMapper.selectEnabledModelIdsByAgentId(id, SystemConstants.NORMAL);
        if (boundModelIds.isEmpty()) {
            boundModelIds = List.of(agent.getModelId());
        }
        Set<Long> allowedIds = Set.copyOf(boundModelIds);
        return effectiveModels.stream().filter(model -> allowedIds.contains(model.getModelId())).toList();
    }

    @Override
    public List<AgentAclVo> listAcl(Long id) {
        requireAgent(id);
        // 预加载角色与部门名称映射，避免逐条授权记录查询主体名称。
        Map<Long, String> roleNames = roleService.selectRoleList(new SysRoleBo()).stream()
            .collect(Collectors.toMap(SysRoleVo::getRoleId, SysRoleVo::getRoleName, (left, right) -> left));
        Map<Long, String> deptNames = deptService.selectDeptList(new SysDeptBo()).stream()
            .collect(Collectors.toMap(SysDeptVo::getDeptId, SysDeptVo::getDeptName, (left, right) -> left));
        List<AgentAcl> aclRows = agentAclMapper.selectByAgentId(id);
        return aclRows.stream().map(row -> toAclVo(row, resolveSubjectName(row, roleNames, deptNames))).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveAcl(Long id, AgentAclSaveBo command) {
        // 校验 Agent 存在性及授权主体、时间窗和字典值。
        requireAgent(id);
        List<AgentAclItemBo> items = command.getItems() == null ? Collections.emptyList() : command.getItems();
        validateAclItems(items);

        // ACL 采用全量覆盖策略，空列表表示清空当前 Agent 的全部授权。
        agentAclMapper.deleteByAgentIds(List.of(id));
        if (items.isEmpty()) {
            return true;
        }

        // 将经过校验的请求项转换为关联记录并批量写入。
        return agentAclMapper.insertBatch(items.stream().map(item -> {
            AgentAcl acl = new AgentAcl();
            acl.setAgentId(id);
            acl.setSubjectType(item.getSubjectType().trim().toUpperCase(Locale.ROOT));
            acl.setSubjectId(item.getSubjectId());
            acl.setPermission(businessDict.requireValue(AiDictTypes.AGENT_ACL_PERMISSION, item.getPermission()));
            acl.setEffectiveTime(item.getEffectiveTime());
            acl.setExpireTime(item.getExpireTime());
            acl.setStatus(defaultValue(item.getStatus(), SystemConstants.NORMAL));
            acl.setRemark(item.getRemark());
            return acl;
        }).toList());
    }

    /** 转换智能体详情，并加载当前有效的关联配置。 */
    private AgentVo toVo(Agent entity) {
        // 复制智能体自身配置，关联配置始终以关系表记录为准。
        AgentVo vo = toBasicVo(entity);
        List<Long> toolIds = listEnabledToolIds(entity.getId());
        List<String> skillNames = resolveSkillNames(entity);
        List<Long> knowledgeIds = resolveKnowledgeIds(entity);
        List<Long> modelIds = resolveModelIds(entity);

        // 回填关联标识，供编辑页直接复用。
        vo.setMcpToolIds(toolIds);
        vo.setSkillNames(skillNames);
        vo.setKnowledgeIds(knowledgeIds);
        vo.setModelIds(modelIds);

        // 批量加载关联展示名称，避免按单个模型或知识库循环查询。
        fillModelNames(vo, entity.getModelId(), modelIds);
        fillToolNames(vo, toolIds);
        fillKnowledgeNames(vo, knowledgeIds);
        return vo;
    }

    /** 复制智能体自身字段，不包含关联表数据。 */
    private AgentVo toBasicVo(Agent entity) {
        AgentVo vo = new AgentVo();
        vo.setId(entity.getId());
        vo.setAgentCode(entity.getAgentCode());
        vo.setAgentName(entity.getAgentName());
        vo.setAgentType(entity.getAgentType());
        vo.setAgentDescribe(entity.getAgentDescribe());
        vo.setAgentShow(entity.getAgentShow());
        vo.setModelId(entity.getModelId());
        vo.setModelSelectPolicy(entity.getModelSelectPolicy());
        vo.setKnowledgeScopePolicy(entity.getKnowledgeScopePolicy());
        vo.setEnableThinking(entity.getEnableThinking());
        vo.setSystemPrompt(entity.getSystemPrompt());
        vo.setWelcomeMessage(entity.getWelcomeMessage());
        vo.setSuggestedQuestions(parseStringArray(entity.getSuggestedQuestions()));
        vo.setExecutionPolicy(entity.getExecutionPolicy());
        vo.setStatus(entity.getStatus());
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }

    /** 查询智能体绑定且启用的 MCP 工具。 */
    private List<Long> listEnabledToolIds(Long agentId) {
        return agentMcpToolMapper.selectEnabledToolIdsByAgentId(agentId, SystemConstants.NORMAL);
    }

    /** 优先读取关系表，兼容尚未迁移的历史 Skill JSON。 */
    private List<String> resolveSkillNames(Agent entity) {
        List<String> skillNames = agentSkillMapper.selectEnabledSkillNamesByAgentId(
            entity.getId(), SystemConstants.NORMAL);
        return skillNames.isEmpty() ? parseStringArray(entity.getSkillNames()) : skillNames;
    }

    /** 优先读取关系表，兼容尚未迁移的历史知识库 JSON。 */
    private List<Long> resolveKnowledgeIds(Agent entity) {
        List<Long> knowledgeIds = agentKnowledgeMapper.selectEnabledKnowledgeIdsByAgentId(
            entity.getId(), SystemConstants.NORMAL);
        return knowledgeIds.isEmpty() ? parseLongArray(entity.getKnowledgeIds()) : knowledgeIds;
    }

    /** 查询关系表中的可选模型，历史数据退回到默认模型。 */
    private List<Long> resolveModelIds(Agent entity) {
        List<Long> modelIds = agentModelMapper.selectEnabledModelIdsByAgentId(entity.getId(), SystemConstants.NORMAL);
        if (!modelIds.isEmpty() || entity.getModelId() == null) {
            return modelIds;
        }
        return List.of(entity.getModelId());
    }

    /** 批量回填默认模型与可选模型名称。 */
    private void fillModelNames(AgentVo vo, Long defaultModelId, List<Long> modelIds) {
        if (modelIds.isEmpty()) {
            return;
        }
        Map<Long, ModelVo> models = chatModelService.queryByIds(modelIds).stream()
            .collect(Collectors.toMap(ModelVo::getId, model -> model, (left, right) -> left));
        ModelVo defaultModel = models.get(defaultModelId);
        if (defaultModel != null) {
            vo.setModelName(defaultModel.getModelName());
        }
        vo.setModelNames(modelIds.stream().map(models::get).filter(java.util.Objects::nonNull)
            .map(ModelVo::getModelName).toList());
    }

    /** 批量回填 MCP 工具展示名称。 */
    private void fillToolNames(AgentVo vo, List<Long> toolIds) {
        if (!toolIds.isEmpty()) {
            Map<Long, McpToolVo> tools = mcpToolService.queryByIds(toolIds).stream()
                .collect(Collectors.toMap(McpToolVo::getId, tool -> tool, (left, right) -> left));
            vo.setMcpToolNames(toolIds.stream().map(tools::get).filter(java.util.Objects::nonNull)
                .map(McpToolVo::getDisplayName).toList());
        }
    }

    /** 批量回填知识库展示名称。 */
    private void fillKnowledgeNames(AgentVo vo, List<Long> knowledgeIds) {
        if (knowledgeIds.isEmpty()) {
            return;
        }
        Map<Long, KnowledgeInfoVo> knowledgeMap = knowledgeInfoService.queryByIds(knowledgeIds).stream()
            .collect(Collectors.toMap(KnowledgeInfoVo::getId, knowledge -> knowledge, (left, right) -> left));
        vo.setKnowledgeNames(knowledgeIds.stream().map(knowledgeMap::get).filter(java.util.Objects::nonNull)
            .map(KnowledgeInfoVo::getName).toList());
    }

    /** 将关联配置同步写入历史 JSON 字段，兼容尚未迁移的旧版读取逻辑。 */
    private void writeLegacyJson(Agent entity, AgentBo bo) {
        entity.setSkillNames(JsonUtils.toJsonString(bo.getSkillNames()));
        entity.setKnowledgeIds(JsonUtils.toJsonString(bo.getKnowledgeIds()));
        entity.setSuggestedQuestions(JsonUtils.toJsonString(bo.getSuggestedQuestions()));
    }

    /** 将请求中的可维护选项映射为系统字典标准值。 */
    private void normalizeBusinessOptions(Agent entity) {
        entity.setStatus(businessDict.requireValue(AiDictTypes.AGENT_STATUS, entity.getStatus()));
        entity.setEnableThinking(businessDict.requireValue(AiDictTypes.FLAG_YES_NO, entity.getEnableThinking()));
        entity.setAgentType(businessDict.requireValue(AiDictTypes.AGENT_TYPE, entity.getAgentType()));
        entity.setModelSelectPolicy(businessDict.requireValue(AiDictTypes.AGENT_MODEL_SELECT_POLICY,
            entity.getModelSelectPolicy()));
        entity.setKnowledgeScopePolicy(businessDict.requireValue(AiDictTypes.AGENT_KNOWLEDGE_SCOPE_POLICY,
            entity.getKnowledgeScopePolicy()));
    }

    /** 全量替换智能体的模型、工具、Skill 与知识库绑定。 */
    private void saveBindings(Long agentId, AgentBo bo) {
        // 关联配置按领域分别维护，避免单个大方法混杂多张关系表操作。
        saveModels(agentId, bo.getModelIds(), bo.getModelId());
        saveMcpTools(agentId, bo.getMcpToolIds());
        saveSkills(agentId, bo.getSkillNames());
        saveKnowledgeRanges(agentId, bo.getKnowledgeIds());
    }

    /** 重建模型绑定，默认模型必须包含在可选模型范围内。 */
    private void saveModels(Long agentId, List<Long> requestedIds, Long defaultModelId) {
        List<Long> modelIds = requestedIds == null ? List.of(defaultModelId)
            : requestedIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (!modelIds.contains(defaultModelId)) {
            throw new ServiceException("默认模型必须包含在可选模型中");
        }
        validateExistingModels(modelIds);
        agentModelMapper.deleteByAgentIds(List.of(agentId));
        agentModelMapper.insertBatch(modelIds.stream().map(modelId -> {
            AgentModel row = new AgentModel();
            row.setAgentId(agentId);
            row.setModelId(modelId);
            row.setIsDefault(modelId.equals(defaultModelId)
                ? AiDomainConstants.FLAG_ENABLED : AiDomainConstants.FLAG_DISABLED);
            row.setStatus(SystemConstants.NORMAL);
            return row;
        }).toList());
    }

    /** 重建 MCP 工具绑定，先删除旧绑定再写入新绑定。 */
    private void saveMcpTools(Long agentId, List<Long> requestedIds) {
        List<Long> toolIds = requestedIds == null ? Collections.emptyList()
            : requestedIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        validateSelectableMcpTools(toolIds);
        agentMcpToolMapper.deleteByAgentIds(List.of(agentId));
        if (toolIds.isEmpty()) {
            return;
        }
        agentMcpToolMapper.insertBatch(toolIds.stream().map(toolId -> {
            AgentMcpTool row = new AgentMcpTool();
            row.setAgentId(agentId);
            row.setToolId(toolId);
            row.setStatus(SystemConstants.NORMAL);
            return row;
        }).toList());
    }

    /** 重建 Skill 绑定，仅保留存在且启用的 Skill。 */
    private void saveSkills(Long agentId, List<String> requestedNames) {
        List<String> skillNames = requestedNames == null ? Collections.emptyList()
            : requestedNames.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
        validateSkills(skillNames);
        agentSkillMapper.deleteByAgentIds(List.of(agentId));
        if (skillNames.isEmpty()) {
            return;
        }
        agentSkillMapper.insertBatch(skillNames.stream().map(skillName -> {
            AgentSkill row = new AgentSkill();
            row.setAgentId(agentId);
            row.setSkillName(skillName);
            row.setStatus(SystemConstants.NORMAL);
            return row;
        }).toList());
    }

    /** 重建知识库绑定，仅保留存在且启用的知识库。 */
    private void saveKnowledgeRanges(Long agentId, List<Long> requestedIds) {
        List<Long> knowledgeIds = requestedIds == null ? Collections.emptyList()
            : requestedIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        validateKnowledgeRanges(knowledgeIds);
        agentKnowledgeMapper.deleteByAgentIds(List.of(agentId));
        if (knowledgeIds.isEmpty()) {
            return;
        }
        agentKnowledgeMapper.insertBatch(knowledgeIds.stream().map(knowledgeId -> {
            AgentKnowledge row = new AgentKnowledge();
            row.setAgentId(agentId);
            row.setKnowledgeId(knowledgeId);
            row.setStatus(SystemConstants.NORMAL);
            return row;
        }).toList());
    }

    /** 委托 MCP 工具服务校验所选工具是否可绑定。 */
    private void validateSelectableMcpTools(List<Long> toolIds) {
        mcpToolService.validateSelectableTools(toolIds);
    }

    /** 校验所选 Skill 均存在且处于启用状态。 */
    private void validateSkills(List<String> skillNames) {
        if (skillNames.isEmpty()) {
            return;
        }
        Set<String> enabledNames = skillDefinitionService.options().stream()
            .map(SkillOptionVo::getName).collect(Collectors.toSet());
        if (!enabledNames.containsAll(skillNames)) {
            throw new ServiceException("所选Skill包含不存在或已停用项");
        }
    }

    /** 校验所选知识库均存在且处于启用状态。 */
    private void validateKnowledgeRanges(List<Long> knowledgeIds) {
        if (knowledgeIds.isEmpty()) {
            return;
        }
        // 批量校验，避免每个知识库单独访问数据库。
        List<KnowledgeInfoVo> knowledgeList = knowledgeInfoService.queryByIds(knowledgeIds);
        boolean allEnabled = knowledgeList.size() == knowledgeIds.size()
            && knowledgeList.stream().allMatch(knowledge -> SystemConstants.NORMAL.equals(knowledge.getStatus()));
        if (!allEnabled) {
            throw new ServiceException("所选知识库包含不存在或已停用项");
        }
    }

    /** 校验请求中的模型均存在，避免按模型逐条访问数据库。 */
    private void validateExistingModels(List<Long> modelIds) {
        if (chatModelService.queryByIds(modelIds).size() != modelIds.size()) {
            throw new ServiceException("可选模型包含不存在项");
        }
    }

    /** 校验编码格式、字典值、模型绑定等智能体基础约束，并回写标准化后的值。 */
    private void validateAgent(AgentBo bo) {
        // 编码格式与唯一性校验，编码创建后作为外部调用的稳定标识。
        String code = bo.getAgentCode() == null ? "" : bo.getAgentCode().trim();
        if (!code.matches("[a-z][a-z0-9-]{1,99}")) {
            throw new ServiceException("Agent编码仅支持小写字母、数字和短横线，且必须以字母开头");
        }
        if (baseMapper.existsByAgentCode(code, bo.getId())) {
            throw new ServiceException("Agent编码已存在");
        }

        // 字典值校验并标准化，非法值由字典组件直接拒绝。
        String agentType = businessDict.requireValue(AiDictTypes.AGENT_TYPE, bo.getAgentType());
        String modelPolicy = businessDict.requireValue(
            AiDictTypes.AGENT_MODEL_SELECT_POLICY, bo.getModelSelectPolicy());
        String knowledgePolicy = businessDict.requireValue(
            AiDictTypes.AGENT_KNOWLEDGE_SCOPE_POLICY, bo.getKnowledgeScopePolicy());
        businessDict.requireValue(AiDictTypes.AGENT_STATUS, bo.getStatus());

        // 模型绑定约束：至少绑定一个模型，且默认模型必须在绑定范围内。
        List<Long> modelIds = bo.getModelIds() == null ? List.of(bo.getModelId())
            : bo.getModelIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (modelIds.isEmpty() || !modelIds.contains(bo.getModelId())) {
            throw new ServiceException("请至少绑定一个模型，且默认模型必须在已绑定模型中");
        }
        validateExistingModels(modelIds);
        bo.setModelIds(modelIds);

        // 业务助手必须绑定知识库或工具，否则无法产生有效业务答复。
        if (AiDomainConstants.AGENT_TYPE_BUSINESS.equals(agentType)
            && (bo.getKnowledgeIds() == null || bo.getKnowledgeIds().isEmpty())
            && (bo.getMcpToolIds() == null || bo.getMcpToolIds().isEmpty())) {
            throw new ServiceException("业务助手至少需要绑定一个知识库范围或MCP工具");
        }
        // 执行策略允许为空，但配置时必须是合法的 JSON 对象。
        if (StringUtils.hasText(bo.getExecutionPolicy()) && !JsonUtils.isJsonObject(bo.getExecutionPolicy())) {
            throw new ServiceException("执行策略必须是JSON对象");
        }

        // 回写标准化后的值，保证后续实体转换使用字典标准值。
        bo.setAgentCode(code);
        bo.setAgentType(agentType);
        bo.setModelSelectPolicy(modelPolicy);
        bo.setKnowledgeScopePolicy(knowledgePolicy);
    }

    /** 校验授权项的字典值、主体存在性、时间窗及组合唯一性。 */
    private void validateAclItems(List<AgentAclItemBo> items) {
        // 预加载角色与部门标识集合，用于授权主体存在性校验。
        Set<String> uniqueKeys = new HashSet<>();
        Set<Long> roleIds = roleService.selectRoleList(new SysRoleBo()).stream()
            .map(SysRoleVo::getRoleId).collect(Collectors.toSet());
        Set<Long> deptIds = deptService.selectDeptList(new SysDeptBo()).stream()
            .map(SysDeptVo::getDeptId).collect(Collectors.toSet());
        // 逐项校验授权，同一主体、同一权限的组合只允许配置一次。
        for (AgentAclItemBo item : items) {
            String subjectType = item.getSubjectType() == null
                ? "" : item.getSubjectType().trim().toUpperCase(Locale.ROOT);
            String permission = businessDict.requireValue(AiDictTypes.AGENT_ACL_PERMISSION, item.getPermission());
            if (!StringUtils.hasText(subjectType)) {
                throw new ServiceException("授权主体类型不能为空");
            }
            subjectType = businessDict.requireValue(AiDictTypes.AGENT_ACL_SUBJECT_TYPE, subjectType);
            if (item.getEffectiveTime() != null && item.getExpireTime() != null
                && !item.getExpireTime().after(item.getEffectiveTime())) {
                throw new ServiceException("失效时间必须晚于生效时间");
            }
            if (!Set.of(SystemConstants.NORMAL, SystemConstants.DISABLE)
                .contains(defaultValue(item.getStatus(), SystemConstants.NORMAL))) {
                throw new ServiceException("Agent授权状态不正确");
            }
            if (!uniqueKeys.add(subjectType + ':' + item.getSubjectId() + ':' + permission)) {
                throw new ServiceException("不能重复配置相同的Agent授权主体和权限");
            }
            if (AiDomainConstants.SUBJECT_ROLE.equals(subjectType)
                && !roleIds.contains(item.getSubjectId())) {
                throw new ServiceException("授权角色不存在或已删除");
            }
            if (AiDomainConstants.SUBJECT_DEPT.equals(subjectType)
                && !deptIds.contains(item.getSubjectId())) {
                throw new ServiceException("授权部门不存在或已删除");
            }
        }
    }

    /** 汇总用户可访问的智能体标识，匹配其启用角色与部门链上的有效授权。 */
    private Set<Long> queryAccessibleAgentIds(Long userId) {
        // 加载用户基础信息，缺失用户不授予任何智能体访问范围。
        SysUserVo user = userService.selectUserById(userId);
        if (user == null) {
            return Collections.emptySet();
        }

        // 汇总启用角色及所属部门链，用于匹配角色和部门授权主体。
        Set<Long> roleIds = roleService.selectRolesByUserId(userId).stream()
            .filter(role -> SystemConstants.NORMAL.equals(role.getStatus()))
            .map(SysRoleVo::getRoleId).collect(Collectors.toSet());
        Set<Long> deptIds = new HashSet<>();
        if (user.getDeptId() != null) {
            deptIds.add(user.getDeptId());
            deptService.selectDeptList(new SysDeptBo()).stream()
                .filter(dept -> user.getDeptId().equals(dept.getDeptId()))
                .findFirst().map(SysDeptVo::getAncestors).ifPresent(ancestors -> {
                    for (String ancestor : ancestors.split(",")) {
                        if (StringUtils.hasText(ancestor)) {
                            deptIds.add(Long.valueOf(ancestor));
                        }
                    }
                });
        }

        // 主体和时间窗过滤由 Mapper 在数据库完成，避免全量加载 ACL 后再在内存筛选。
        Date now = new Date();
        if (roleIds.isEmpty() && deptIds.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(agentAclMapper.selectEffectiveAgentIds(SystemConstants.NORMAL, now, roleIds, deptIds,
            AiDomainConstants.SUBJECT_ROLE, AiDomainConstants.SUBJECT_DEPT));
    }

    /** 判断智能体在当前用户的有效模型范围内是否可选。 */
    private boolean isModelSelectable(Long agentId, Long defaultModelId,
                                      String modelPolicy, Set<Long> effectiveModelIds) {
        if (AiDomainConstants.AGENT_MODEL_SELECT_POLICY_FIXED.equals(modelPolicy)) {
            return effectiveModelIds.contains(defaultModelId);
        }

        // 用户可选策略优先使用已启用的绑定模型，未配置绑定时回退到默认模型。
        List<Long> modelIds = agentModelMapper.selectEnabledModelIdsByAgentId(agentId, SystemConstants.NORMAL);
        if (modelIds.isEmpty()) {
            modelIds = List.of(defaultModelId);
        }
        return modelIds.stream().anyMatch(effectiveModelIds::contains);
    }

    /** 转换授权记录为视图对象，主体名称缺失时回退为主体标识。 */
    private AgentAclVo toAclVo(AgentAcl entity, String subjectName) {
        AgentAclVo vo = new AgentAclVo();
        vo.setId(entity.getId());
        vo.setAgentId(entity.getAgentId());
        vo.setSubjectType(entity.getSubjectType());
        vo.setSubjectId(entity.getSubjectId());
        vo.setSubjectName(subjectName == null ? String.valueOf(entity.getSubjectId()) : subjectName);
        vo.setPermission(entity.getPermission());
        vo.setEffectiveTime(entity.getEffectiveTime());
        vo.setExpireTime(entity.getExpireTime());
        vo.setStatus(entity.getStatus());
        vo.setRemark(entity.getRemark());
        return vo;
    }

    /** 根据授权主体类型解析角色或部门名称。 */
    private String resolveSubjectName(AgentAcl acl, Map<Long, String> roleNames, Map<Long, String> deptNames) {
        return AiDomainConstants.SUBJECT_ROLE.equals(acl.getSubjectType())
            ? roleNames.get(acl.getSubjectId()) : deptNames.get(acl.getSubjectId());
    }

    /** 解析历史 JSON 数组为长整型列表，空值返回空列表。 */
    private List<Long> parseLongArray(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        List<Long> values = JsonUtils.parseArray(json, Long.class);
        return values == null ? new ArrayList<>() : values;
    }

    /** 解析历史 JSON 数组为字符串列表，空值返回空列表。 */
    private List<String> parseStringArray(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        List<String> values = JsonUtils.parseArray(json, String.class);
        return values == null ? new ArrayList<>() : values;
    }

    /** 确认智能体存在，不存在时抛出业务异常。 */
    private void requireAgent(Long id) {
        if (baseMapper.selectById(id) == null) {
            throw new ServiceException("Agent 不存在");
        }
    }

    /** 原值为空白时返回默认值，用于授权状态的容错处理。 */
    private String defaultValue(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

}
