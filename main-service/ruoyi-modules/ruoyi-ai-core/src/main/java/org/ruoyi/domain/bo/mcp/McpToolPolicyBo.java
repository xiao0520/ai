package org.ruoyi.domain.bo.mcp;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端可修改的 MCP 工具治理策略。
 */
@Data
public class McpToolPolicyBo {

    /**
     * 主键ID
     */
    @NotNull(message = "工具ID不能为空")
    private Long id;

    /**
     * 显示名称
     */
    @NotBlank(message = "显示名称不能为空")
    @Size(max = 200, message = "显示名称不能超过{max}个字符")
    private String displayName;

    /**
     * 风险级别
     */
    @NotBlank(message = "风险等级不能为空")
    private String riskLevel;

    /**
     * 是否需要确认
     */
    @NotBlank(message = "调用确认配置不能为空")
    private String requireConfirmation;

    /**
     * 超时秒数
     */
    @Min(value = 1, message = "调用超时不能小于1秒")
    @Max(value = 300, message = "调用超时不能超过300秒")
    @NotNull(message = "调用超时不能为空")
    private Integer timeoutSeconds;

    /**
     * 管理员是否启用
     */
    @NotBlank(message = "管理状态不能为空")
    private String adminEnabled;

    /**
     * 是否允许无 Agent 的普通聊天使用；旧调用未传时保留原设置
     */
    private String generalChatEnabled;

    /**
     * 可见结果字段
     */
    private String visibleResultFields;
}
