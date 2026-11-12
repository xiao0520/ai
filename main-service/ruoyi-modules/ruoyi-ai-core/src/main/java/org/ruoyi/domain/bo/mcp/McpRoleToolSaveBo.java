package org.ruoyi.domain.bo.mcp;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 按角色保存 MCP 工具权限。
 */
@Data
public class McpRoleToolSaveBo {
    /**
     * 工具ID列表
     */
    @NotNull(message = "工具ID列表不能为空")
    private List<Long> toolIds;
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
