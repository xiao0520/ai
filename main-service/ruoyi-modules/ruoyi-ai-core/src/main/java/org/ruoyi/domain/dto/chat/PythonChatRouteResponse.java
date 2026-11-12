package org.ruoyi.domain.dto.chat;

/**
 * Python 在 Java 候选快照内返回的确定性路由结果。
 *
 * @param requestId                  对应的路由请求标识
 * @param candidateSnapshotHash      Python 实际使用的候选快照摘要
 * @param selectedModelId            选中的模型 ID
 * @param selectedRevision           选中的模型配置修订号
 * @param reasonCode                 命中的路由规则编码
 * @param policyRevision             Python 实际执行的策略修订号
 */
public record PythonChatRouteResponse(String requestId, String candidateSnapshotHash,
                                      Long selectedModelId, Integer selectedRevision,
                                      String reasonCode, Integer policyRevision) {
}