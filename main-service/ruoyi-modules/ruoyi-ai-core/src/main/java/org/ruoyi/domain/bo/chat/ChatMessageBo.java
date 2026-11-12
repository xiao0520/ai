package org.ruoyi.domain.bo.chat;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;
import org.ruoyi.domain.entity.chat.ChatMessage;

/**
 * 聊天消息查询条件。
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = ChatMessage.class, reverseConvertGenerate = false)
public class ChatMessageBo extends BaseEntity {

    /** 主键。 */
    private Long id;

    /** 会话主键。 */
    private Long sessionId;

    /** 用户主键。 */
    private Long userId;

    /** 用户账号。 */
    private String userName;

    /** 消息内容。 */
    private String content;

    /** 消息角色。 */
    private String role;

    /** 调用状态。 */
    private String callStatus;

    /** 消息总 Token 数。 */
    private Long totalTokens;

    /** 输入 Token 数。 */
    private Long inputTokens;

    /** 输出 Token 数。 */
    private Long outputTokens;

    /** 扣减 Token 数。 */
    private Long deductTokens;

    /** 模型名称。 */
    private String modelName;
}
