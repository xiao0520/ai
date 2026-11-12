package org.ruoyi.domain.entity.chat;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 会话管理对象 chat_session
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_session")
public class ChatSession extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 会话标题
     */
    private String sessionTitle;

    /**
     * 智能体ID
     */
    private Long agentId;

    /**
     * 模型ID
     */
    private Long modelId;

    /**
     * 状态
     */
    private String status;

    /**
     * 最后消息时间
     */
    private Date lastMessageAt;

    /**
     * 消息数量
     */
    private Integer messageCount;

    /**
     * 输入令牌数
     */
    private Long inputTokens;

    /**
     * 输出令牌数
     */
    private Long outputTokens;

    /**
     * 扣除令牌数
     */
    private Long deductTokens;

    /**
     * 对话ID
     */
    private String conversationId;

}

