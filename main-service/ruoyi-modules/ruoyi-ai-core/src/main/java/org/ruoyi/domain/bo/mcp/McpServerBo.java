package org.ruoyi.domain.bo.mcp;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;
import org.ruoyi.domain.entity.mcp.McpServer;

/**
 * MCP Server 新增、修改和查询参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = McpServer.class, reverseConvertGenerate = false)
public class McpServerBo extends BaseEntity {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 服务编码
     */
    @NotBlank(message = "Server编码不能为空")
    @Pattern(regexp = "^[a-z][a-z0-9-]{1,99}$", message = "Server编码只能包含小写字母、数字和短横线，且必须以字母开头")
    private String serverCode;

    /**
     * 服务名称
     */
    @NotBlank(message = "Server名称不能为空")
    @Size(max = 200, message = "Server名称不能超过{max}个字符")
    private String serverName;

    /**
     * 服务类型
     */
    @NotBlank(message = "Server类型不能为空")
    private String serverType;

    /**
     * 传输类型
     */
    @NotBlank(message = "传输方式不能为空")
    private String transportType;

    /**
     * 端点地址
     */
    @Size(max = 1000, message = "服务地址不能超过{max}个字符")
    private String endpoint;

    /**
     * 运行时键
     */
    @Size(max = 100, message = "运行时键不能超过{max}个字符")
    private String runtimeKey;

    /**
     * 协议版本
     */
    @NotBlank(message = "协议版本不能为空")
    private String protocolVersion;

    /**
     * 认证类型
     */
    @NotBlank(message = "认证方式不能为空")
    private String authType;

    /**
     * 凭证键
     */
    @Size(max = 100, message = "凭证别名不能超过{max}个字符")
    private String credentialKey;

    /**
     * 连接超时时间
     */
    @Min(value = 1, message = "连接超时不能小于1秒")
    @Max(value = 300, message = "连接超时不能超过300秒")
    @NotNull(message = "连接超时不能为空")
    private Integer connectTimeout;

    /**
     * 默认风险级别
     */
    @NotBlank(message = "默认风险等级不能为空")
    private String defaultRiskLevel;

    /**
     * 状态
     */
    private String status;
    /**
     * Runtime状态
     */
    private String runtimeStatus;

    /**
     * 备注
     */
    @Size(max = 500, message = "备注不能超过{max}个字符")
    private String remark;
}
