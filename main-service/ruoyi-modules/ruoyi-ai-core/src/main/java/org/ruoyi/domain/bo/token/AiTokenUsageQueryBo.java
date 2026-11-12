package org.ruoyi.domain.bo.token;

import lombok.Data;

import java.util.Date;

/**
 * Token 使用记录查询条件。
 */
@Data
public class AiTokenUsageQueryBo {

    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 部门ID
     */
    private Long deptId;
    /**
     * 用户账号
     */
    private String userName;
    /**
     * 模型ID
     */
    private Long modelId;
    /**
     * 提供者编码
     */
    private String providerCode;
    /**
     * 状态
     */
    private String status;
    /**
     * 使用来源
     */
    private String usageSource;
    /**
     * 开始时间
     */
    private Date beginTime;
    /**
     * 结束时间
     */
    private Date endTime;
}
