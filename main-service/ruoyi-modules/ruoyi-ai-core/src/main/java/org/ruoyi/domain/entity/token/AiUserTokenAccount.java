package org.ruoyi.domain.entity.token;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 用户 Token 额度账户。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_token_account")
public class AiUserTokenAccount extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId("id")
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;
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
    @Version
    private Integer version;

    /**
     * 备注
     */
    private String remark;
}
