package org.ruoyi.domain.bo.token;

import lombok.Data;

import java.util.Date;

/**
 * Token 额度调整流水查询条件。
 */
@Data
public class AiTokenQuotaLogQueryBo {

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
     * 操作类型
     */
    private String operationType;
    /**
     * 操作员ID
     */
    private Long operatorId;
    /**
     * 开始时间
     */
    private Date beginTime;
    /**
     * 结束时间
     */
    private Date endTime;
}
