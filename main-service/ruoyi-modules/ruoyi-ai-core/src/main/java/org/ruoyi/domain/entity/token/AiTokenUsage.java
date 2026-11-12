package org.ruoyi.domain.entity.token;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 模型 Token 使用记录。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("token_usage")
public class AiTokenUsage extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId("id")
    private Long id;

    /**
     * 请求ID
     */
    private String requestId;
    /**
     * 调用ID
     */
    private String callId;
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 账户ID
     */
    private Long accountId;
    /**
     * 会话ID
     */
    private Long sessionId;
    /**
     * 智能体ID
     */
    private Long agentId;
    /**
     * 模型ID
     */
    private Long modelId;
    /**
     * 提供者编码
     */
    private String providerCode;
    /**
     * 模型名称
     */
    private String modelName;
    /**
     * 模型修订号
     */
    private Integer modelRevision;
    /**
     * 输入令牌数
     */
    private Long inputTokens;
    /**
     * 输出令牌数
     */
    private Long outputTokens;
    /**
     * 缓存令牌数
     */
    private Long cacheTokens;

    /**
     * 推理令牌数
     */
    private Long reasoningTokens;

    /**
     * 实际令牌数
     */
    private Long actualTokens;

    /**
     * 计费令牌数
     */
    private Long chargedTokens;

    /**
     * 预留令牌数
     */
    private Long reservedTokens;

    /**
     * 输入权重
     */
    private BigDecimal inputWeight;
    /**
     * 输出权重
     */
    private BigDecimal outputWeight;
    /**
     * 缓存权重
     */
    private BigDecimal cacheWeight;
    /**
     * 推理权重
     */
    private BigDecimal reasoningWeight;
    /**
     * 预估成本
     */
    private BigDecimal estimatedCost;
    /**
     * 使用来源
     */
    private String usageSource;
    /** 原始计量单位：TOKENS、REQUESTS、DOCUMENT_PAIRS 或 UNKNOWN。 */
    private String usageUnit;
    /** 原始计量数量；UNKNOWN 时为空。 */
    private Long usageQuantity;
    /** 计量质量：ACTUAL、ESTIMATED 或 UNKNOWN。 */
    private String measurementQuality;
    /**
     * 状态
     */
    private String status;
    /**
     * 错误编码
     */
    private String errorCode;
    /**
     * 错误信息
     */
    private String errorMessage;
    /**
     * 开始时间
     */
    private Date startTime;
    /**
     * 完成时间
     */
    private Date finishTime;
}
