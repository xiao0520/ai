package org.ruoyi.service.token;

import org.ruoyi.domain.dto.knowledge.rag.ExecutionUsageItem;
import org.ruoyi.domain.dto.knowledge.rag.RagExecutionScope;
import org.ruoyi.domain.dto.token.RagQueryUsageReservation;

import java.util.List;

/**
 * RAG 查询、改写、Embedding 与 Rerank 调用的额度和用量结算。
 */
public interface IRagQueryUsageService {

    /**
     * 按本轮问题与可能调用的查询模型数量预占 Token 上界。
     *
     * @param requestId 请求追踪标识
     * @param userId 用户主键
     * @param sessionId 会话主键
     * @param agentId 智能体主键
     * @param scope RAG 执行范围快照
     * @param estimatedQuestionTokens 估算的问题 Token 数
     * @return 查询阶段额度预占上下文
     */
    RagQueryUsageReservation reserve(String requestId, Long userId, Long sessionId, Long agentId,
                                     RagExecutionScope scope, long estimatedQuestionTokens);

    /**
     * 按调用标识幂等保存查询阶段计量，并结算真实或估算的 Token。
     *
     * @param reservation 查询阶段额度预占上下文
     * @param items 各模型调用的用量明细
     * @param terminalStatus 查询终态
     * @param errorCode 失败原因编码，成功时可为空
     */
    void settle(RagQueryUsageReservation reservation, List<ExecutionUsageItem> items,
                String terminalStatus, String errorCode);
}
