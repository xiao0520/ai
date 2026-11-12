package org.ruoyi.service.chat;

import org.ruoyi.domain.dto.chat.ChatModelDecision;
import org.ruoyi.domain.dto.chat.ChatPersistedTurn;
import org.ruoyi.domain.dto.knowledge.rag.Citation;

import java.util.List;

/**
 * 聊天执行链路的会话、消息、路由决策与引用持久化能力。
 *
 * 持久化顺序用于保证会话归属、请求幂等和 RAG 引用闭环，不负责调用外部模型。
 */
public interface IChatExecutionPersistenceService {

    /**
     * 锁定并校验会话，保存用户消息，同时返回当前请求可使用的历史消息。
     *
     * @param userId       当前用户 ID
     * @param sessionId    会话 ID
     * @param agentId      可选 Agent ID
     * @param requestId    本次模型调用请求标识
     * @param content      用户消息正文
     * @param decision     已通过最终复核的模型决策
     * @param historyLimit 最大历史消息数量
     * @return 会话 ID 和按时间正序排列的历史消息
     */
    ChatPersistedTurn recordUserTurn(Long userId, Long sessionId, Long agentId, String requestId, String content,
                                     ChatModelDecision decision, int historyLimit);

    /**
     * 保存成功的助手消息、实际 Token 用量和已经完成复核的引用。
     *
     * @param userId       当前用户 ID
     * @param sessionId    会话 ID
     * @param requestId    本次模型调用请求标识
     * @param content      助手完整回答
     * @param decision     实际执行的模型决策
     * @param inputTokens  实际输入 Token 数
     * @param outputTokens 实际输出 Token 数
     * @param deductTokens 实际扣减 Token 数
     * @param citations    已完成候选闭环校验的引用
     * @return 新增助手消息 ID
     */
    Long recordAssistantTurn(Long userId, Long sessionId, String requestId, String content,
                             ChatModelDecision decision, long inputTokens, long outputTokens,
                             long deductTokens, List<Citation> citations);

    /**
     * 保存失败的助手消息和稳定错误信息，形成完整调用审计。
     *
     * @param userId      当前用户 ID
     * @param sessionId   会话 ID
     * @param requestId   本次模型调用请求标识
     * @param decision    原计划执行的模型决策
     * @param errorCode   稳定错误编码
     * @param errorMessage 面向用户的错误说明
     */
    void recordFailedAssistantTurn(Long userId, Long sessionId, String requestId,
                                   ChatModelDecision decision, String errorCode, String errorMessage);
}
