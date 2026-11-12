package org.ruoyi.service.chat;

import org.ruoyi.domain.dto.chat.ChatModelDecision;
import org.ruoyi.domain.dto.knowledge.rag.Citation;
import org.ruoyi.domain.dto.knowledge.rag.ExecutionUsageItem;
import org.ruoyi.domain.dto.token.ChatTokenReservation;
import org.ruoyi.domain.dto.token.RagQueryUsageReservation;

import java.util.List;

/**
 * 聊天终态结算服务。
 *
 * 在单个本地事务中完成额度终态、消息、路由审计和引用写入，避免成功消息与额度状态分离。
 */
public interface IChatExecutionSettlementService {

    /**
     * 结算普通聊天额度并保存成功消息。
     *
     * @param reservation 本轮生成额度预占
     * @param userId      当前用户 ID
     * @param requestId   模型调用请求标识
     * @param content     助手完整回答
     * @param decision    实际执行的模型决策
     * @param inputTokens 实际输入 Token 数
     * @param outputTokens 实际输出 Token 数
     * @param deductTokens 实际扣减 Token 数
     * @return 新增助手消息 ID
     */
    Long settleAndRecord(ChatTokenReservation reservation, Long userId, String requestId, String content,
                         ChatModelDecision decision, long inputTokens, long outputTokens, long deductTokens);

    /**
     * 结算 RAG 检索与生成额度，并保存成功消息和引用。
     *
     * @param generationReservation 生成额度预占
     * @param queryReservation      RAG 查询预算预占
     * @param userId                当前用户 ID
     * @param requestId             模型调用请求标识
     * @param content               助手完整回答
     * @param decision              实际执行的模型决策
     * @param inputTokens           实际输入 Token 数
     * @param outputTokens          实际输出 Token 数
     * @param deductTokens          实际扣减 Token 数
     * @param citations             已完成闭环校验的最终引用
     * @param executionUsage        Embedding、Rerank 等检索阶段实际用量
     * @return 新增助手消息 ID
     */
    Long settleAndRecordRag(ChatTokenReservation generationReservation,
                            RagQueryUsageReservation queryReservation,
                            Long userId, String requestId, String content, ChatModelDecision decision,
                            long inputTokens, long outputTokens, long deductTokens,
                            List<Citation> citations, List<ExecutionUsageItem> executionUsage);

    /**
     * 释放普通聊天额度并保存失败消息。
     *
     * @param reservation 生成额度预占
     * @param userId      当前用户 ID
     * @param requestId   模型调用请求标识
     * @param decision    原计划执行的模型决策
     * @param errorCode   稳定错误编码
     * @param errorMessage 面向用户的错误说明
     */
    void releaseAndRecord(ChatTokenReservation reservation, Long userId, String requestId,
                          ChatModelDecision decision, String errorCode, String errorMessage);

    /** 工具失败前模型已返回用量时，结算真实消耗并保存失败消息。 */
    void releaseAndRecord(ChatTokenReservation reservation, Long userId, String requestId,
                          ChatModelDecision decision, String errorCode, String errorMessage,
                          long inputTokens, long outputTokens);

    /**
     * 结束 RAG 查询用量、释放生成额度并保存失败消息。
     *
     * @param generationReservation 生成额度预占
     * @param queryReservation      RAG 查询预算预占
     * @param userId                当前用户 ID
     * @param requestId             模型调用请求标识
     * @param decision              原计划执行的模型决策
     * @param errorCode             稳定错误编码
     * @param errorMessage          面向用户的错误说明
     * @param executionUsage        失败前已经发生的检索阶段实际用量
     */
    void releaseAndRecordRag(ChatTokenReservation generationReservation,
                             RagQueryUsageReservation queryReservation,
                             Long userId, String requestId, ChatModelDecision decision,
                             String errorCode, String errorMessage,
                             List<ExecutionUsageItem> executionUsage);
}
