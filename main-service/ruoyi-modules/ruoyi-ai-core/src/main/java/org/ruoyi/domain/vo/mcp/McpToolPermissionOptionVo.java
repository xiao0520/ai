package org.ruoyi.domain.vo.mcp;

import lombok.Data;

import java.io.Serializable;

/**
 * 角色授权使用的 MCP 工具选项。
 */
@Data
public class McpToolPermissionOptionVo implements Serializable {
    /**
     * 工具ID
     */
    private Long toolId;
    /**
     * 限定名称
     */
    private String qualifiedName;
    /**
     * 显示名称
     */
    private String displayName;
    /**
     * 服务名称
     */
    private String serverName;
    /**
     * 风险级别
     */
    private String riskLevel;
}
