package org.ruoyi.domain.vo.mcp;

import lombok.Data;

import java.io.Serializable;

/**
 * Agent 表单使用的已启用 MCP 工具选项。
 */
@Data
public class McpToolOptionVo implements Serializable {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 服务编码
     */
    private String serverCode;
    /**
     * 服务名称
     */
    private String serverName;
    /**
     * 工具名称
     */
    private String toolName;
    /**
     * 限定名称
     */
    private String qualifiedName;
    /**
     * 显示名称
     */
    private String displayName;
    /**
     * 风险级别
     */
    private String riskLevel;
    /**
     * 标签
     */
    private String label;
}
