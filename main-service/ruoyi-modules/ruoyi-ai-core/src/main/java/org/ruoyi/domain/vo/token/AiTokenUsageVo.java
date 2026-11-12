package org.ruoyi.domain.vo.token;

import lombok.Data;
import org.ruoyi.common.translation.annotation.Translation;
import org.ruoyi.common.translation.constant.TransConstant;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * Token 使用记录视图。
 */
@Data
public class AiTokenUsageVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
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
     * 预估成本
     */
    private BigDecimal estimatedCost;
    /**
     * 使用来源
     */
    private String usageSource;
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
    /**
     * 创建时间
     */
    private Date createTime;
}
