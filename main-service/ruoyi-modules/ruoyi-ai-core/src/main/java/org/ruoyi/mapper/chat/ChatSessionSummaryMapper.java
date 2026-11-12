package org.ruoyi.mapper.chat;

import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.chat.ChatSessionSummary;
import org.apache.ibatis.annotations.Param;

/**
 * 会话历史摘要Mapper接口
 *
 * @author xiaoqh
 * @date 2026-08-31
 */
public interface ChatSessionSummaryMapper extends BaseMapperPlus<ChatSessionSummary, ChatSessionSummary> {

    ChatSessionSummary selectActiveBySessionId(@Param("sessionId") Long sessionId,
                                               @Param("activeStatus") String activeStatus);

    ChatSessionSummary selectBySessionId(@Param("sessionId") Long sessionId);
}
