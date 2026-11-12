package org.ruoyi.domain.dto.contract;

import org.ruoyi.domain.dto.knowledge.rag.ExecutionUsageItem;
import java.util.List;

/** 同一任务的预算与累计用量，终态和账户结算共享数据库事务。 */
public record ContractBillingSnapshot(ContractTokenReservation reservation,
                                      List<ExecutionUsageItem> executionUsage) {
}
