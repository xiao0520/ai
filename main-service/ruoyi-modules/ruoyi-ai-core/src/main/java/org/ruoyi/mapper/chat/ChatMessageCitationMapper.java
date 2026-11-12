package org.ruoyi.mapper.chat;

import org.apache.ibatis.annotations.Param;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.chat.ChatMessageCitation;

import java.util.Collection;
import java.util.List;

/** 聊天消息引用持久化与用户归属查询。 */
public interface ChatMessageCitationMapper extends BaseMapperPlus<ChatMessageCitation, ChatMessageCitation> {
    List<ChatMessageCitation> selectByMessageIds(@Param("messageIds") Collection<Long> messageIds);
    ChatMessageCitation selectOwnedCitation(@Param("messageId") Long messageId,
                                             @Param("citationId") String citationId,
                                             @Param("userId") Long userId);
}
