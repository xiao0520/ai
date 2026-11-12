package org.ruoyi.domain.entity.chat;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 聊天消息实体。
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_message")
public class ChatMessage extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键。 */
    @TableId(value = "id")
    private Long id;

    /** 会话主键。 */
    private Long sessionId;

    /** 会话内消息序号。 */
    private Integer messageSeq;

    /** 用户主键。 */
    private Long userId;

    /** 消息内容。 */
    private String content;

    /** 消息角色。 */
    private String role;

    /** 实际调用模型主键。 */
    private Long modelId;

    /** 模型选择模式：FIXED 或 AUTO。 */
    private String modelSelectionMode;

    /** 实际调用的模型发布修订号。 */
    private Integer modelRevision;

    /** AUTO 路由原因编码；FIXED 为 FIXED。 */
    private String routeReasonCode;

    /** AUTO 路由策略修订号。 */
    private Integer routePolicyRevision;

    /** AUTO 候选模型快照 SHA-256。 */
    private String candidateSnapshotHash;

    /** 消息总 Token 数。 */
    private Long totalTokens;

    /** 输入 Token 数。 */
    private Long inputTokens;

    /** 输出 Token 数。 */
    private Long outputTokens;

    /** 扣减 Token 数。 */
    private Long deductTokens;

    /** 模型名称快照。 */
    private String modelName;

    /** 调用状态。 */
    private String callStatus;

    /** 调用请求标识。 */
    private String requestId;

    /** 调用失败码。 */
    private String errorCode;

    /** 调用失败说明。 */
    private String errorMessage;
}
