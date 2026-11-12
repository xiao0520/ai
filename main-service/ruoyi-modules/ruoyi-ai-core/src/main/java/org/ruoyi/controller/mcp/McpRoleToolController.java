package org.ruoyi.controller.mcp;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.log.annotation.Log;
import org.ruoyi.common.log.enums.BusinessType;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.mcp.McpRoleToolSaveBo;
import org.ruoyi.domain.vo.mcp.McpRoleToolPermissionVo;
import org.ruoyi.domain.vo.mcp.McpToolPermissionOptionVo;
import org.ruoyi.service.mcp.IMcpRoleToolPermissionService;
import org.ruoyi.system.domain.bo.SysRoleBo;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * MCP角色工具权限管理Controller
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/mcp/role-tool")
public class McpRoleToolController {

    private final IMcpRoleToolPermissionService permissionService;

    /**
     * 分页查询角色工具权限列表
     */
    @SaCheckPermission("mcp:role-tool:list")
    @GetMapping("/roles")
    public TableDataInfo<McpRoleToolPermissionVo> roles(SysRoleBo query, PageQuery pageQuery) {
        return permissionService.queryRolePage(query, pageQuery);
    }

    /**
     * 根据角色ID查询角色工具权限
     */
    @SaCheckPermission("mcp:role-tool:query")
    @GetMapping("/roles/{roleId}/tools")
    public R<McpRoleToolPermissionVo> roleTools(@PathVariable Long roleId) {
        return R.ok(permissionService.queryRoleTools(roleId));
    }

    /**
     * 保存角色工具权限配置
     */
    @SaCheckPermission("mcp:role-tool:assign")
    @Log(title = "角色MCP工具权限", businessType = BusinessType.GRANT)
    @PutMapping("/roles/{roleId}/tools")
    public R<Void> saveRoleTools(@PathVariable Long roleId,
                                 @Valid @RequestBody McpRoleToolSaveBo command) {
        return permissionService.saveRoleTools(roleId, command) ? R.ok() : R.fail();
    }

    /**
     * 查询MCP工具权限选项列表
     */
    @SaCheckPermission(value = {"mcp:role-tool:list", "mcp:role-tool:assign"}, mode = SaMode.OR)
    @GetMapping("/tools/options")
    public R<List<McpToolPermissionOptionVo>> toolOptions() {
        return R.ok(permissionService.queryToolOptions());
    }

    /**
     * 查询用户生效的工具权限列表
     */
    @SaCheckPermission(value = {"mcp:role-tool:query", "agent:agent:list"}, mode = SaMode.OR)
    @GetMapping("/users/{userId}/effective-tools")
    public R<List<McpToolPermissionOptionVo>> effectiveTools(@PathVariable Long userId,
                                                              @RequestParam(required = false) Long agentId) {
        return R.ok(permissionService.queryEffectiveTools(userId, agentId));
    }
}
