package org.ruoyi.domain.vo.mcp;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.ruoyi.domain.entity.mcp.McpServer;

import java.io.Serializable;
import java.util.Date;

/**
 * MCP Server 展示对象。
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = McpServer.class)
public class McpServerVo implements Serializable {

    /**
     * 主键ID
     */
    private Long id;
    /**
     * 服务编码
     */
    @ExcelProperty("Server编码")
    private String serverCode;
    /**
     * 服务名称
     */
    @ExcelProperty("Server名称")
    private String serverName;
    /**
     * 服务类型
     */
    @ExcelProperty("Server类型")
    private String serverType;
    /**
     * 传输类型
     */
    @ExcelProperty("传输方式")
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
    /**
     * 创建时间
     */
    private Date createTime;
    /**
     * 更新时间
     */
    private Date updateTime;
}
