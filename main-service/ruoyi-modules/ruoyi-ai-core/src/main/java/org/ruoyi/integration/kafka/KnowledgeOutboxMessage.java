package org.ruoyi.integration.kafka;

/** 待发送的不可变消息；仅传输凭证别名和业务引用，不保存模型明文密钥。 */
public record KnowledgeOutboxMessage(String id, Long jobId, String attemptId, String topic,
                                     String messageKey, String payload, Integer sendAttempts) {
}