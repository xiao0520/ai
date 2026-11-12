package org.ruoyi.domain.dto.chat;

/**
 * Python 返回的模型实际用量。
 *
 * @param inputTokens  实际输入 Token 数
 * @param outputTokens 实际输出 Token 数
 * @param totalTokens  实际总 Token 数，缺失时由 Java 使用输入与输出之和
 */
public record PythonChatUsage(Long inputTokens, Long outputTokens, Long totalTokens) {
}