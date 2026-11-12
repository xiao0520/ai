package org.ruoyi.domain.bo.token;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户 Token 额度操作参数。
 */
@Data
public class AiTokenAccountCommandBo {

    /**
     * 令牌数
     */
    @Min(value = 0, message = "Token数量不能小于0")
    private Long tokens;

    /**
     * 是否无限额度
     */
    private Boolean unlimited;

    /**
     * 预警阈值
     */
    @Min(value = 1, message = "告警阈值不能小于1")
    @Max(value = 100, message = "告警阈值不能大于100")
    private Integer warningThreshold;

    /**
     * 状态
     */
    private String status;

    /**
     * 理由
     */
    @Size(max = 500, message = "操作原因不能超过500个字符")
    private String reason;
}
