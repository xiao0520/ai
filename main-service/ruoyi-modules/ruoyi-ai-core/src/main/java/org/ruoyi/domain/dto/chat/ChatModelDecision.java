package org.ruoyi.domain.dto.chat;

import org.ruoyi.domain.vo.model.ModelVo;
import org.ruoyi.enums.ChatModelSelectionMode;

/**
 * 一次聊天最终使用的模型及可审计路由决策。
 *
 * @param mode                      用户请求的模型选择模式
 * @param model                     Java 最终复核通过的实际执行模型
 * @param reasonCode                固定选择或自动路由的原因编码
 * @param policyRevision            自动路由策略修订号，固定模式为空
 * @param candidateSnapshotHash     自动路由候选快照摘要，固定模式为空
 */
public record ChatModelDecision(ChatModelSelectionMode mode, ModelVo model, String reasonCode,
                                Integer policyRevision, String candidateSnapshotHash) {
}