package org.ruoyi.domain.vo.mcp;

import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 角色及其 MCP 工具权限。
 */
@Data
public class McpRoleToolPermissionVo implements Serializable {
    /**
     * 角色ID
     */
    private Long roleId;
    /**
     * 角色名称
     */
    private String roleName;
    /**
     * 角色权限字符串
     */
    private String roleKey;
    /**
     * 角色排序
     */
    private Integer roleSort;
    /**
     * 角色状态
     */
    private String roleStatus;
    /**
     * 工具ID列表
     */
    private List<Long> toolIds = Collections.emptyList();
    /**
     * 工具列表
     */
    private List<McpToolPermissionOptionVo> tools = Collections.emptyList();
    /**
     * 生效时间
     */
    private Date effectiveTime;
    /**
     * 过期时间
     */
    private Date expireTime;
    /**
     * 备注
     */
    private String remark;
}
