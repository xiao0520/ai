package org.ruoyi.domain.entity.mcp;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.util.Date;

/**
 * 角色 MCP 工具权限。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mcp_role_tool_permission")
public class McpRoleToolPermission extends BaseEntity {
    /**
     * 主键ID
     */
    @TableId("id")
    private Long id;
    /**
     * 角色ID
     */
    private Long roleId;
    /**
     * 工具ID
     */
    private Long toolId;
    /**
     * 状态
     */
    private String status;
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
