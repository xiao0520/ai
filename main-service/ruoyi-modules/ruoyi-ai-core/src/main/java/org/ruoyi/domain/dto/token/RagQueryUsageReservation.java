package org.ruoyi.domain.dto.token;

import java.util.Date;

/**
 * 一次 RAG 查询阶段已经预占的 Token 额度。
 *
 * @param requestId      模型调用请求标识，同时作为幂等结算键
 * @param accountId      扣费账户 ID，无限额度时可能为空
 * @param userId         当前用户 ID
 * @param sessionId      会话 ID
 * @param agentId        可选 Agent ID
 * @param reservedTokens 本次查询预占 Token 数
 * @param unlimited      是否为无限额度账户
 * @param startTime      查询开始时间
 */
public record RagQueryUsageReservation(String requestId, Long accountId, Long userId, Long sessionId,
                                       Long agentId, long reservedTokens, boolean unlimited, Date startTime) {
}