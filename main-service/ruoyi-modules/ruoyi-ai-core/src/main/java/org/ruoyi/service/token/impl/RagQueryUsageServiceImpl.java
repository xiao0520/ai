package org.ruoyi.service.token.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.constant.AiTokenConstants;
import org.ruoyi.domain.dto.knowledge.rag.ExecutionUsageItem;
import org.ruoyi.domain.dto.knowledge.rag.IndexSnapshot;
import org.ruoyi.domain.dto.knowledge.rag.RagExecutionScope;
import org.ruoyi.domain.dto.knowledge.rag.RequestMeasurement;
import org.ruoyi.domain.dto.knowledge.rag.TokenMeasurement;
import org.ruoyi.domain.dto.knowledge.rag.UnknownMeasurement;
import org.ruoyi.domain.dto.token.RagQueryUsageReservation;
import org.ruoyi.domain.entity.token.AiTokenUsage;
import org.ruoyi.domain.entity.token.AiUserTokenAccount;
import org.ruoyi.enums.ModelType;
import org.ruoyi.mapper.token.AiTokenUsageMapper;
import org.ruoyi.mapper.token.AiUserTokenAccountMapper;
import org.ruoyi.service.model.IModelProfileSnapshotService;
import org.ruoyi.service.model.IModelProfileSnapshotService.ModelProfileSnapshot;
import org.ruoyi.service.token.IRagQueryUsageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * RAG 查询阶段模型用量服务。
 *
 * <p>以用户额度账户为边界，按本轮可能调用的查询模型预占 Token，并按调用标识幂等登记
 * Embedding、问题改写和 Rerank 等阶段的真实计量。</p>
 */
@Service
@RequiredArgsConstructor
public class RagQueryUsageServiceImpl implements IRagQueryUsageService {
    private final AiUserTokenAccountMapper accounts;
    private final AiTokenUsageMapper usage;
    private final IModelProfileSnapshotService models;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RagQueryUsageReservation reserve(String requestId, Long userId, Long sessionId, Long agentId,
                                            RagExecutionScope scope, long estimatedQuestionTokens) {
        AiUserTokenAccount account = accounts.selectManualByUserId(userId, AiTokenConstants.CYCLE_MANUAL);
        if (account == null || !AiTokenConstants.STATUS_NORMAL.equals(account.getStatus())) {
            throw new ServiceException("当前用户未配置可用Token额度，无法执行知识库检索");
        }
        long perCall = Math.max(estimatedQuestionTokens, 1L);
        long reserved = 0;
        for (IndexSnapshot index : scope.indexSnapshots()) {
            if (perCall > index.embedding().maxInputTokens()) {
                throw new ServiceException("当前问题超过向量模型最大输入Token，请缩短后重试");
            }
            reserved = Math.addExact(reserved, perCall);
            if (index.multimodalEmbedding() != null) {
                if (perCall > index.multimodalEmbedding().maxInputTokens()) {
                    throw new ServiceException("当前问题超过多模态向量模型最大输入Token，请缩短后重试");
                }
                reserved = Math.addExact(reserved, perCall);
            }
        }
        // 执行面按索引分别调用向量模型，重排也需要为候选文本预占额度。
        reserved = Math.addExact(reserved, rerankBudget(scope, perCall));
        boolean unlimited = AiTokenConstants.YES.equals(account.getUnlimited());
        if (!unlimited && accounts.reserveTokens(account.getId(), reserved) != 1) {
            throw new ServiceException("Token剩余额度不足，无法执行知识库检索");
        }
        return new RagQueryUsageReservation(requestId, account.getId(), userId, sessionId, agentId,
            unlimited ? 0L : reserved, unlimited, new Date());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void settle(RagQueryUsageReservation reservation, List<ExecutionUsageItem> items,
                       String terminalStatus, String errorCode) {
        long charge = 0L;
        Date now = new Date();
        for (ExecutionUsageItem item : items == null ? List.<ExecutionUsageItem>of() : items) {
            if (item.stage() == ExecutionUsageItem.Stage.V_CHAT) {
                continue;
            }
            if (!reservation.requestId().equals(item.attemptId())) {
                throw new ServiceException("RAG查询用量不属于当前请求");
            }
            String callId = DigestUtil.sha256Hex(item.callId());
            if (usage.selectByCallId(callId) != null) {
                continue;
            }
            ModelProfileSnapshot model = models.requireRevision(Long.valueOf(item.profile().id()),
                item.profile().revision(), category(item.stage()));
            Measurement measurement = measurement(item);
            charge = Math.addExact(charge, measurement.tokens());
            AiTokenUsage row = new AiTokenUsage();
            row.setId(IdWorker.getId());
            row.setRequestId(reservation.requestId());
            row.setCallId(callId);
            row.setUserId(reservation.userId());
            row.setAccountId(reservation.accountId());
            row.setSessionId(reservation.sessionId());
            row.setAgentId(reservation.agentId());
            row.setModelId(model.id());
            row.setProviderCode(model.providerCode());
            row.setModelName(model.modelName());
            row.setModelRevision(model.revision());
            row.setInputTokens(measurement.inputTokens());
            row.setOutputTokens(measurement.outputTokens());
            row.setCacheTokens(0L);
            row.setReasoningTokens(0L);
            row.setActualTokens(measurement.tokens());
            row.setChargedTokens(measurement.tokens());
            row.setReservedTokens(0L);
            row.setUsageSource("RAG_QUERY_" + item.stage().value());
            row.setUsageUnit(measurement.unit());
            row.setUsageQuantity(measurement.quantity());
            row.setMeasurementQuality(measurement.quality());
            row.setStatus(terminalStatus);
            row.setErrorCode(errorCode);
            row.setStartTime(reservation.startTime());
            row.setFinishTime(now);
            if (usage.insert(row) != 1) {
                throw new ServiceException("RAG查询用量明细保存失败");
            }
        }
        if (!reservation.unlimited()) {
            if (charge > reservation.reservedTokens()) {
                throw new ServiceException("RAG查询实际Token用量超过预占预算");
            }
            if (accounts.settleTokens(reservation.accountId(), reservation.reservedTokens(), charge) != 1) {
                throw new ServiceException("RAG查询Token额度结算失败");
            }
        }
    }

    private Measurement measurement(ExecutionUsageItem item) {
        if (item.stage() == ExecutionUsageItem.Stage.V_RERANK
            && !(item.measurement() instanceof TokenMeasurement)) {
            throw new ServiceException("重排缺少实际或文本估算Token，请检查AI执行服务版本");
        }
        if (item.measurement() instanceof TokenMeasurement tokens) {
            long input = tokens.inputTokens().longValue();
            long output = tokens.outputTokens().longValue();
            return new Measurement("TOKENS", Math.addExact(input, output), tokens.quality().value(), input, output,
                Math.addExact(input, output));
        }
        if (item.measurement() instanceof RequestMeasurement requests) {
            return new Measurement(requests.unit().value(), requests.quantity().longValue(),
                requests.quality().value(), 0L, 0L, 0L);
        }
        if (item.measurement() instanceof UnknownMeasurement) {
            return new Measurement("UNKNOWN", null, "UNKNOWN", 0L, 0L, 0L);
        }
        throw new ServiceException("RAG查询返回了未知计量类型");
    }

    /** 以已授权候选数量和文档上限估算，实际结算只扣回传的实际或估算 Token。 */
    private long rerankBudget(RagExecutionScope scope, long queryTokens) {
        if (scope.retrievalSnapshot() == null
            || !"DENSE_BM25_RRF_RERANK".equals(scope.retrievalSnapshot().mode().value())) {
            return 0;
        }
        long total = 0;
        for (var knowledge : scope.knowledgeScopes()) {
            var settings = knowledge.retrievalSettings();
            if (settings != null && settings.rerankEnabled() && settings.rerankProfile() != null) {
                total = Math.addExact(total, Math.multiplyExact(settings.rerankInputCount().longValue(),
                    queryTokens + settings.rerankProfile().maxDocumentTokens()));
            }
        }
        return total;
    }

    private String category(ExecutionUsageItem.Stage stage) {
        return switch (stage) {
            case V_EMBEDDING -> ModelType.VECTOR.getKey();
            case V_VISION -> ModelType.IMAGE.getKey();
            case V_RERANK -> ModelType.RERANK.getKey();
            case V_CHAT -> ModelType.CHAT.getKey();
        };
    }

    private record Measurement(String unit, Long quantity, String quality,
                               long inputTokens, long outputTokens, long tokens) { }
}
