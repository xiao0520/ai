package org.ruoyi.domain.vo.token;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Token 用量统计。
 */
@Data
public class AiTokenStatisticsVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 总调用次数
     */
    private Long totalCalls = 0L;
    /**
     * 成功调用次数
     */
    private Long successCalls = 0L;
    /**
     * 失败调用次数
     */
    private Long failedCalls = 0L;
    /**
     * 输入令牌数
     */
    private Long inputTokens = 0L;
    /**
     * 输出令牌数
     */
    private Long outputTokens = 0L;
    /**
     * 实际消耗令牌数
     */
    private Long actualTokens = 0L;
    /**
     * 计费令牌数
     */
    private Long chargedTokens = 0L;
    /**
     * 预估成本
     */
    private BigDecimal estimatedCost = BigDecimal.ZERO;
    /**
     * 模型用量排行
     */
    private List<AiTokenStatItemVo> modelRanking = new ArrayList<>();
    /**
     * 用户用量排行
     */
    private List<AiTokenStatItemVo> userRanking = new ArrayList<>();
    /**
     * 每日用量趋势
     */
    private List<AiTokenStatItemVo> dailyTrend = new ArrayList<>();
}
