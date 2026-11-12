package org.ruoyi.service.chat.impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.domain.bo.chat.ChatSendBo;
import org.ruoyi.domain.dto.chat.ChatModelDecision;
import org.ruoyi.domain.dto.chat.ChatPersistedTurn;
import org.ruoyi.domain.dto.chat.PythonChatActor;
import org.ruoyi.domain.dto.chat.PythonChatInput;
import org.ruoyi.domain.dto.chat.PythonChatMessage;
import org.ruoyi.domain.dto.chat.PythonChatStreamEvent;
import org.ruoyi.domain.dto.chat.PythonChatStreamRequest;
import org.ruoyi.domain.dto.chat.PythonChatUsage;
import org.ruoyi.domain.dto.chat.PythonExecutionPolicy;
import org.ruoyi.domain.dto.chat.PythonModelProfile;
import org.ruoyi.domain.dto.knowledge.RagContractJson;
import org.ruoyi.domain.dto.knowledge.rag.Citation;
import org.ruoyi.domain.dto.knowledge.rag.ExecutionUsageItem;
import org.ruoyi.domain.dto.knowledge.rag.RagChatActor;
import org.ruoyi.domain.dto.knowledge.rag.RagChatInput;
import org.ruoyi.domain.dto.knowledge.rag.RagChatMessage;
import org.ruoyi.domain.dto.knowledge.rag.RagChatStreamRequest;
import org.ruoyi.domain.dto.knowledge.rag.McpToolGrant;
import org.ruoyi.domain.dto.knowledge.rag.RagCitationPayload;
import org.ruoyi.domain.dto.knowledge.rag.RagCompletedPayload;
import org.ruoyi.domain.dto.knowledge.rag.RagExecutionPolicy;
import org.ruoyi.domain.dto.knowledge.rag.RagExecutionScope;
import org.ruoyi.domain.dto.knowledge.rag.RagFailedPayload;
import org.ruoyi.domain.dto.knowledge.rag.RagGenerationProfile;
import org.ruoyi.domain.dto.knowledge.rag.RagGenerationUsage;
import org.ruoyi.domain.dto.token.ChatTokenReservation;
import org.ruoyi.domain.dto.token.RagQueryUsageReservation;
import org.ruoyi.domain.vo.model.ModelVo;


import org.ruoyi.integration.python.PythonAiExecutionClient;
import org.ruoyi.service.chat.IChatExecutionPersistenceService;
import org.ruoyi.service.chat.IChatExecutionService;
import org.ruoyi.service.chat.IChatExecutionSettlementService;
import org.ruoyi.service.chat.IChatKnowledgeScopeService;
import org.ruoyi.service.chat.IChatModelRoutingService;
import org.ruoyi.service.knowledge.IKnowledgeRetrievalScopeService;
import org.ruoyi.service.mcp.IMcpChatToolGrantService;
import org.ruoyi.service.knowledge.KnowledgeArtifactAccessException;
import org.ruoyi.service.token.IAiChatTokenService;
import org.ruoyi.service.token.IRagQueryUsageService;
import org.ruoyi.support.ChatExecutionProperties;
import org.ruoyi.support.RagChatProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;

/**
 * 浏览器与 Python 执行服务之间的聊天编排服务。
 * Java 负责模型决策、会话归属、历史裁剪、额度预占、RAG 授权范围和终态持久化；
 * Python 负责执行模型与检索链。外部流式调用不持有数据库事务，成功或失败只在终态开启本地事务。
 */
@Service
@Slf4j
public class ChatExecutionServiceImpl implements IChatExecutionService {
    /** Python 执行服务返回的文本增量、用量和终态事件。 */
    private static final String EVENT_DELTA = "delta";
    private static final String EVENT_USAGE = "usage";
    private static final String EVENT_COMPLETED = "completed";
    private static final String EVENT_FAILED = "failed";

    /** 长连接保活事件，仅维持连接，不参与消息持久化。 */
    private static final String EVENT_KEEPALIVE = "keepalive";

    /** 单条引用事件：接收 Python 引用后，Java 复核并透传给浏览器。 */
    private static final String EVENT_CITATION = "citation";

    /** Java 面向浏览器输出的内容、引用集合、终态和错误事件。 */
    private static final String EVENT_CONTENT = "content";
    private static final String EVENT_CITATIONS = "citations";
    private static final String EVENT_DONE = "done";
    private static final String EVENT_ERROR = "error";
    /** 每条聊天消息为角色和协议字段预留的估算 Token。 */
    private static final long MESSAGE_PROTOCOL_OVERHEAD_TOKENS = 16L;
    /** RAG 提示词模板在知识片段之外需要预留的输入 Token。 */
    private static final long RAG_PROMPT_OVERHEAD_TOKENS = 256L;

    private final IChatModelRoutingService modelRoutingService;
    private final IMcpChatToolGrantService mcpChatToolGrantService;
    private final IChatExecutionPersistenceService persistenceService;
    private final IChatExecutionSettlementService settlementService;
    private final IAiChatTokenService chatTokenService;
    private final IRagQueryUsageService ragQueryUsageService;
    private final IChatKnowledgeScopeService knowledgeScopeService;
    private final IKnowledgeRetrievalScopeService retrievalScopeService;
    private final PythonAiExecutionClient pythonAiExecutionClient;
    private final RagContractJson ragCodec;
    private final ChatExecutionProperties properties;
    private final RagChatProperties ragProperties;
    private final AsyncTaskExecutor taskExecutor;

    public ChatExecutionServiceImpl(IChatModelRoutingService modelRoutingService,
                                    IMcpChatToolGrantService mcpChatToolGrantService,
                                    IChatExecutionPersistenceService persistenceService,
                                    IChatExecutionSettlementService settlementService,
                                    IAiChatTokenService chatTokenService,
                                    IRagQueryUsageService ragQueryUsageService,
                                    IChatKnowledgeScopeService knowledgeScopeService,
                                    IKnowledgeRetrievalScopeService retrievalScopeService,
                                    PythonAiExecutionClient pythonAiExecutionClient,
                                    RagContractJson ragCodec,
                                    ChatExecutionProperties properties,
                                    RagChatProperties ragProperties,
                                    @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor) {
        this.modelRoutingService = modelRoutingService;
        this.mcpChatToolGrantService = mcpChatToolGrantService;
        this.persistenceService = persistenceService;
        this.settlementService = settlementService;
        this.chatTokenService = chatTokenService;
        this.ragQueryUsageService = ragQueryUsageService;
        this.knowledgeScopeService = knowledgeScopeService;
        this.retrievalScopeService = retrievalScopeService;
        this.pythonAiExecutionClient = pythonAiExecutionClient;
        this.ragCodec = ragCodec;
        this.properties = properties;
        this.ragProperties = ragProperties;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public SseEmitter stream(ChatSendBo command, Long userId) {
        // 普通聊天不扫描知识库；只有显式 KNOWLEDGE 请求才冻结检索范围。
        List<Long> knowledgeIds = command.effectiveChatMode() == org.ruoyi.enums.ChatKnowledgeMode.KNOWLEDGE
            ? knowledgeScopeService.resolve(command.getKnowledgeId(), ragProperties.toRetrievalProfile())
            : List.of();
        ChatModelDecision decision = modelRoutingService.resolve(command, userId);
        List<McpToolGrant> toolGrants = mcpChatToolGrantService.resolve(
            userId, command.getAgentId(), decision.model());
        // 浏览器关闭或超时时同步中断后台任务，最终由执行链释放尚未结算的额度。
        SseEmitter emitter = new SseEmitter(properties.getRequestTotalTimeout().toMillis());
        Future<?> task = taskExecutor.submit(() -> execute(command, userId, decision, knowledgeIds, toolGrants, emitter));
        emitter.onCompletion(() -> task.cancel(true));
        emitter.onTimeout(() -> task.cancel(true));
        return emitter;
    }

    /** 外部 HTTP 流不进入数据库事务；成功或失败只在终态执行一次本地事务。 */
    private void execute(ChatSendBo command, Long userId, ChatModelDecision decision,
                         List<Long> knowledgeIds, List<McpToolGrant> toolGrants, SseEmitter emitter) {
        ModelVo model = decision.model();
        String requestId = UUID.randomUUID().toString();
        ChatTokenReservation generationReservation = null;
        RagQueryUsageReservation queryReservation = null;
        boolean userTurnPersisted = false;
        StreamResult result = new StreamResult();
        try {
            // 先锁定会话并保存用户消息，随后基于稳定历史计算输入与预算。
            ChatPersistedTurn turn = persistenceService.recordUserTurn(userId, command.getSessionId(),
                command.getAgentId(), requestId, command.getContent(), decision, historyLimit(model));
            userTurnPersisted = true;
            List<PythonChatMessage> history = fitHistoryToInputLimit(turn.history(), command.getContent(),
                model.getMaxInputTokens());
            long messageInputTokens = estimateInputTokens(history, command.getContent());
            int contextBudgetTokens = knowledgeIds.isEmpty()
                ? 0 : effectiveContextBudget(model, messageInputTokens);
            long generationInputBudget = Math.addExact(messageInputTokens, contextBudgetTokens);
            if (!knowledgeIds.isEmpty()) {
                generationInputBudget = Math.addExact(generationInputBudget, RAG_PROMPT_OVERHEAD_TOKENS);
            }
            // 模型调用前预占生成额度，防止并发请求共同透支用户余额。
            generationReservation = toolGrants.isEmpty()
                ? chatTokenService.reserve(requestId, userId, command.getSessionId(),
                    command.getAgentId(), model, generationInputBudget)
                : chatTokenService.reserve(requestId, userId, command.getSessionId(),
                    command.getAgentId(), model, generationInputBudget, 4);

            // 仅显式知识库模式进入 RAG；普通聊天继续保留模型、MCP、会话和用量链路。
            if (knowledgeIds.isEmpty()) {
                pythonAiExecutionClient.streamChat(buildPythonRequest(command, userId, requestId, model,
                    generationReservation, history, toolGrants), event -> handlePythonEvent(event, emitter, result));
            } else {
                RagExecutionScope scope = retrievalScopeService.create(userId, requestId,
                    knowledgeIds, ragProperties.toRetrievalProfile(), contextBudgetTokens);
                queryReservation = ragQueryUsageService.reserve(requestId, userId, command.getSessionId(),
                    command.getAgentId(), scope, estimateInputTokens(List.of(), command.getContent()));
                result.rag = true;
                pythonAiExecutionClient.streamRagChat(buildRagRequest(command, userId, requestId, model,
                    generationReservation, history, scope, toolGrants), event -> handleRagEvent(event, requestId, emitter, result));
            }
            if (!result.completed) {
                throw new ServiceException("AI执行服务未返回完成事件");
            }

            // 只有收到完整完成事件后才结算实际用量并持久化助手消息。

            PythonChatUsage usage = result.usage == null ? new PythonChatUsage(0L, 0L, 0L) : result.usage;
            long deductTokens = totalTokens(usage);
            if (!toolGrants.isEmpty() && !generationReservation.unlimited()
                && deductTokens > generationReservation.reservedTokens()) {
                throw new ServiceException("工具调用的实际Token用量超出本次预占额度");
            }
            Long messageId;
            if (result.rag) {
                if (queryReservation == null) {
                    throw new ServiceException("RAG查询预算状态缺失");
                }
                messageId = settlementService.settleAndRecordRag(generationReservation, queryReservation,
                    userId, requestId, result.content.toString(), decision, tokenValue(usage.inputTokens()),
                    tokenValue(usage.outputTokens()), deductTokens, result.citations, result.executionUsage);
                send(emitter, EVENT_CITATIONS, browserCitations(messageId, result.citations));
                queryReservation = null;
            } else {
                messageId = settlementService.settleAndRecord(generationReservation, userId, requestId,
                    result.content.toString(), decision, tokenValue(usage.inputTokens()),
                    tokenValue(usage.outputTokens()), deductTokens);
            }
            generationReservation = null;
            send(emitter, EVENT_DONE, new BrowserDoneEvent(
                true, messageId, model.getId(), model.getModelName(),
                decision.mode().name(), decision.reasonCode()));
        } catch (Exception error) {
            log.error("对话执行失败：requestId={}, userId={}, sessionId={}, modelId={}, modelRevision={}, errorType={}",
                requestId, userId, command.getSessionId(), model.getId(), model.getPublishedRevision(),
                error.getClass().getName(), error);
            // 根据执行阶段选择补偿动作，避免重复释放额度或在用户消息落库前写失败终态。
            String errorCode = errorCode(error);
            String errorMessage = userMessage(error);
            if (generationReservation != null && queryReservation != null && userTurnPersisted) {
                releaseAndRecordRag(generationReservation, queryReservation, userId, requestId, decision,
                    errorCode, errorMessage, result.executionUsage);
            } else if (generationReservation != null && userTurnPersisted) {
                releaseAndRecord(generationReservation, userId, requestId, decision, errorCode, errorMessage,
                    result.usage);
            } else if (generationReservation != null) {
                releaseReservation(generationReservation, errorCode);
            } else if (userTurnPersisted) {
                persistFailure(userId, command, requestId, decision, errorCode, errorMessage);
            }
            send(emitter, EVENT_ERROR, new BrowserErrorEvent(errorCode, errorMessage));
            send(emitter, EVENT_DONE, new BrowserDoneEvent(
                true, null, model.getId(), model.getModelName(),
                decision.mode().name(), decision.reasonCode()));
        } finally {
            // 无论成功、失败或客户端取消，都结束 SSE 响应，避免连接长期占用。
            emitter.complete();
        }
    }

    /** 普通聊天只接受 keepalive、正文、用量、完成和失败五类协议事件。 */
    private void handlePythonEvent(PythonChatStreamEvent event, SseEmitter emitter, StreamResult result) {
        if (EVENT_KEEPALIVE.equals(event.event())) {
            return;
        }
        if (EVENT_DELTA.equals(event.event())) {
            appendDelta(event.data(), emitter, result);
            return;
        }
        if (EVENT_USAGE.equals(event.event())) {
            result.usage = readUsage(event.data());
            return;
        }
        if (EVENT_COMPLETED.equals(event.event())) {
            completeContent(event.data().path("content").asText(""), result);
            JsonNode usage = event.data().path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                result.usage = readUsage(usage);
            }
            result.completed = true;
            return;
        }
        if (EVENT_FAILED.equals(event.event())) {
            throw pythonFailure(event.data().path("errorCode").asText());
        }
        throw new ServiceException("AI执行服务返回了未知事件");
    }

    /** RAG 事件使用生成契约严格解析，最终引用必须是此前候选引用的子集。 */
    private void handleRagEvent(PythonChatStreamEvent event, String requestId,
                                SseEmitter emitter, StreamResult result) {
        if (EVENT_KEEPALIVE.equals(event.event())) {
            return;
        }
        if (EVENT_DELTA.equals(event.event())) {
            appendDelta(event.data(), emitter, result);
            return;
        }
        if (EVENT_USAGE.equals(event.event())) {
            result.usage = readUsage(event.data());
            return;
        }
        if (EVENT_CITATION.equals(event.event())) {
            RagCitationPayload payload = readRag(event.data(), RagCitationPayload.class);
            requireRequestId(requestId, payload.requestId());
            Citation citation = payload.citation();
            if (result.candidateCitations.putIfAbsent(citation.citationId(), citation) != null) {
                throw new ServiceException("AI执行服务返回了重复引用标识");
            }
            send(emitter, EVENT_CITATION, browserCitation(null, citation));
            return;
        }
        if (EVENT_COMPLETED.equals(event.event())) {
            RagCompletedPayload payload = readRag(event.data(), RagCompletedPayload.class);
            requireRequestId(requestId, payload.requestId());
            completeContent(payload.content(), result);
            result.usage = toChatUsage(payload.usage());
            for (Citation citation : payload.citations()) {
                Citation candidate = result.candidateCitations.get(citation.citationId());
                if (candidate == null || !candidate.equals(citation)) {
                    throw new ServiceException("AI执行服务完成引用不属于已复核候选");
                }
            }
            result.citations = List.copyOf(payload.citations());
            result.executionUsage = List.copyOf(payload.executionUsage());
            result.completed = true;
            return;
        }
        if (EVENT_FAILED.equals(event.event())) {
            RagFailedPayload payload = readRag(event.data(), RagFailedPayload.class);
            requireRequestId(requestId, payload.requestId());
            result.executionUsage = List.copyOf(payload.executionUsage());
            throw pythonFailure(payload.errorCode().value());
        }
        throw new ServiceException("AI执行服务返回了未知RAG事件");
    }

    /** 同时累计完整正文并将增量正文转发给浏览器。 */
    private void appendDelta(JsonNode data, SseEmitter emitter, StreamResult result) {
        String text = data.path("text").asText("");
        if (!text.isEmpty()) {
            result.content.append(text);
            send(emitter, EVENT_CONTENT, new BrowserContentEvent(text));
        }
    }

    /** 完成事件正文必须与此前增量拼接结果一致，防止持久化内容与用户所见内容不同。 */
    private void completeContent(String completedContent, StreamResult result) {
        if (result.content.isEmpty()) {
            result.content.append(completedContent);
        } else if (!result.content.toString().equals(completedContent)) {
            throw new ServiceException("AI执行服务流式正文与完成正文不一致");
        }
    }

    /** 将 Java 已核定的模型快照和裁剪后历史转换为普通聊天执行请求。 */
    private PythonChatStreamRequest buildPythonRequest(ChatSendBo command, Long userId, String requestId,
                                                       ModelVo model, ChatTokenReservation reservation,
                                                       List<PythonChatMessage> history, List<McpToolGrant> toolGrants) {
        List<PythonChatMessage> messages = new ArrayList<>(history);
        messages.add(new PythonChatMessage(AiDomainConstants.CHAT_ROLE_USER, command.getContent()));
        int maxOutputTokens = effectiveMaxOutputTokens(model, reservation);
        return new PythonChatStreamRequest(requestId, clientRequestId(command, requestId),
            String.valueOf(command.getSessionId()), new PythonChatActor(String.valueOf(userId), List.of()),
            new PythonChatInput(messages),
            new PythonModelProfile(model.getId(), model.getPublishedRevision(), model.getModelName(),
                model.getProviderCode(), model.getApiHost(), model.getApiMode(), model.getCredentialKey(),
                model.getMaxInputTokens(), maxOutputTokens),
            new PythonExecutionPolicy(model.getMaxInputTokens(), maxOutputTokens,
                Math.toIntExact(properties.getRequestTotalTimeout().toSeconds()),
                toolGrants.isEmpty() ? null : reservation.unlimited()
                    ? Math.multiplyExact(Math.addExact(reservation.estimatedInputTokens(),
                        model.getMaxOutputTokens().longValue()), 4L) : reservation.reservedTokens()), toolGrants);
    }

    /** 将同一份会话历史转换为 RAG 契约，并附带 Java 签发的检索授权范围。 */
    private RagChatStreamRequest buildRagRequest(ChatSendBo command, Long userId, String requestId,
                                                 ModelVo model, ChatTokenReservation reservation,
                                                 List<PythonChatMessage> history, RagExecutionScope scope,
                                                 List<McpToolGrant> toolGrants) {
        List<RagChatMessage> messages = history.stream()
            .map(item -> new RagChatMessage(RagChatMessage.Role.fromValue(item.role()), item.content()))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        messages.add(new RagChatMessage(RagChatMessage.Role.V_USER, command.getContent()));
        int maxOutputTokens = effectiveMaxOutputTokens(model, reservation);
        return new RagChatStreamRequest(requestId, clientRequestId(command, requestId),
            String.valueOf(command.getSessionId()), new RagChatActor(String.valueOf(userId), List.of()),
            new RagChatInput(List.copyOf(messages)),
            new RagGenerationProfile(BigInteger.valueOf(model.getId()),
                BigInteger.valueOf(model.getPublishedRevision()),
                model.getModelName(), model.getProviderCode(), model.getApiHost(),
                RagGenerationProfile.ApiMode.fromValue(model.getApiMode()), model.getCredentialKey(),
                model.getMaxInputTokens(), maxOutputTokens),
            new RagExecutionPolicy(BigInteger.valueOf(model.getMaxInputTokens()), maxOutputTokens,
                Math.toIntExact(properties.getRequestTotalTimeout().toSeconds()),
                toolGrants.isEmpty() ? null : BigInteger.valueOf(reservation.unlimited()
                    ? Math.multiplyExact(Math.addExact(reservation.estimatedInputTokens(),
                        model.getMaxOutputTokens().longValue()), 4L) : reservation.reservedTokens())), scope, toolGrants);
    }

    private String clientRequestId(ChatSendBo command, String requestId) {
        String value = command.getClientRequestId();
        return StringUtils.isBlank(value) || value.length() < 6 ? requestId : value;
    }

    /** 为系统提示和知识片段预留输入窗口，防止检索上下文挤占模型输出。 */
    private int effectiveContextBudget(ModelVo model, long messageInputTokens) {
        long available = model.getMaxInputTokens().longValue() - messageInputTokens - RAG_PROMPT_OVERHEAD_TOKENS;
        if (available < 1) {
            throw new ServiceException("当前对话历史不足以容纳知识库上下文，请新建会话后重试");
        }
        return Math.toIntExact(Math.min(available, ragProperties.getContextBudgetTokens()));
    }
    /** 有限额度下将输出上限收紧到本轮实际可用预算。 */
    private int effectiveMaxOutputTokens(ModelVo model, ChatTokenReservation reservation) {
        if (reservation.unlimited()) {
            return model.getMaxOutputTokens();
        }
        long outputBudget = reservation.reservedTokens() - reservation.estimatedInputTokens();
        return Math.toIntExact(Math.min(model.getMaxOutputTokens(), outputBudget));
    }

    /** UTF-8 字节数作为跨供应商保守估算，真实计费仍以模型返回用量为准。 */
    private long estimateInputTokens(List<PythonChatMessage> history, String currentContent) {
        long estimatedTokens = utf8Length(currentContent) + MESSAGE_PROTOCOL_OVERHEAD_TOKENS;
        for (PythonChatMessage message : history) {
            estimatedTokens = Math.addExact(estimatedTokens,
                utf8Length(message.content()) + MESSAGE_PROTOCOL_OVERHEAD_TOKENS);
        }
        return estimatedTokens;
    }

    /** 超出输入窗口时从最旧历史开始裁剪，当前用户消息不允许被静默截断。 */
    private List<PythonChatMessage> fitHistoryToInputLimit(List<PythonChatMessage> history, String currentContent,
                                                           int maxInputTokens) {
        if (estimateInputTokens(List.of(), currentContent) > maxInputTokens) {
            throw new ServiceException("当前消息超过模型最大输入Token，请缩短后重试");
        }
        List<PythonChatMessage> fittedHistory = new ArrayList<>(history);
        while (!fittedHistory.isEmpty() && estimateInputTokens(fittedHistory, currentContent) > maxInputTokens) {
            fittedHistory.remove(0);
        }
        return fittedHistory;
    }

    private long utf8Length(String content) {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 一个对话轮次包含用户和助手两条消息，同时受服务端绝对上限保护。 */
    private int historyLimit(ModelVo model) {
        int configuredTurns = model.getHistoryMaxTurns() == null ? 1 : model.getHistoryMaxTurns();
        return Math.min(Math.multiplyExact(configuredTurns, 2), properties.getMaxHistoryMessages());
    }

    private PythonChatUsage readUsage(JsonNode value) {
        return new PythonChatUsage(value.path("inputTokens").asLong(0L), value.path("outputTokens").asLong(0L),
            value.path("totalTokens").asLong(0L));
    }

    private PythonChatUsage toChatUsage(RagGenerationUsage usage) {
        if (usage == null) {
            return new PythonChatUsage(0L, 0L, 0L);
        }
        try {
            return new PythonChatUsage(longValue(usage.inputTokens()), longValue(usage.outputTokens()),
                longValue(usage.totalTokens()));
        } catch (ArithmeticException error) {
            throw new ServiceException("AI执行服务返回的Token数量超出系统范围");
        }
    }

    private long longValue(BigInteger value) {
        return value == null ? 0L : value.longValueExact();
    }
    private long totalTokens(PythonChatUsage usage) {
        long total = tokenValue(usage.totalTokens());
        return total > 0 ? total : Math.addExact(tokenValue(usage.inputTokens()), tokenValue(usage.outputTokens()));
    }
    private long tokenValue(Long value) {
        return value == null ? 0L : Math.max(value, 0L);
    }

    /** 使用共享 RAG 契约解析事件，拒绝字段缺失或枚举越界的响应。 */
    private <T> T readRag(JsonNode value, Class<T> type) {
        try {
            return ragCodec.read(value.toString().getBytes(StandardCharsets.UTF_8), type);
        } catch (Exception error) {
            throw new ServiceException("AI执行服务返回了无效RAG响应");
        }
    }

    private void requireRequestId(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new ServiceException("AI执行服务响应请求标识不一致");
        }
    }

    private List<BrowserCitationEvent> browserCitations(Long messageId, List<Citation> citations) {
        return citations.stream().map(item -> browserCitation(messageId, item)).toList();
    }

    private BrowserCitationEvent browserCitation(Long messageId, Citation citation) {
        return new BrowserCitationEvent(messageId, citation.citationId(), citation.location().title(),
            citation.location().titlePath(), citation.location().locator(), citation.excerpt());
    }

    /** 补偿失败不能覆盖已经确定的主调用错误，异常留给额度审计和服务日志定位。 */
    private void releaseReservation(ChatTokenReservation reservation, String errorCode) {
        try {
            chatTokenService.release(reservation, errorCode);
        } catch (Exception compensationError) {
            log.error("对话额度释放失败：requestId={}, errorCode={}",
                reservation.requestId(), errorCode, compensationError);
        }
    }

    private void releaseAndRecord(ChatTokenReservation reservation, Long userId, String requestId,
                                  ChatModelDecision decision, String errorCode, String errorMessage,
                                  PythonChatUsage usage) {
        try {
            if (usage == null || usage.inputTokens() == null || usage.outputTokens() == null) {
                settlementService.releaseAndRecord(
                    reservation, userId, requestId, decision, errorCode, errorMessage);
            } else {
                settlementService.releaseAndRecord(reservation, userId, requestId, decision, errorCode,
                    errorMessage, tokenValue(usage.inputTokens()), tokenValue(usage.outputTokens()));
            }
        } catch (Exception compensationError) {
            log.error("对话失败终态补偿失败：requestId={}, userId={}, errorCode={}",
                requestId, userId, errorCode, compensationError);
        }
    }

    private void releaseAndRecordRag(ChatTokenReservation generationReservation,
                                     RagQueryUsageReservation queryReservation,
                                     Long userId, String requestId, ChatModelDecision decision,
                                     String errorCode, String errorMessage,
                                     List<ExecutionUsageItem> executionUsage) {
        try {
            settlementService.releaseAndRecordRag(
                generationReservation, queryReservation, userId, requestId,
                decision, errorCode, errorMessage, executionUsage);
        } catch (Exception compensationError) {
            log.error("对话失败终态补偿失败：requestId={}, userId={}, errorCode={}",
                requestId, userId, errorCode, compensationError);
        }
    }

    private void persistFailure(Long userId, ChatSendBo command, String requestId,
                                ChatModelDecision decision, String errorCode, String errorMessage) {
        try {
            persistenceService.recordFailedAssistantTurn(
                userId, command.getSessionId(), requestId, decision, errorCode, errorMessage);
        } catch (Exception compensationError) {
            log.error("对话失败审计写入失败：requestId={}, userId={}, sessionId={}, errorCode={}",
                requestId, userId, command.getSessionId(), errorCode, compensationError);
        }
    }

    /** 浏览器断开后由完成回调取消任务，无需用发送异常覆盖当前执行终态。 */
    private void send(SseEmitter emitter, String event, Object body) {
        try {
            emitter.send(SseEmitter.event().name(event).data(body));
        } catch (IOException ignored) {
            // 客户端连接已经关闭，后台任务会通过取消信号结束。
        }
    }

    private String errorCode(Exception error) {
        if (error instanceof PythonExecutionException pythonError) {
            return pythonError.errorCode;
        }
        if (error instanceof KnowledgeArtifactAccessException artifactError) {
            return artifactError.errorCode().value();
        }
        return error instanceof ServiceException ? "VALIDATION" : "INTERNAL";
    }

    private String userMessage(Exception error) {
        if (error instanceof PythonExecutionException pythonError) {
            return pythonError.userMessage;
        }
        if (error instanceof KnowledgeArtifactAccessException artifactError) {
            return pythonFailure(artifactError.errorCode().value()).userMessage;
        }
        if (error instanceof ServiceException) {
            return error.getMessage();
        }
        return "对话暂时不可用，请稍后重试";
    }

    /** 将 Python 稳定错误码映射为用户可操作的中文提示，不透传供应商原始响应。 */
    private PythonExecutionException pythonFailure(String errorCode) {
        return switch (errorCode) {
            case "MODEL_UNAVAILABLE" -> new PythonExecutionException(errorCode, "所选模型当前不可用，请检查模型配置");
            case "TOOL_REJECTED" -> new PythonExecutionException(errorCode, "MCP 工具权限已失效或工具配置发生变化，请联系管理员重新授权或同步工具");
            case "TOOL_CREDENTIAL_MISSING" -> new PythonExecutionException(errorCode, "MCP 服务凭证未配置，请管理员在 Python 的 AI_PROVIDER_CREDENTIALS 中配置服务所用的凭证别名");
            case "TOOL_TIMEOUT" -> new PythonExecutionException(errorCode, "MCP 服务连接或工具调用超时，请管理员检查服务地址、网络与超时配置");
            case "AUTHORIZATION" -> new PythonExecutionException(
                errorCode, "AI执行服务认证失败，请联系管理员检查内部服务签名配置");
            case "PROVIDER_AUTHORIZATION" -> new PythonExecutionException(errorCode, "模型供应商凭证无效或无权调用该模型");
            case "RATE_LIMITED" -> new PythonExecutionException(errorCode, "模型服务繁忙，请稍后重试");
            case "TIMEOUT" -> new PythonExecutionException(errorCode, "模型响应超时，请稍后重试");
            case "CANCELLED" -> new PythonExecutionException(errorCode, "对话已取消");
            case "KNOWLEDGE_NOT_READY", "INDEX_MISMATCH", "VECTOR_UNAVAILABLE" ->
                new PythonExecutionException(errorCode, "知识库索引尚未就绪，请稍后重试");
            case "EMBEDDING_UNAVAILABLE" -> new PythonExecutionException(errorCode, "知识库向量模型暂不可用");
            case "RERANK_UNAVAILABLE" -> new PythonExecutionException(errorCode, "知识库重排序模型暂不可用");
            case "SCOPE_EXPIRED" -> new PythonExecutionException(errorCode, "知识库检索授权已过期，请重新提问");
            case "CITATION_INVALID" -> new PythonExecutionException(errorCode, "回答引用校验失败，请重新提问");
            case "BUDGET_EXCEEDED" -> new PythonExecutionException(errorCode, "本次模型预算不足，请缩短问题后重试");
            case "VALIDATION" -> new PythonExecutionException(errorCode, "模型执行请求无效，请检查模型配置");
            default -> new PythonExecutionException("INTERNAL", "模型服务暂时不可用，请稍后重试");
        };
    }

    /** 单次 SSE 执行的内存状态，仅在对应后台任务线程内使用。 */
    private static final class StreamResult {
        private final StringBuilder content = new StringBuilder();
        private final Map<String, Citation> candidateCitations = new LinkedHashMap<>();
        private PythonChatUsage usage;
        private boolean completed;
        private boolean rag;
        private List<Citation> citations = List.of();
        private List<ExecutionUsageItem> executionUsage = List.of();
    }

    private record BrowserContentEvent(String content) { }
    private record BrowserCitationEvent(Long messageId, String citationId, String title,
                                        List<String> titlePath, Object locator, String excerpt) { }
    private record BrowserDoneEvent(boolean done, Long messageId, Long modelId, String modelName,
                                    String modelMode, String routeReasonCode) { }
    private record BrowserErrorEvent(String code, String error) { }

    /** 携带稳定错误码的内部异常，浏览器和额度审计共用同一错误语义。 */
    private static final class PythonExecutionException extends RuntimeException {
        private final String errorCode;
        private final String userMessage;
        private PythonExecutionException(String errorCode, String userMessage) {
            super(userMessage);
            this.errorCode = errorCode;
            this.userMessage = userMessage;
        }
    }
}
