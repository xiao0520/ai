package org.ruoyi.domain.dto.contract;

import java.util.Date;
import java.util.Set;

/** 已批准的合同执行硬预算，持久化后用于异常恢复和防止扩权计费。 */
public record ContractTokenReservation(String requestId, Long accountId, Long userId, Long agentId,
                                       int approvedTokens, boolean unlimited, Date startTime,
                                       Set<String> allowedProfiles) {
}
