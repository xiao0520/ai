package org.ruoyi.domain.bo.chat;

import org.ruoyi.domain.entity.chat.ChatSession;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 会话管理业务对象 chat_session
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = ChatSession.class, reverseConvertGenerate = false)
public class ChatSessionBo extends BaseEntity {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户账号。
     */
    private String userName;

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
     * 对话ID
     */
    private String conversationId;


}

