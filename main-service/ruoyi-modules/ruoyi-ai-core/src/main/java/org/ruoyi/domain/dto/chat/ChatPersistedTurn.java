package org.ruoyi.domain.dto.chat;

import java.util.List;

/**
 * 用户消息已经落库后的会话上下文。
 *
 * @param sessionId 会话 ID
 * @param history   不包含本次用户消息、按时间正序排列的历史消息
 */
public record ChatPersistedTurn(Long sessionId, List<PythonChatMessage> history) {
}