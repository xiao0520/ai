package org.ruoyi.service.mcp.impl;

import cn.hutool.core.collection.CollUtil;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.mcp.McpRoleToolSaveBo;
import org.ruoyi.domain.entity.mcp.McpRoleToolPermission;
import org.ruoyi.domain.vo.mcp.McpRoleToolPermissionVo;
import org.ruoyi.domain.vo.mcp.McpToolOptionVo;
import org.ruoyi.domain.vo.mcp.McpToolPermissionOptionVo;
import org.ruoyi.mapper.mcp.McpRoleToolPermissionMapper;
import org.ruoyi.service.mcp.IMcpRoleToolPermissionService;
import org.ruoyi.service.mcp.IMcpToolService;
import org.ruoyi.service.agent.IAgentMcpToolReferenceService;
import org.ruoyi.system.domain.bo.SysRoleBo;
import org.ruoyi.system.domain.vo.SysRoleVo;
import org.ruoyi.system.service.ISysRoleService;
import org.ruoyi.system.service.ISysUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP角色工具权限管理Service实现类
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Service
@RequiredArgsConstructor
public class McpRoleToolPermissionServiceImpl implements IMcpRoleToolPermissionService {

    private final ISysRoleService roleService;
    private final ISysUserService userService;
    private final McpRoleToolPermissionMapper permissionMapper;
    private final IAgentMcpToolReferenceService agentToolReferenceService;
    private final IMcpToolService mcpToolService;

    @Override
    public TableDataInfo<McpRoleToolPermissionVo> queryRolePage(SysRoleBo query, PageQuery pageQuery) {
        // 角色分页由系统角色服务负责，避免权限模块绕过角色数据范围。
        TableDataInfo<SysRoleVo> rolePage = roleService.selectPageRoleList(query, pageQuery);
        if (CollUtil.isEmpty(rolePage.getRows())) {
            return new TableDataInfo<>(Collections.emptyList(), rolePage.getTotal());
        }
        List<Long> roleIds = rolePage.getRows().stream().map(SysRoleVo::getRoleId).toList();
        // 批量加载当前页授权关系和工具展示信息，避免逐角色查询。
        Map<Long, List<McpRoleToolPermission>> permissions = permissionMapper.selectByRoleIds(roleIds)
            .stream().collect(Collectors.groupingBy(McpRoleToolPermission::getRoleId,
                LinkedHashMap::new, Collectors.toList()));
        Set<Long> toolIds = permissions.values().stream().flatMap(List::stream)
            .map(McpRoleToolPermission::getToolId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, McpToolPermissionOptionVo> toolMap = mapTools(toolIds, false);
        List<McpRoleToolPermissionVo> rows = rolePage.getRows().stream()
            .map(role -> toRoleVo(role, permissions.getOrDefault(role.getRoleId(), Collections.emptyList()), toolMap))
            .toList();
        return new TableDataInfo<>(rows, rolePage.getTotal());
    }

    @Override
    public List<McpToolPermissionOptionVo> queryToolOptions() {
        // 工具状态及 Server 可执行性由工具领域统一判断。
        return mcpToolService.queryEnabledOptions().stream().map(this::toPermissionOption).toList();
    }

    @Override
    public McpRoleToolPermissionVo queryRoleTools(Long roleId) {
        // 校验角色数据范围与存在性，防止越权查看其他角色的授权。
        roleService.checkRoleDataScope(roleId);
        SysRoleVo role = roleService.selectRoleById(roleId);
        if (role == null) {
            throw new ServiceException("角色不存在或已删除");
        }

        // 加载角色全部授权并回填工具展示信息，历史授权不受工具当前状态影响。
        List<McpRoleToolPermission> permissions = permissionMapper.selectByRoleId(roleId);
        Set<Long> toolIds = permissions.stream().map(McpRoleToolPermission::getToolId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return toRoleVo(role, permissions, mapTools(toolIds, false));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveRoleTools(Long roleId, McpRoleToolSaveBo command) {
        // 校验角色数据范围及输入时间窗，避免越权覆盖其他角色的授权。
        roleService.checkRoleDataScope(roleId);
        roleService.checkRoleAllowed(new SysRoleBo(roleId));
        if (roleService.selectRoleById(roleId) == null) {
            throw new ServiceException("角色不存在或已删除");
        }
        validateTime(command);
        List<Long> toolIds = command.getToolIds() == null ? Collections.emptyList()
            : command.getToolIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        validateTools(toolIds);

        // 使用“全量替换”保存角色工具授权，删除与新增处于同一事务中。
        permissionMapper.deleteByRoleId(roleId);
        if (toolIds.isEmpty()) {
            return true;
        }
        List<McpRoleToolPermission> rows = new ArrayList<>(toolIds.size());
        for (Long toolId : toolIds) {
            McpRoleToolPermission row = new McpRoleToolPermission();
            row.setRoleId(roleId);
            row.setToolId(toolId);
            row.setStatus(SystemConstants.NORMAL);
            row.setEffectiveTime(command.getEffectiveTime());
            row.setExpireTime(command.getExpireTime());
            row.setRemark(command.getRemark());
            rows.add(row);
        }
        return permissionMapper.insertBatch(rows);
    }

    @Override
    public List<McpToolPermissionOptionVo> queryEffectiveTools(Long userId, Long agentId) {
        // 运行时入口先确认用户有效，再按角色聚合其工具授权。
        if (userService.selectUserById(userId) == null) {
            throw new ServiceException("用户不存在或已删除");
        }
        List<SysRoleVo> roles = roleService.selectRolesByUserId(userId);
        Set<Long> roleToolIds;

        // 超级管理员可使用所有当前可执行的 MCP 工具。
        if (roles.stream().anyMatch(SysRoleVo::isAdmin)) {
            roleToolIds = queryToolOptions().stream().map(McpToolPermissionOptionVo::getToolId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        } else {
            // 普通用户只聚合其启用角色在当前生效时间窗内的授权工具。
            List<Long> roleIds = roles.stream().filter(role -> SystemConstants.NORMAL.equals(role.getStatus()))
                .map(SysRoleVo::getRoleId).toList();
            if (roleIds.isEmpty()) {
                return Collections.emptyList();
            }
            Date now = new Date();
            roleToolIds = new LinkedHashSet<>(permissionMapper.selectEffectiveToolIds(
                roleIds, SystemConstants.NORMAL, now));
        }
        if (agentId != null) {
            // Agent 配置工具时，角色权限还需与 Agent 工具白名单取交集。
            Set<Long> agentToolIds = new LinkedHashSet<>(agentToolReferenceService.listEnabledToolIds(agentId));
            roleToolIds.retainAll(agentToolIds);
        }
        // 仅返回仍可执行且所属 Server 已启用的工具展示信息。
        return new ArrayList<>(mapTools(roleToolIds, true).values());
    }

    /** 校验授权时间窗，失效时间必须晚于生效时间。 */
    private void validateTime(McpRoleToolSaveBo command) {
        if (command.getEffectiveTime() != null && command.getExpireTime() != null
            && !command.getExpireTime().after(command.getEffectiveTime())) {
            throw new ServiceException("失效时间必须晚于生效时间");
        }
    }

    /** 校验所选工具均可被绑定，不可执行或已移除的工具直接拒绝。 */
    private void validateTools(List<Long> toolIds) {
        if (toolIds.isEmpty()) {
            return;
        }
        // 工具及其所属 Server 的启用状态由工具领域统一校验。
        mcpToolService.validateSelectableTools(toolIds);
    }

    /** 批量加载工具展示信息，运行时场景仅保留当前可执行工具。 */
    private Map<Long, McpToolPermissionOptionVo> mapTools(Set<Long> toolIds, boolean onlyEnabled) {
        if (toolIds.isEmpty()) {
            return Collections.emptyMap();
        }
        // 角色详情保留历史授权展示；运行时场景仅加载当前可执行工具。
        List<McpToolPermissionOptionVo> options = onlyEnabled
            ? mcpToolService.queryEnabledOptionsByIds(toolIds).stream().map(this::toPermissionOption).toList()
            : mcpToolService.queryByIds(toolIds).stream().map(this::toPermissionOption).toList();
        return options.stream().collect(Collectors.toMap(McpToolPermissionOptionVo::getToolId,
                java.util.function.Function.identity(),
                (left, right) -> left, LinkedHashMap::new));
    }

    /** 将工具选项转换为授权展示对象。 */
    private McpToolPermissionOptionVo toPermissionOption(McpToolOptionVo tool) {
        McpToolPermissionOptionVo vo = new McpToolPermissionOptionVo();
        vo.setToolId(tool.getId());
        vo.setQualifiedName(tool.getQualifiedName());
        vo.setDisplayName(tool.getDisplayName());
        vo.setServerName(tool.getServerName());
        vo.setRiskLevel(tool.getRiskLevel());
        return vo;
    }

    /** 将工具详情转换为授权展示对象，用于历史授权的完整展示。 */
    private McpToolPermissionOptionVo toPermissionOption(org.ruoyi.domain.vo.mcp.McpToolVo tool) {
        McpToolPermissionOptionVo vo = new McpToolPermissionOptionVo();
        vo.setToolId(tool.getId());
        vo.setQualifiedName(tool.getQualifiedName());
        vo.setDisplayName(tool.getDisplayName());
        vo.setServerName(tool.getServerName());
        vo.setRiskLevel(tool.getRiskLevel());
        return vo;
    }

    /** 组装角色授权视图，时间窗与备注取自授权记录的首条数据。 */
    private McpRoleToolPermissionVo toRoleVo(SysRoleVo role, List<McpRoleToolPermission> permissions,
                                              Map<Long, McpToolPermissionOptionVo> toolMap) {
        McpRoleToolPermissionVo vo = new McpRoleToolPermissionVo();
        vo.setRoleId(role.getRoleId());
        vo.setRoleName(role.getRoleName());
        vo.setRoleKey(role.getRoleKey());
        vo.setRoleSort(role.getRoleSort());
        vo.setRoleStatus(role.getStatus());
        vo.setToolIds(permissions.stream().map(McpRoleToolPermission::getToolId).distinct().toList());
        vo.setTools(vo.getToolIds().stream().map(toolMap::get).filter(java.util.Objects::nonNull).toList());
        if (!permissions.isEmpty()) {
            McpRoleToolPermission first = permissions.get(0);
            vo.setEffectiveTime(first.getEffectiveTime());
            vo.setExpireTime(first.getExpireTime());
            vo.setRemark(first.getRemark());
        }
        return vo;
    }
}
