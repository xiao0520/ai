package org.ruoyi.domain.bo.mcp;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

/**
 * MCP 工具目录查询参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class McpToolBo extends BaseEntity {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 服务ID
     */
    private Long serverId;
    /**
     * 工具名称
     */
    private String toolName;
    /**
     * 限定名称
     */
    private String qualifiedName;
    /**
     * 风险级别
     */
    private String riskLevel;
    /**
     * 管理员是否启用
     */
    private String adminEnabled;
    /**
     * 发现状态
     */
    private String discoveryStatus;
}
