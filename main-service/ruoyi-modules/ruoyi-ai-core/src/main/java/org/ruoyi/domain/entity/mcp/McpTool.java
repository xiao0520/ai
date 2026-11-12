package org.ruoyi.domain.entity.mcp;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.util.Date;

/**
 * MCP Server 发现或导入的工具目录记录。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mcp_tool_info")
public class McpTool extends BaseEntity {

    /**
     * 主键ID
     */
    @TableId("id")
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
     * 显示名称
     */
    private String displayName;
    /**
     * 描述
     */
    private String description;
    /**
     * 输入模式
     */
    private String inputSchema;
    /**
     * 输出模式
     */
    private String outputSchema;
    /**
     * 注解JSON
     */
    private String annotationsJson;
    /**
     * 元数据JSON
     */
    private String metaJson;
    /**
     * 模式摘要
     */
    private String schemaDigest;
    /**
     * 来源类型
     */
    private String sourceType;
    /**
     * 发现状态
     */
    private String discoveryStatus;
    /**
     * 管理员是否启用
     */
    private String adminEnabled;
    /**
     * 是否允许无 Agent 的普通聊天使用
     */
    private String generalChatEnabled;
    /**
     * 风险级别
     */
    private String riskLevel;
    /**
     * 是否需要确认
     */
    private String requireConfirmation;
    /**
     * 超时秒数
     */
    private Integer timeoutSeconds;
    /**
     * 可见结果字段
     */
    private String visibleResultFields;
    /**
     * 最后发现时间
     */
    private Date lastDiscoveredAt;
}
