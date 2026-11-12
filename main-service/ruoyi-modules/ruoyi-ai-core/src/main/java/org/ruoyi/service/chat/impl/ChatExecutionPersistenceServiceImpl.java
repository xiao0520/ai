package org.ruoyi.service.chat.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.domain.dto.chat.ChatModelDecision;
import org.ruoyi.domain.dto.chat.ChatPersistedTurn;
import org.ruoyi.domain.dto.chat.PythonChatMessage;
import org.ruoyi.domain.dto.knowledge.rag.Citation;
import org.ruoyi.domain.dto.knowledge.rag.SourceRef;
import org.ruoyi.domain.entity.chat.ChatMessage;
import org.ruoyi.domain.entity.chat.ChatMessageCitation;
import org.ruoyi.domain.entity.chat.ChatSession;
import org.ruoyi.mapper.chat.ChatMessageCitationMapper;
import org.ruoyi.mapper.chat.ChatMessageMapper;
import org.ruoyi.mapper.chat.ChatSessionMapper;
import org.ruoyi.service.chat.IChatExecutionPersistenceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 聊天会话、消息、模型路由审计和引用的持久化实现。
 *
 * 每次写入先锁定会话并校验归属。消息、会话统计和引用在同一事务内提交，任一环节失败时整体回滚。
 */
@Service
@RequiredArgsConstructor
public class ChatExecutionPersistenceServiceImpl implements IChatExecutionPersistenceService {

    private static final String CALL_STATUS_SUCCESS = "SUCCESS";
    private static final String CALL_STATUS_FAILED = "FAILED";

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatMessageCitationMapper citationMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatPersistedTurn recordUserTurn(Long userId, Long sessionId, Long agentId,
                                            String requestId, String content,
                                            ChatModelDecision decision, int historyLimit) {
        // 锁定会话后读取历史，防止并发请求生成重复的消息序号。
        ChatSession session = requireActiveOwnedSession(userId, sessionId);
        List<PythonChatMessage> history = loadHistory(sessionId, requestId, historyLimit);

        // 用户消息同样保存最终模型决策，便于按一次完整问答追溯路由结果。
        ChatMessage message = buildMessage(sessionId, nextSequence(session), userId, content,
            AiDomainConstants.CHAT_ROLE_USER, decision, requestId);
        markSucceeded(message, 0L, 0L, 0L);
        insertMessage(message);

        if (sessionMapper.recordUserTurn(
            sessionId, userId, agentId, decision.model().getId(), new Date()) != 1) {
            throw new ServiceException("会话消息写入失败");
        }
        return new ChatPersistedTurn(sessionId, history);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long recordAssistantTurn(Long userId, Long sessionId, String requestId, String content,
                                    ChatModelDecision decision, long inputTokens, long outputTokens,
                                    long deductTokens, List<Citation> citations) {
        // 助手消息、引用和会话用量必须作为一个事务终态提交。
        ChatSession session = requireActiveOwnedSession(userId, sessionId);
        ChatMessage message = buildMessage(sessionId, nextSequence(session), userId, content,
            AiDomainConstants.CHAT_ROLE_ASSISTANT, decision, requestId);
        markSucceeded(message, inputTokens, outputTokens, deductTokens);
        insertMessage(message);
        insertCitations(message.getId(), citations);

        if (sessionMapper.recordAssistantTurn(sessionId, userId, decision.model().getId(),
            inputTokens, outputTokens, deductTokens, new Date()) != 1) {
            throw new ServiceException("会话用量更新失败");
        }
        return message.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordFailedAssistantTurn(Long userId, Long sessionId, String requestId,
                                          ChatModelDecision decision,
                                          String errorCode, String errorMessage) {
        // 失败消息保留模型决策和稳定错误码，但不计入实际 Token 用量。
        ChatSession session = requireActiveOwnedSession(userId, sessionId);
        ChatMessage message = buildMessage(sessionId, nextSequence(session), userId,
            failureContent(errorMessage), AiDomainConstants.CHAT_ROLE_ASSISTANT, decision, requestId);
        markFailed(message, errorCode, errorMessage);
        insertMessage(message);

        if (sessionMapper.recordAssistantTurn(sessionId, userId, decision.model().getId(),
            0L, 0L, 0L, new Date()) != 1) {
            throw new ServiceException("失败对话审计写入失败");
        }
    }

    /** 引用只保存 Python 已返回且 Java 已完成候选闭环校验的最终集合。 */
    private void insertCitations(Long messageId, List<Citation> citations) {
        List<Citation> safeCitations = citations == null ? List.of() : citations;
        for (Citation citation : safeCitations) {
            ChatMessageCitation citationEntity = toCitationEntity(messageId, citation);
            if (citationMapper.insert(citationEntity) != 1) {
                throw new ServiceException("回答引用保存失败");
            }
        }
    }

    private ChatMessageCitation toCitationEntity(Long messageId, Citation citation) {
        try {
            SourceRef source = citation.location().source();
            ChatMessageCitation entity = new ChatMessageCitation();
            entity.setId(IdWorker.getId());
            entity.setMessageId(messageId);
            entity.setCitationId(citation.citationId());
            entity.setKnowledgeId(Long.valueOf(source.knowledgeId()));
            entity.setIndexVersionId(Long.valueOf(source.indexVersion()));
            entity.setDocumentId(Long.valueOf(source.documentId()));
            entity.setDocumentVersionId(Long.valueOf(source.documentVersionId()));
            entity.setGenerationId(Long.valueOf(source.generationId()));
            entity.setChunkId(source.chunkId());
            entity.setContentHash(source.contentHash());
            entity.setTitle(citation.location().title());
            entity.setTitlePathJson(objectMapper.writeValueAsString(citation.location().titlePath()));
            entity.setLocatorJson(objectMapper.writeValueAsString(citation.location().locator()));
            entity.setExcerpt(citation.excerpt());
            entity.setCreateTime(new Date());
            return entity;
        } catch (Exception error) {
            throw new ServiceException("回答引用序列化失败");
        }
    }

    /** 排除本次请求及失败消息，再反转为模型需要的时间正序。 */
    private List<PythonChatMessage> loadHistory(Long sessionId, String requestId, int historyLimit) {
        List<ChatMessage> messages = messageMapper.selectRecentHistory(
            sessionId, 0, requestId,
            AiDomainConstants.CHAT_ROLE_USER,
            AiDomainConstants.CHAT_ROLE_ASSISTANT,
            CALL_STATUS_FAILED,
            historyLimit
        );
        Collections.reverse(messages);
        return messages.stream()
            .map(message -> new PythonChatMessage(message.getRole(), message.getContent()))
            .toList();
    }

    private int nextSequence(ChatSession session) {
        int currentMessageCount = session.getMessageCount() == null ? 0 : session.getMessageCount();
        return Math.addExact(currentMessageCount, 1);
    }

    private String failureContent(String errorMessage) {
        String message = errorMessage == null || errorMessage.isBlank()
            ? "模型调用失败，请稍后重试"
            : errorMessage;
        return "本次对话未完成：" + message;
    }

    /** 使用数据库行锁校验会话归属和状态，后续消息序号与统计均基于该快照。 */
    private ChatSession requireActiveOwnedSession(Long userId, Long sessionId) {
        ChatSession session = sessionMapper.selectByIdForUpdate(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new ServiceException("会话不存在或无权访问");
        }
        if (!AiDomainConstants.STATUS_ACTIVE.equals(session.getStatus())) {
            throw new ServiceException("会话已归档，不能继续对话");
        }
        return session;
    }

    /** 构造消息公共审计字段，成功或失败终态由调用方分别补充。 */
    private ChatMessage buildMessage(Long sessionId, int sequence, Long userId, String content,
                                     String role, ChatModelDecision decision, String requestId) {
        ChatMessage message = new ChatMessage();
        message.setId(IdWorker.getId());
        message.setSessionId(sessionId);
        message.setMessageSeq(sequence);
        message.setUserId(userId);
        message.setContent(content);
        message.setRole(role);
        message.setModelId(decision.model().getId());
        message.setModelName(decision.model().getModelName());
        message.setModelSelectionMode(decision.mode().name());
        message.setModelRevision(decision.model().getPublishedRevision());
        message.setRouteReasonCode(decision.reasonCode());
        message.setRoutePolicyRevision(decision.policyRevision());
        message.setCandidateSnapshotHash(decision.candidateSnapshotHash());
        message.setRequestId(requestId);
        return message;
    }

    private void markSucceeded(ChatMessage message, long inputTokens,
                               long outputTokens, long deductTokens) {
        message.setCallStatus(CALL_STATUS_SUCCESS);
        setTokenUsage(message, inputTokens, outputTokens, deductTokens);
    }

    private void markFailed(ChatMessage message, String errorCode, String errorMessage) {
        message.setCallStatus(CALL_STATUS_FAILED);
        message.setErrorCode(errorCode);
        message.setErrorMessage(errorMessage);
        setTokenUsage(message, 0L, 0L, 0L);
    }

    private void setTokenUsage(ChatMessage message, long inputTokens,
                               long outputTokens, long deductTokens) {
        message.setInputTokens(inputTokens);
        message.setOutputTokens(outputTokens);
        message.setTotalTokens(Math.addExact(inputTokens, outputTokens));
        message.setDeductTokens(deductTokens);
    }

    private void insertMessage(ChatMessage message) {
        if (messageMapper.insert(message) != 1) {
            throw new ServiceException("对话消息保存失败");
        }
    }
}
