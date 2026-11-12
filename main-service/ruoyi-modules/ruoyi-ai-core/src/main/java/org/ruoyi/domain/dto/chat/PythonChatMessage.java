package org.ruoyi.domain.dto.chat;

/**
 * Java 与 Python 之间的单条聊天消息契约。
 *
 * @param role    system、user 或 assistant 角色
 * @param content 消息正文
 */
public record PythonChatMessage(String role, String content) {
}