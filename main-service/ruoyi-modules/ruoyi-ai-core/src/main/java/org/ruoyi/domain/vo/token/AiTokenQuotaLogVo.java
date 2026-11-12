package org.ruoyi.domain.vo.token;

import lombok.Data;
import org.ruoyi.common.translation.annotation.Translation;
import org.ruoyi.common.translation.constant.TransConstant;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * Token 额度调整流水视图。
 */
@Data
public class AiTokenQuotaLogVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    private Long id;
    /**
     * 账户ID
     */
    private Long accountId;
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
     * 用户昵称
     */
    private String nickName;

    /**
     * 部门名称
     */
    @Translation(type = TransConstant.DEPT_ID_TO_NAME, mapper = "deptId")
    private String deptName;

    /**
     * 操作类型
     */
    private String operationType;
    /**
     * 变动令牌数
     */
    private Long changeTokens;
    /**
     * 变动前总额
     */
    private Long beforeTotal;
    /**
     * 变动后总额
     */
    private Long afterTotal;
    /**
     * 变动前剩余额
     */
    private Long beforeRemaining;
    /**
     * 变动后剩余额
     */
    private Long afterRemaining;
    /**
     * 操作员ID
     */
    private Long operatorId;
    /**
     * 操作员名称
     */
    private String operatorName;
    /**
     * 理由
     */
    private String reason;
    /**
     * 创建时间
     */
    private Date createTime;
}
