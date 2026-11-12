package org.ruoyi.domain.entity.token;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * Token 额度调整流水。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("token_quota_log")
public class AiTokenQuotaLog extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId("id")
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
     * 理由
     */
    private String reason;
}
