package org.ruoyi.domain.entity.mcp;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

/**
 * Agent 与 MCP 工具关联。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_mcp_tool")
public class AgentMcpTool extends BaseEntity {
    /**
     * 主键ID
     */
    @TableId("id")
    private Long id;
    /**
     * 智能体ID
     */
    private Long agentId;
    /**
     * 工具ID
     */
    private Long toolId;
    /**
     * 状态
     */
    private String status;
}
