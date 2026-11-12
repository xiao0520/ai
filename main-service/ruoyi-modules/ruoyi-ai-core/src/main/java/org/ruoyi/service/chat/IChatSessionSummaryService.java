package org.ruoyi.service.chat;

import org.ruoyi.domain.entity.chat.ChatSessionSummary;

/**
 * 会话历史摘要Service接口
 *
 * @author xiaoqh
 * @date 2026-08-31
 */
public interface IChatSessionSummaryService {

    /**
     * 查询会话当前有效摘要
     *
     * @param sessionId 会话ID
     * @return 会话摘要
     */
    ChatSessionSummary queryActiveBySessionId(Long sessionId);

    /**
     * 保存或更新会话摘要。
     * 摘要由后续 Python AI 服务生成，Java 仅负责校验并持久化。
     *
     * @param summary 会话摘要
     * @return 是否保存成功
     */
    Boolean saveOrUpdate(ChatSessionSummary summary);
}
