package org.ruoyi.service.mcp.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.json.utils.JsonUtils;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.constant.AiDictTypes;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.constant.AiSystemConfigKeys;
import org.ruoyi.domain.bo.mcp.McpToolBo;
import org.ruoyi.domain.bo.mcp.McpToolPolicyBo;
import org.ruoyi.domain.entity.mcp.McpServer;
import org.ruoyi.domain.entity.mcp.McpTool;
import org.ruoyi.domain.vo.mcp.McpToolOptionVo;
import org.ruoyi.domain.vo.mcp.McpToolVo;
import org.ruoyi.mapper.mcp.McpRoleToolPermissionMapper;
import org.ruoyi.mapper.mcp.McpServerMapper;
import org.ruoyi.mapper.mcp.McpToolMapper;
import org.ruoyi.service.mcp.IMcpToolService;
import org.ruoyi.service.agent.IAgentMcpToolReferenceService;
import org.ruoyi.support.AiBusinessDict;
import org.ruoyi.support.AiRuntimeConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MCP工具管理Service实现类
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Service
@RequiredArgsConstructor
public class McpToolServiceImpl implements IMcpToolService {

    private final McpToolMapper baseMapper;
    private final McpServerMapper serverMapper;
    private final McpRoleToolPermissionMapper roleToolMapper;
    private final IAgentMcpToolReferenceService agentToolReferenceService;
    private final AiBusinessDict businessDict;
    private final AiRuntimeConfig runtimeConfig;

    @Override
    public TableDataInfo<McpToolVo> queryPage(McpToolBo bo, PageQuery pageQuery) {
        // 筛选与排序规则交给 Mapper XML，避免服务层混入持久化表达式。
        Page<McpTool> page = pageQuery.build();
        page.setRecords(baseMapper.selectToolList(page, bo));

        // 当前页工具统一批量加载所属服务，避免逐行查询 Server 信息。
        Map<Long, McpServer> servers = mapServers(page.getRecords());
        List<McpToolVo> rows = page.getRecords().stream()
            .map(tool -> toVo(tool, servers.get(tool.getServerId()))).toList();
        return new TableDataInfo<>(rows, page.getTotal());
    }

    @Override
    public McpToolVo queryById(Long id) {
        McpTool tool = requireTool(id);
        return toVo(tool, serverMapper.selectById(tool.getServerId()));
    }

    @Override
    public List<McpToolVo> queryByIds(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }
        List<McpTool> tools = baseMapper.selectByIds(ids);
        Map<Long, McpServer> servers = mapServers(tools);
        return tools.stream().map(tool -> toVo(tool, servers.get(tool.getServerId()))).toList();
    }

    @Override
    public List<McpToolOptionVo> queryEnabledOptions() {
        // 工具自身状态由 Mapper 筛选，所属服务状态仍需与批量加载结果联合校验。
        List<McpTool> tools = baseMapper.selectEnabledDiscoveredTools(AiDomainConstants.FLAG_ENABLED,
            AiDomainConstants.MCP_TOOL_DISCOVERY_STATUS_REMOVED);
        Map<Long, McpServer> servers = mapServers(tools);
        return tools.stream()
            .filter(tool -> {
                McpServer server = servers.get(tool.getServerId());
                return server != null && AiDomainConstants.MCP_STATUS_ENABLED.equals(server.getStatus());
            })
            .map(tool -> toOption(tool, servers.get(tool.getServerId())))
            .toList();
    }

    @Override
    public List<McpToolOptionVo> queryEnabledOptionsByIds(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }

        // 先限定工具自身可执行状态，再与所属 Server 的启用状态联合过滤。
        List<Long> toolIds = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (toolIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<McpTool> tools = baseMapper.selectEnabledDiscoveredByIds(toolIds,
            AiDomainConstants.FLAG_ENABLED, AiDomainConstants.MCP_TOOL_DISCOVERY_STATUS_REMOVED);
        Map<Long, McpServer> servers = mapServers(tools);
        return tools.stream()
            .filter(tool -> {
                McpServer server = servers.get(tool.getServerId());
                return server != null && AiDomainConstants.MCP_STATUS_ENABLED.equals(server.getStatus());
            })
            .map(tool -> toOption(tool, servers.get(tool.getServerId())))
            .toList();
    }

    @Override
    public void validateSelectableTools(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        List<Long> toolIds = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (toolIds.isEmpty()) {
            return;
        }

        // 同时校验工具自身状态及所属 Server，避免 Agent 绑定不可执行的工具。
        List<McpTool> tools = baseMapper.selectEnabledDiscoveredByIds(toolIds,
            AiDomainConstants.FLAG_ENABLED, AiDomainConstants.MCP_TOOL_DISCOVERY_STATUS_REMOVED);
        if (tools.size() != toolIds.size()) {
            throw new ServiceException("关联工具包含不存在、已移除或已停用的工具");
        }
        Map<Long, McpServer> servers = mapServers(tools);
        boolean allServersEnabled = tools.stream().allMatch(tool -> {
            McpServer server = servers.get(tool.getServerId());
            return server != null && AiDomainConstants.MCP_STATUS_ENABLED.equals(server.getStatus());
        });
        if (!allServersEnabled) {
            throw new ServiceException("关联工具包含已停用MCP Server下的工具");
        }
    }

    @Override
    public boolean updatePolicy(McpToolPolicyBo bo) {
        // 字典校验先于更新，确保风险、确认策略和启停状态不会被任意字符串污染。
        McpTool current = requireTool(bo.getId());
        String riskLevel = businessDict.requireSpecifiedValue(AiDictTypes.MCP_RISK_LEVEL, bo.getRiskLevel());
        String requireConfirmation = businessDict.requireSpecifiedValue(
            AiDictTypes.MCP_CONFIRMATION, bo.getRequireConfirmation());
        String adminEnabled = businessDict.requireSpecifiedValue(AiDictTypes.MCP_TOOL_STATUS, bo.getAdminEnabled());
        String generalChatEnabled = StringUtils.hasText(bo.getGeneralChatEnabled())
            ? businessDict.requireSpecifiedValue(AiDictTypes.FLAG_YES_NO, bo.getGeneralChatEnabled())
            : current.getGeneralChatEnabled();
        validateVisibleResultFields(bo.getVisibleResultFields());
        McpTool update = new McpTool();
        update.setId(current.getId());
        update.setDisplayName(bo.getDisplayName());
        update.setRiskLevel(riskLevel);
        update.setRequireConfirmation(requiresConfirmation(riskLevel)
            ? AiDomainConstants.FLAG_ENABLED : requireConfirmation);
        update.setTimeoutSeconds(bo.getTimeoutSeconds());
        update.setAdminEnabled(adminEnabled);
        update.setGeneralChatEnabled(generalChatEnabled);
        update.setVisibleResultFields(StringUtils.hasText(bo.getVisibleResultFields())
            ? bo.getVisibleResultFields() : AiDomainConstants.EMPTY_JSON_ARRAY);
        if (AiDomainConstants.FLAG_ENABLED.equals(update.getAdminEnabled())
            && AiDomainConstants.MCP_TOOL_DISCOVERY_STATUS_REMOVED.equals(current.getDiscoveryStatus())) {
            throw new ServiceException("已从Server移除的工具不能启用");
        }
        // 被 Server 移除的工具只能保留历史记录，不能重新启用执行。
        return baseMapper.updateById(update) > 0;
    }

    @Override
    public boolean updateStatus(Long id, String adminEnabled) {
        // 启用前检查发现状态，防止管理端把已移除工具恢复为可调用状态。
        McpTool current = requireTool(id);
        String normalizedStatus = businessDict.requireSpecifiedValue(AiDictTypes.MCP_TOOL_STATUS, adminEnabled);
        if (AiDomainConstants.FLAG_ENABLED.equals(normalizedStatus)
            && AiDomainConstants.MCP_TOOL_DISCOVERY_STATUS_REMOVED.equals(current.getDiscoveryStatus())) {
            throw new ServiceException("已从Server移除的工具不能启用");
        }
        McpTool update = new McpTool();
        update.setId(id);
        update.setAdminEnabled(normalizedStatus);
        return baseMapper.updateById(update) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteRemoved(Collection<Long> ids) {
        // 先规范化删除主键并一次加载全部目标，避免循环访问数据库。
        List<Long> toolIds = ids == null ? Collections.emptyList()
            : ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (toolIds.isEmpty()) {
            throw new ServiceException("请选择需要清理的工具");
        }
        List<McpTool> tools = baseMapper.selectByIds(toolIds);
        if (tools.size() != toolIds.size()) {
            throw new ServiceException("所选工具包含不存在或已删除的数据");
        }
        for (McpTool tool : tools) {
            if (!AiDomainConstants.MCP_TOOL_DISCOVERY_STATUS_REMOVED.equals(tool.getDiscoveryStatus())
                || !AiDomainConstants.FLAG_DISABLED.equals(tool.getAdminEnabled())) {
                throw new ServiceException("只能清理已移除且已停用的工具：" + tool.getQualifiedName());
            }
        }

        // 角色授权和 Agent 绑定均为空时才能物理清理已移除工具。
        long roleBindings = roleToolMapper.countByToolIds(toolIds);
        long agentBindings = agentToolReferenceService.countBindings(toolIds);
        if (roleBindings > 0 || agentBindings > 0) {
            throw new ServiceException("所选工具仍被角色或 Agent 关联，请先解除关联");
        }
        return baseMapper.deleteByIds(toolIds) > 0;
    }

    /** 校验模型可见字段配置必须是合法的 JSON 数组。 */
    private void validateVisibleResultFields(String visibleResultFields) {
        if (StringUtils.hasText(visibleResultFields) && !JsonUtils.isJsonArray(visibleResultFields)) {
            throw new ServiceException("模型可见字段必须是JSON数组");
        }
    }

    /** 高风险工具由系统参数统一强制开启二次确认。 */
    private boolean requiresConfirmation(String riskLevel) {
        return runtimeConfig.getAllowedValuesIgnoreCase(AiSystemConfigKeys.MCP_CONFIRMATION_RISK_LEVELS,
            businessDict.values(AiDictTypes.MCP_RISK_LEVEL)).contains(riskLevel);
    }

    /** 批量加载工具所属 Server，避免逐条查询产生 N+1。 */
    private Map<Long, McpServer> mapServers(List<McpTool> tools) {
        if (CollUtil.isEmpty(tools)) {
            return Collections.emptyMap();
        }
        List<Long> serverIds = tools.stream().map(McpTool::getServerId).distinct().toList();
        return serverMapper.selectByIds(serverIds).stream().collect(Collectors.toMap(
            McpServer::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    /** 转换工具管理视图，Server 信息由调用方批量加载后传入。 */
    private McpToolVo toVo(McpTool tool, McpServer server) {
        McpToolVo vo = new McpToolVo();
        vo.setId(tool.getId());
        vo.setServerId(tool.getServerId());
        if (server != null) {
            vo.setServerCode(server.getServerCode());
            vo.setServerName(server.getServerName());
        }
        vo.setToolName(tool.getToolName());
        vo.setQualifiedName(tool.getQualifiedName());
        vo.setDisplayName(tool.getDisplayName());
        vo.setDescription(tool.getDescription());
        vo.setInputSchema(tool.getInputSchema());
        vo.setOutputSchema(tool.getOutputSchema());
        vo.setAnnotationsJson(tool.getAnnotationsJson());
        vo.setMetaJson(tool.getMetaJson());
        vo.setSchemaDigest(tool.getSchemaDigest());
        vo.setSourceType(tool.getSourceType());
        vo.setDiscoveryStatus(tool.getDiscoveryStatus());
        vo.setAdminEnabled(tool.getAdminEnabled());
        vo.setGeneralChatEnabled(tool.getGeneralChatEnabled());
        vo.setRiskLevel(tool.getRiskLevel());
        vo.setRequireConfirmation(tool.getRequireConfirmation());
        vo.setTimeoutSeconds(tool.getTimeoutSeconds());
        vo.setVisibleResultFields(tool.getVisibleResultFields());
        vo.setLastDiscoveredAt(tool.getLastDiscoveredAt());
        vo.setCreateTime(tool.getCreateTime());
        vo.setUpdateTime(tool.getUpdateTime());
        return vo;
    }

    /** 转换工具选项视图，展示标签由工具名与所属 Server 名拼接。 */
    private McpToolOptionVo toOption(McpTool tool, McpServer server) {
        McpToolOptionVo vo = new McpToolOptionVo();
        vo.setId(tool.getId());
        vo.setServerCode(server.getServerCode());
        vo.setServerName(server.getServerName());
        vo.setToolName(tool.getToolName());
        vo.setQualifiedName(tool.getQualifiedName());
        vo.setDisplayName(tool.getDisplayName());
        vo.setRiskLevel(tool.getRiskLevel());
        vo.setLabel(tool.getDisplayName() + "（" + server.getServerName() + "）");
        return vo;
    }

    /** 查询工具并在不存在时抛出业务异常。 */
    private McpTool requireTool(Long id) {
        McpTool tool = baseMapper.selectById(id);
        if (tool == null) {
            throw new ServiceException("MCP工具不存在或已删除");
        }
        return tool;
    }
}
