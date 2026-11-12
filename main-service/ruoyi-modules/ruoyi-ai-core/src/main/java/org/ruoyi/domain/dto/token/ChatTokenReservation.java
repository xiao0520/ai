package org.ruoyi.domain.dto.token;

import java.util.Date;

/** 一次聊天调用已成功预占的额度。 */
public record ChatTokenReservation(String requestId, Long accountId, Long userId, Long sessionId, Long agentId,
                                   Long modelId, String providerCode, String modelName, Integer modelRevision,
                                   long estimatedInputTokens, long reservedTokens, boolean unlimited, Date startTime) {
}
