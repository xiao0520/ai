package org.ruoyi.service.mcp;

import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.mcp.McpRoleToolSaveBo;
import org.ruoyi.domain.vo.mcp.McpRoleToolPermissionVo;
import org.ruoyi.domain.vo.mcp.McpToolPermissionOptionVo;
import org.ruoyi.system.domain.bo.SysRoleBo;

import java.util.List;

/**
 * MCP角色工具权限管理Service接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface IMcpRoleToolPermissionService {

    /**
     * 分页查询角色列表（带工具权限信息）
     *
     * @param query     查询条件
     * @param pageQuery 分页参数
     * @return 角色工具权限分页列表
     */
    TableDataInfo<McpRoleToolPermissionVo> queryRolePage(SysRoleBo query, PageQuery pageQuery);

    /**
     * 查询MCP工具权限选项列表
     *
     * @return 工具权限选项列表
     */
    List<McpToolPermissionOptionVo> queryToolOptions();

    /**
     * 根据角色ID查询角色工具权限
     *
     * @param roleId 角色ID
     * @return 角色工具权限
     */
    McpRoleToolPermissionVo queryRoleTools(Long roleId);

    /**
     * 保存角色工具权限配置
     *
     * @param roleId  角色ID
     * @param command 保存参数
     * @return 是否保存成功
     */
    boolean saveRoleTools(Long roleId, McpRoleToolSaveBo command);

    /**
     * 查询用户生效的工具权限列表
     *
     * @param userId  用户ID
     * @param agentId 智能体ID
     * @return 生效的工具权限列表
     */
    List<McpToolPermissionOptionVo> queryEffectiveTools(Long userId, Long agentId);
}
