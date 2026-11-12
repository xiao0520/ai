package org.ruoyi.domain.vo.token;

import lombok.Data;
import org.ruoyi.common.translation.annotation.Translation;
import org.ruoyi.common.translation.constant.TransConstant;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 用户 Token 额度账户视图。
 */
@Data
public class AiUserTokenAccountVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    private Long id;
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
     * 周期类型
     */
    private String cycleType;
    /**
     * 周期编号
     */
    private String cycleNo;
    /**
     * 总令牌数
     */
    private Long totalTokens;
    /**
     * 已使用令牌数
     */
    private Long usedTokens;
    /**
     * 预留令牌数
     */
    private Long reservedTokens;
    /**
     * 剩余令牌数
     */
    private Long remainingTokens;
    /**
     * 使用率
     */
    private BigDecimal usageRate;
    /**
     * 是否无限额度
     */
    private String unlimited;
    /**
     * 预警阈值
     */
    private Integer warningThreshold;
    /**
     * 周期开始时间
     */
    private Date cycleStart;
    /**
     * 周期结束时间
     */
    private Date cycleEnd;
    /**
     * 状态
     */
    private String status;
    /**
     * 版本
     */
    private Integer version;
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
