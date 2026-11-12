package org.ruoyi.service.token;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.constant.AiTokenConstants;
import org.ruoyi.domain.dto.contract.ContractTokenReservation;
import org.ruoyi.domain.dto.knowledge.rag.ExecutionUsageItem;
import org.ruoyi.domain.dto.knowledge.rag.IndexSnapshot;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeRetrievalSettings;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeScope;
import org.ruoyi.domain.dto.knowledge.rag.ProfileRef;
import org.ruoyi.domain.dto.knowledge.rag.RagExecutionScope;
import org.ruoyi.domain.dto.knowledge.rag.TokenMeasurement;
import org.ruoyi.domain.dto.knowledge.rag.VisionProfile;
import org.ruoyi.domain.entity.token.AiTokenUsage;
import org.ruoyi.domain.entity.token.AiUserTokenAccount;
import org.ruoyi.domain.vo.model.ModelVo;
import org.ruoyi.enums.ModelType;
import org.ruoyi.mapper.token.AiTokenUsageMapper;
import org.ruoyi.mapper.token.AiUserTokenAccountMapper;
import org.ruoyi.service.model.IModelProfileSnapshotService;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 合同执行适配现有 Token 账户和用量表，由合同任务短事务统一提交。 */
@Service
@RequiredArgsConstructor
public class ContractReviewTokenService {
    private final AiUserTokenAccountMapper accounts;
    private final AiTokenUsageMapper usages;
    private final IModelProfileSnapshotService models;

    /** 先原子预占账户余额，再允许 Python 执行；不限额账户同样下发有限执行预算。 */
    public ContractTokenReservation reserve(String requestId, Long userId, Long agentId,
                                            ModelVo model, RagExecutionScope scope) {
        return reserve(requestId, userId, agentId, model, scope, "REVIEW", null);
    }

    /** 提取仅预占一次生成；OCR 使用同一账户预算并单独记录调用。 */
    public ContractTokenReservation reserve(String requestId, Long userId, Long agentId,
                                            ModelVo model, RagExecutionScope scope,
                                            String operation, VisionProfile vision) {
        AiUserTokenAccount account = accounts.selectManualByUserId(userId, AiTokenConstants.CYCLE_MANUAL);
        if (account == null || !AiTokenConstants.STATUS_NORMAL.equals(account.getStatus())) {
            throw new ServiceException("当前用户未配置可用Token额度，无法执行合同审查");
        }
        Set<String> allowed = new HashSet<>();
        allowed.add("CHAT:" + model.getId() + ":" + model.getPublishedRevision());
        long requested = ("EXTRACT".equals(operation) ? 1L : 2L)
            * (model.getMaxInputTokens().longValue() + model.getMaxOutputTokens());
        if (vision != null) {
            allowed.add(key("VISION", vision.profile()));
            // 扫描页数量执行前未知，以有限上界预占；执行面仍逐次检查余额。
            requested = Math.min(Integer.MAX_VALUE, requested + 20L * vision.maxInputTokens());
        }
        if (scope != null) {
        for (IndexSnapshot index : scope.indexSnapshots()) {
            allowed.add(key("EMBEDDING", index.embedding().profile()));
            requested += index.embedding().maxInputTokens();
            if (index.multimodalEmbedding() != null) {
                allowed.add(key("EMBEDDING", index.multimodalEmbedding().profile()));
                requested += index.multimodalEmbedding().maxInputTokens();
            }
        }
        for (KnowledgeScope knowledge : scope.knowledgeScopes()) {
            KnowledgeRetrievalSettings settings = knowledge.retrievalSettings();
            if (settings != null && settings.rerankEnabled() && settings.rerankProfile() != null) {
                allowed.add(key("RERANK", settings.rerankProfile().profile()));
                requested += (settings.rerankProfile().maxDocumentTokens().longValue() + 1024)
                    * settings.rerankInputCount();
            }
        }
        }
        boolean unlimited = AiTokenConstants.YES.equals(account.getUnlimited());
        long total = value(account.getTotalTokens());
        long available = Math.max(0, total - Math.min(total, value(account.getUsedTokens()))
            - Math.min(total, value(account.getReservedTokens())));
        int approved = Math.toIntExact(Math.min(Integer.MAX_VALUE, unlimited ? requested
            : Math.min(requested, available)));
        if (approved < 1 || (!unlimited && accounts.reserveTokens(account.getId(), approved) != 1)) {
            throw new ServiceException("Token剩余额度不足或已被其他任务占用，无法执行合同审查");
        }
        return new ContractTokenReservation(requestId, account.getId(), userId, agentId,
            approved, unlimited, new Date(), Set.copyOf(allowed));
    }

    /** 用量只允许来自本次授权模型，不能用未知计量或零占位绕过扣费。 */
    public long validate(ContractTokenReservation reservation, List<ExecutionUsageItem> items) {
        Set<String> callIds = new HashSet<>();
        long total = 0;
        for (ExecutionUsageItem item : items) {
            if (!reservation.requestId().equals(item.attemptId()) || !callIds.add(item.callId())
                || !reservation.allowedProfiles().contains(key(item.stage().value(), item.profile()))
                || !(item.measurement() instanceof TokenMeasurement tokens)) {
                throw new ServiceException("合同模型用量与当前授权执行不一致");
            }
            total = Math.addExact(total, tokens.inputTokens().longValue() + tokens.outputTokens());
        }
        return total;
    }

    /** 调用方已锁定运行任务；明细、账户释放和任务终态必须同事务提交。 */
    public void settle(ContractTokenReservation reservation, List<ExecutionUsageItem> items,
                       String terminalStatus, String errorCode) {
        long charge = validate(reservation, items);
        long reserved = reservation.approvedTokens();
        // tokenizer 与供应商可能有偏差；补占差额失败时保留任务与快照，不能截断实际用量。
        if (!reservation.unlimited() && charge > reserved) {
            if (accounts.reserveTokens(reservation.accountId(), charge - reserved) != 1) {
                throw new ServiceException("模型实际计量超出预算且余额不足，请联系管理员核对合同用量");
            }
            reserved = charge;
        }
        for (ExecutionUsageItem item : items) {
            String callId = DigestUtil.sha256Hex(reservation.requestId() + ":" + item.callId());
            if (usages.selectByCallId(callId) != null) {
                throw new ServiceException("合同模型调用已经结算，拒绝重复扣费");
            }
            var model = models.requireRevision(Long.valueOf(item.profile().id()), item.profile().revision(),
                category(item, reservation));
            TokenMeasurement tokens = (TokenMeasurement) item.measurement();
            long amount = tokens.inputTokens().longValue() + tokens.outputTokens();
            AiTokenUsage row = new AiTokenUsage();
            row.setId(IdWorker.getId());
            row.setRequestId(reservation.requestId());
            row.setCallId(callId);
            row.setUserId(reservation.userId());
            row.setAccountId(reservation.accountId());
            row.setAgentId(reservation.agentId());
            row.setModelId(model.id());
            row.setProviderCode(model.providerCode());
            row.setModelName(model.modelName());
            row.setModelRevision(model.revision());
            row.setInputTokens(tokens.inputTokens().longValue());
            row.setOutputTokens(tokens.outputTokens().longValue());
            row.setCacheTokens(0L);
            row.setReasoningTokens(0L);
            row.setActualTokens(amount);
            row.setChargedTokens(amount);
            row.setReservedTokens(0L);
            row.setUsageSource("CONTRACT_" + item.stage().value());
            row.setUsageUnit("TOKENS");
            row.setUsageQuantity(amount);
            row.setMeasurementQuality(tokens.quality().value());
            row.setStatus(terminalStatus);
            row.setErrorCode(errorCode);
            row.setStartTime(reservation.startTime());
            row.setFinishTime(new Date());
            if (usages.insert(row) != 1) {
                throw new ServiceException("合同模型用量保存失败");
            }
        }
        if (!reservation.unlimited()
            && accounts.settleTokens(reservation.accountId(), reserved, charge) != 1) {
            throw new ServiceException("合同Token额度结算失败，请联系管理员核对账户");
        }
    }

    private String category(ExecutionUsageItem item, ContractTokenReservation reservation) {
        return switch (item.stage()) {
            case V_CHAT -> ModelType.CHAT.getKey();
            case V_EMBEDDING -> ModelType.VECTOR.getKey();
            case V_RERANK -> ModelType.RERANK.getKey();
            case V_VISION -> reservation.allowedProfiles().contains(key("CHAT", item.profile()))
                ? ModelType.CHAT.getKey() : ModelType.IMAGE.getKey();
            default -> throw new ServiceException("合同审查返回了未授权的模型阶段");
        };
    }

    private String key(String stage, ProfileRef profile) {
        return stage + ":" + profile.id() + ":" + profile.revision();
    }

    private long value(Long tokens) {
        return tokens == null ? 0 : Math.max(tokens, 0);
    }
}
