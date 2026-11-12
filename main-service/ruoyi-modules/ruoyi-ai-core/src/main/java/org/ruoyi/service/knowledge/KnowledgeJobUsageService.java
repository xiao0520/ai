package org.ruoyi.service.knowledge;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.constant.AiSystemConfigKeys;
import org.ruoyi.constant.AiTokenConstants;
import org.ruoyi.domain.dto.knowledge.rag.ExecutionUsageItem;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeExecutionBudget;
import org.ruoyi.domain.dto.knowledge.rag.RequestMeasurement;
import org.ruoyi.domain.dto.knowledge.rag.TokenMeasurement;
import org.ruoyi.domain.dto.knowledge.rag.UnknownMeasurement;
import org.ruoyi.domain.entity.knowledge.KnowledgeParseJob;
import org.ruoyi.domain.entity.token.AiTokenUsage;
import org.ruoyi.domain.entity.token.AiUserTokenAccount;
import org.ruoyi.enums.ModelType;
import org.ruoyi.mapper.knowledge.KnowledgeKafkaJobMapper;
import org.ruoyi.mapper.token.AiTokenUsageMapper;
import org.ruoyi.mapper.token.AiUserTokenAccountMapper;
import org.ruoyi.service.model.IModelProfileSnapshotService;
import org.ruoyi.service.model.IModelProfileSnapshotService.ModelProfileSnapshot;
import org.ruoyi.support.AiRuntimeConfig;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 知识库任务模型用量服务。
 *
 * <p>按 attempt 预占 Token 硬预算，按调用标识幂等登记模型用量，并在任务终态一次性结算。
 * 请求次数等非 Token 计量只用于审计，不折算或伪造 Token。</p>
 */
@Service
@RequiredArgsConstructor
public class KnowledgeJobUsageService {
    private static final String BUDGET_NONE = "NONE";
    private static final String BUDGET_RESERVED = "RESERVED";
    private static final String BUDGET_SETTLING = "SETTLING";
    private static final String BUDGET_SETTLED = "SETTLED";

    private final AiUserTokenAccountMapper accounts;
    private final AiTokenUsageMapper usage;
    private final KnowledgeKafkaJobMapper jobs;
    private final IModelProfileSnapshotService models;
    private final AiRuntimeConfig runtimeConfig;

    /**
     * 为当前任务 attempt 预占模型调用预算。
     *
     * <p>维护任务不使用模型；入库任务按创建人账户预占硬上限。</p>
     *
     * @param job 待派发的知识库任务
     * @return Worker 执行预算；维护任务返回 {@code null}
     */
    public KnowledgeExecutionBudget reserve(KnowledgeParseJob job) {
        if (!AiDomainConstants.KNOWLEDGE_PARSE_JOB_TYPE_INGESTION.equals(job.getJobType())) {
            job.setBudgetStatus(BUDGET_NONE);
            job.setBudgetAccountId(null);
            job.setBudgetReservedTokens(0L);
            job.setExecutionBudgetTokens(null);
            return null;
        }
        if (job.getCreateBy() == null) {
            throw new ServiceException("知识库任务缺少创建人，无法分配模型预算");
        }
        AiUserTokenAccount account = accounts.selectManualByUserId(job.getCreateBy(), AiTokenConstants.CYCLE_MANUAL);
        if (account == null || !AiTokenConstants.STATUS_NORMAL.equals(account.getStatus())) {
            throw new ServiceException("任务创建人未配置可用Token额度");
        }
        long configured = runtimeConfig.getRequiredPositiveInt(AiSystemConfigKeys.KNOWLEDGE_JOB_MAX_TOKEN_BUDGET,
            Integer.MAX_VALUE);
        boolean unlimited = AiTokenConstants.YES.equals(account.getUnlimited());
        long approved = unlimited ? configured : Math.min(configured, available(account));
        if (approved < 1) {
            throw new ServiceException("Token剩余额度不足，无法执行知识库入库任务");
        }
        if (!unlimited && accounts.reserveTokens(account.getId(), approved) != 1) {
            throw new ServiceException("Token额度已被其他任务占用，请稍后重试");
        }
        job.setBudgetAccountId(account.getId());
        job.setBudgetReservedTokens(unlimited ? 0L : approved);
        job.setExecutionBudgetTokens(Math.toIntExact(approved));
        job.setBudgetStatus(BUDGET_RESERVED);
        return new KnowledgeExecutionBudget(Math.toIntExact(approved));
    }

    /**
     * 在任务终态幂等结算当前 attempt 的模型用量。
     *
     * @param job 当前知识库任务
     * @param items Worker 返回的模型调用计量
     * @param terminalStatus 任务终态
     * @param errorCode 失败原因编码，成功时可为空
     */
    public void settle(KnowledgeParseJob job, List<ExecutionUsageItem> items, String terminalStatus,
                       String errorCode) {
        if (!BUDGET_RESERVED.equals(job.getBudgetStatus())) {
            return;
        }
        if (jobs.markBudgetStatus(job.getId(), job.getAttemptId(), BUDGET_RESERVED, BUDGET_SETTLING) != 1) {
            return;
        }
        long charge = 0L;
        Date now = new Date();
        for (ExecutionUsageItem item : items == null ? List.<ExecutionUsageItem>of() : items) {
            requireAttempt(job, item);
            String callId = DigestUtil.sha256Hex(item.callId());
            if (usage.selectByCallId(callId) != null) {
                continue;
            }
            ModelProfileSnapshot model = models.requireRevision(Long.valueOf(item.profile().id()),
                item.profile().revision(), category(item.stage()));
            Measurement measurement = measurement(item);
            charge = safeAdd(charge, measurement.tokens());
            AiTokenUsage row = new AiTokenUsage();
            row.setId(IdWorker.getId());
            row.setRequestId(job.getRequestId());
            row.setCallId(callId);
            row.setUserId(job.getCreateBy());
            row.setAccountId(job.getBudgetAccountId());
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
            row.setUsageSource(source(item));
            row.setUsageUnit(measurement.unit());
            row.setUsageQuantity(measurement.quantity());
            row.setMeasurementQuality(measurement.quality());
            row.setStatus(terminalStatus);
            row.setErrorCode(errorCode);
            row.setStartTime(job.getStartedAt() == null ? now : job.getStartedAt());
            row.setFinishTime(now);
            if (usage.insert(row) != 1) {
                throw new ServiceException("知识库模型用量明细保存失败");
            }
        }
        long approved = job.getExecutionBudgetTokens() == null ? 0L : job.getExecutionBudgetTokens();
        if (charge > approved) {
            throw new ServiceException("知识库任务实际Token用量超过已批准预算");
        }
        long reserved = job.getBudgetReservedTokens() == null ? 0L : job.getBudgetReservedTokens();
        long charged = Math.min(charge, reserved);
        if (reserved > 0 && accounts.settleTokens(job.getBudgetAccountId(), reserved, charged) != 1) {
            throw new ServiceException("知识库任务Token额度结算失败");
        }
        if (jobs.markBudgetStatus(job.getId(), job.getAttemptId(), BUDGET_SETTLING, BUDGET_SETTLED) != 1) {
            throw new ServiceException("知识库任务预算状态更新失败");
        }
        job.setBudgetStatus(BUDGET_SETTLED);
    }

    /**
     * 拒绝不属于当前任务 attempt 的模型计量。
     */
    private void requireAttempt(KnowledgeParseJob job, ExecutionUsageItem item) {
        if (!job.getAttemptId().equals(item.attemptId())) {
            throw new ServiceException("模型用量不属于当前执行批次");
        }
    }

    /**
     * 把 Token、请求次数或未知计量标准化为本地用量字段。
     */
    private Measurement measurement(ExecutionUsageItem item) {
        if (item.measurement() instanceof TokenMeasurement tokens) {
            long input = tokens.inputTokens().longValue();
            long output = tokens.outputTokens().longValue();
            long total = safeAdd(input, output);
            return new Measurement("TOKENS", total, tokens.quality().value(), input, output, total);
        }
        if (item.measurement() instanceof RequestMeasurement requests) {
            return new Measurement(requests.unit().value(), requests.quantity().longValue(),
                requests.quality().value(), 0L, 0L, 0L);
        }
        if (item.measurement() instanceof UnknownMeasurement) {
            return new Measurement("UNKNOWN", null, "UNKNOWN", 0L, 0L, 0L);
        }
        throw new ServiceException("知识库任务返回了未知计量类型");
    }

    /**
     * 根据执行阶段确定应读取的模型分类。
     */
    private String category(ExecutionUsageItem.Stage stage) {
        return switch (stage) {
            case V_EMBEDDING -> ModelType.VECTOR.getKey();
            case V_VISION -> ModelType.IMAGE.getKey();
            case V_RERANK -> ModelType.RERANK.getKey();
            case V_CHAT -> ModelType.CHAT.getKey();
        };
    }

    /**
     * 生成可审计的用量来源编码。
     */
    private String source(ExecutionUsageItem item) {
        String quality = item.measurement() instanceof TokenMeasurement tokens ? tokens.quality().value()
            : item.measurement() instanceof RequestMeasurement request ? request.quality().value() : "UNKNOWN";
        return "KNOWLEDGE_" + item.stage().value() + "_" + quality;
    }

    private record Measurement(String unit, Long quantity, String quality,
                               long inputTokens, long outputTokens, long tokens) { }

    /**
     * 计算扣除已用和已预占后的非负可用额度。
     */
    private long available(AiUserTokenAccount account) {
        long total = nonNegative(account.getTotalTokens());
        return Math.max(total - Math.min(total, nonNegative(account.getUsedTokens()))
            - Math.min(total, nonNegative(account.getReservedTokens())), 0L);
    }

    private long nonNegative(Long value) {
        return value == null ? 0L : Math.max(value, 0L);
    }

    /**
     * 对 Token 求和执行溢出保护。
     */
    private long safeAdd(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException error) { throw new ServiceException("模型Token用量超出系统支持范围"); }
    }
}
