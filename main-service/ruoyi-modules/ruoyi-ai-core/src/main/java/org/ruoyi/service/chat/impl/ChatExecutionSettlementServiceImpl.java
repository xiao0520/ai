package org.ruoyi.service.chat.impl;

import lombok.RequiredArgsConstructor;
import org.ruoyi.constant.AiTokenConstants;
import org.ruoyi.domain.dto.chat.ChatModelDecision;
import org.ruoyi.domain.dto.knowledge.rag.Citation;
import org.ruoyi.domain.dto.knowledge.rag.ExecutionUsageItem;
import org.ruoyi.domain.dto.knowledge.rag.TokenMeasurement;
import org.ruoyi.domain.dto.token.ChatTokenReservation;
import org.ruoyi.domain.dto.token.RagQueryUsageReservation;
import org.ruoyi.service.chat.IChatExecutionPersistenceService;
import org.ruoyi.service.chat.IChatExecutionSettlementService;
import org.ruoyi.service.token.IAiChatTokenService;
import org.ruoyi.service.token.IRagQueryUsageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 聊天执行终态的事务编排实现。
 *
 * 额度结算或释放、失败审计、助手消息和引用必须在同一事务中完成，任一写入失败时整体回滚。
 */
@Service
@RequiredArgsConstructor
public class ChatExecutionSettlementServiceImpl implements IChatExecutionSettlementService {
    private final IAiChatTokenService chatTokenService;
    private final IRagQueryUsageService ragQueryUsageService;
    private final IChatExecutionPersistenceService persistenceService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long settleAndRecord(ChatTokenReservation reservation, Long userId, String requestId, String content,
                                ChatModelDecision decision, long inputTokens, long outputTokens,
                                long deductTokens) {
        // 先更新额度终态，再写助手消息；任一步失败都由当前事务统一回滚。
        chatTokenService.settle(reservation, inputTokens, outputTokens);
        return persistenceService.recordAssistantTurn(userId, reservation.sessionId(), requestId, content, decision,
            inputTokens, outputTokens, deductTokens, List.of());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long settleAndRecordRag(ChatTokenReservation generationReservation,
                                   RagQueryUsageReservation queryReservation,
                                   Long userId, String requestId, String content, ChatModelDecision decision,
                                   long inputTokens, long outputTokens, long deductTokens,
                                   List<Citation> citations, List<ExecutionUsageItem> executionUsage) {
        // RAG 查询用量、生成额度、消息和引用共享同一事务终态。
        ragQueryUsageService.settle(queryReservation, executionUsage, AiTokenConstants.USAGE_SUCCESS, null);
        chatTokenService.settle(generationReservation, inputTokens, outputTokens);
        return persistenceService.recordAssistantTurn(userId, generationReservation.sessionId(), requestId,
            content, decision, inputTokens, outputTokens, deductTokens, citations);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseAndRecord(ChatTokenReservation reservation, Long userId, String requestId,
                                 ChatModelDecision decision, String errorCode, String errorMessage) {
        // 失败消息只有在额度释放成功后才提交，避免形成互相矛盾的终态。
        chatTokenService.release(reservation, errorCode);
        persistenceService.recordFailedAssistantTurn(userId, reservation.sessionId(), requestId, decision, errorCode,
            errorMessage);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseAndRecord(ChatTokenReservation reservation, Long userId, String requestId,
                                 ChatModelDecision decision, String errorCode, String errorMessage,
                                 long inputTokens, long outputTokens) {
        chatTokenService.settleFailed(reservation, inputTokens, outputTokens, errorCode);
        persistenceService.recordFailedAssistantTurn(userId, reservation.sessionId(), requestId, decision, errorCode,
            errorMessage);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseAndRecordRag(ChatTokenReservation generationReservation,
                                    RagQueryUsageReservation queryReservation,
                                    Long userId, String requestId, ChatModelDecision decision,
                                    String errorCode, String errorMessage,
                                    List<ExecutionUsageItem> executionUsage) {
        // 检索用量独立结算；工具调用前的模型轮次若已消耗 Token，不能全额退还。
        ragQueryUsageService.settle(queryReservation, executionUsage, AiTokenConstants.USAGE_FAILED, errorCode);
        TokenMeasurement chatMeasurement = executionUsage.stream()
            .filter(item -> item.stage() == ExecutionUsageItem.Stage.V_CHAT)
            .map(ExecutionUsageItem::measurement)
            .filter(TokenMeasurement.class::isInstance)
            .map(TokenMeasurement.class::cast)
            .findFirst().orElse(null);
        if (chatMeasurement == null) {
            chatTokenService.release(generationReservation, errorCode);
        } else {
            chatTokenService.settleFailed(generationReservation,
                chatMeasurement.inputTokens(), chatMeasurement.outputTokens(), errorCode);
        }
        persistenceService.recordFailedAssistantTurn(userId, generationReservation.sessionId(), requestId,
            decision, errorCode, errorMessage);
    }
}
