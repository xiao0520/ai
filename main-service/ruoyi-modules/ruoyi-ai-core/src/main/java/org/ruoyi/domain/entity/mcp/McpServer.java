package org.ruoyi.domain.entity.mcp;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.util.Date;

/**
 * MCP Server 控制面配置。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mcp_server_info")
public class McpServer extends BaseEntity {

    /**
     * 主键ID
     */
    @TableId("id")
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
     * 服务类型
     */
    private String serverType;
    /**
     * 传输类型
     */
    private String transportType;
    /**
     * 端点地址
     */
    private String endpoint;
    /**
     * 运行时键
     */
    private String runtimeKey;
    /**
     * 协议版本
     */
    private String protocolVersion;
    /**
     * 认证类型
     */
    private String authType;
    /**
     * 凭证键
     */
    private String credentialKey;
    /**
     * 连接超时时间
     */
    private Integer connectTimeout;
    /**
     * 默认风险级别
     */
    private String defaultRiskLevel;
    /**
     * 状态
     */
    private String status;
    /**
     * 运行时状态
     */
    private String runtimeStatus;
    /**
     * 运行时消息
     */
    private String runtimeMessage;
    /**
     * 最后发现时间
     */
    private Date lastDiscoveredAt;
    /**
     * 备注
     */
    private String remark;
}
