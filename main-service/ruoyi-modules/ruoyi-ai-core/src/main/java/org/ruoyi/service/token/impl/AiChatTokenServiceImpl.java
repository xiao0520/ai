package org.ruoyi.service.token.impl;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.constant.AiTokenConstants;
import org.ruoyi.domain.dto.token.ChatTokenReservation;
import org.ruoyi.domain.entity.token.AiTokenUsage;
import org.ruoyi.domain.entity.token.AiUserTokenAccount;
import org.ruoyi.domain.vo.model.ModelVo;
import org.ruoyi.mapper.token.AiTokenUsageMapper;
import org.ruoyi.mapper.token.AiUserTokenAccountMapper;
import org.ruoyi.service.token.IAiChatTokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * 聊天模型调用额度服务。
 *
 * 在同一数据库事务中完成额度预占、实际用量结算和失败释放，避免并发请求透支账户
 */
@Service
@RequiredArgsConstructor
public class AiChatTokenServiceImpl implements IAiChatTokenService {

    private static final String USAGE_SOURCE_CHAT = "CHAT";

    private final AiUserTokenAccountMapper accountMapper;
    private final AiTokenUsageMapper usageMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatTokenReservation reserve(String requestId, Long userId, Long sessionId, Long agentId, ModelVo model,
                                        long estimatedInputTokens) {
        return reserve(requestId, userId, sessionId, agentId, model, estimatedInputTokens, 1);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatTokenReservation reserve(String requestId, Long userId, Long sessionId, Long agentId, ModelVo model,
                                        long estimatedInputTokens, int modelTurns) {
        if (modelTurns < 1 || modelTurns > 4) {
            throw new ServiceException("模型调用轮数超出允许范围");
        }
        // 查询并校验当前用户唯一的手工额度账户，未配置额度不得调用付费模型。
        AiUserTokenAccount account = accountMapper.selectManualByUserId(userId, AiTokenConstants.CYCLE_MANUAL);
        if (account == null) {
            throw new ServiceException("当前用户未配置Token额度，请联系管理员分配额度");
        }
        if (!AiTokenConstants.STATUS_NORMAL.equals(account.getStatus())) {
            throw new ServiceException("当前Token额度账户不可用，请联系管理员处理");
        }

        boolean unlimited = AiTokenConstants.YES.equals(account.getUnlimited());
        // 上下文窗口是输入与输出的容量上限，不是单次实际消耗。有限账户按模型输出上限预占，
        // 但允许使用余额发起较短回答，并由执行层将本轮输出上限同步收紧到实际预占值。
        long normalizedInputTokens = Math.max(estimatedInputTokens, 1L);
        long reserveTokens = unlimited ? 0L : resolveReserveTokens(account, model, normalizedInputTokens, modelTurns);
        if (!unlimited && accountMapper.reserveTokens(account.getId(), reserveTokens) != 1) {
            throw new ServiceException("Token剩余额度已用完或被其他对话占用，无法发起本次对话");
        }
        return new ChatTokenReservation(requestId, account.getId(), userId, sessionId, agentId, model.getId(),
            model.getProviderCode(), model.getModelName(), model.getPublishedRevision(),
            normalizedInputTokens, unlimited ? 0L : reserveTokens, unlimited, new Date());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void settle(ChatTokenReservation reservation, long inputTokens, long outputTokens) {
        settleOutcome(reservation, inputTokens, outputTokens, AiTokenConstants.USAGE_SUCCESS, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void settleFailed(ChatTokenReservation reservation, long inputTokens, long outputTokens, String errorCode) {
        settleOutcome(reservation, inputTokens, outputTokens, AiTokenConstants.USAGE_FAILED, errorCode);
    }

    private void settleOutcome(ChatTokenReservation reservation, long inputTokens, long outputTokens,
                               String status, String errorCode) {
        long normalizedInput = nonNegative(inputTokens);
        long normalizedOutput = nonNegative(outputTokens);
        long actualTokens = safeAdd(normalizedInput, normalizedOutput);
        long chargedTokens = reservation.unlimited()
            ? actualTokens : Math.min(actualTokens, reservation.reservedTokens());

        // 有限额度账户在同一 UPDATE 内释放预占并增加已用额度，保证并发下不会出现负数余额。
        if (!reservation.unlimited()
            && accountMapper.settleTokens(reservation.accountId(), reservation.reservedTokens(),
            chargedTokens) != 1) {
            throw new ServiceException("Token额度结算失败，请联系管理员核对账户");
        }
        insertUsage(reservation, normalizedInput, normalizedOutput, actualTokens, chargedTokens,
            status, errorCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void release(ChatTokenReservation reservation, String errorCode) {
        // 失败调用不扣减额度，但必须释放已经预占的额度，避免账户被异常请求长期占满。
        if (!reservation.unlimited() && reservation.reservedTokens() > 0
            && accountMapper.releaseTokens(reservation.accountId(), reservation.reservedTokens()) != 1) {
            throw new ServiceException("Token预占额度释放失败，请联系管理员核对账户");
        }
        insertUsage(reservation, 0L, 0L, 0L, 0L, AiTokenConstants.USAGE_FAILED, errorCode);
    }

    /** 保存不可变用量明细，成功与失败均保留调用审计。 */
    private void insertUsage(ChatTokenReservation reservation, long inputTokens, long outputTokens, long actualTokens,
                             long chargedTokens, String status, String errorCode) {
        AiTokenUsage usage = new AiTokenUsage();
        usage.setRequestId(reservation.requestId());
        usage.setCallId(reservation.requestId());
        usage.setUserId(reservation.userId());
        usage.setAccountId(reservation.accountId());
        usage.setSessionId(reservation.sessionId());
        usage.setAgentId(reservation.agentId());
        usage.setModelId(reservation.modelId());
        usage.setProviderCode(reservation.providerCode());
        usage.setModelName(reservation.modelName());
        usage.setModelRevision(reservation.modelRevision());
        usage.setInputTokens(inputTokens);
        usage.setOutputTokens(outputTokens);
        usage.setActualTokens(actualTokens);
        usage.setChargedTokens(chargedTokens);
        usage.setReservedTokens(reservation.reservedTokens());
        usage.setUsageSource(USAGE_SOURCE_CHAT);
        usage.setStatus(status);
        usage.setErrorCode(errorCode);
        usage.setStartTime(reservation.startTime());
        usage.setFinishTime(new Date());
        if (usageMapper.insert(usage) != 1) {
            throw new ServiceException("Token用量明细保存失败");
        }
    }

    /** 预占不超过当前可用额度，让小于模型输出上限的余额仍可用于短回答。 */
    private long resolveReserveTokens(AiUserTokenAccount account, ModelVo model, long estimatedInputTokens,
                                      int modelTurns) {
        if (model.getMaxOutputTokens() == null || model.getMaxOutputTokens() <= 0) {
            throw new ServiceException("模型未配置有效的最大输出Token，无法发起对话");
        }
        long availableTokens = availableTokens(account);
        if (availableTokens <= 0) {
            throw new ServiceException("Token剩余额度已用完，无法发起本次对话");
        }
        if (availableTokens <= estimatedInputTokens) {
            throw new ServiceException("Token剩余额度不足以覆盖本次输入，请缩短消息或联系管理员增加额度");
        }
        long requestedTokens = Math.multiplyExact(
            safeAdd(estimatedInputTokens, model.getMaxOutputTokens().longValue()), modelTurns);
        return Math.min(requestedTokens, availableTokens);
    }

    /** 数据库旧记录的空值按零处理，不允许异常数据扩大可用额度。 */
    private long availableTokens(AiUserTokenAccount account) {
        long totalTokens = nonNegative(account.getTotalTokens());
        long usedTokens = nonNegative(account.getUsedTokens());
        long reservedTokens = nonNegative(account.getReservedTokens());
        return Math.max(totalTokens - Math.min(totalTokens, usedTokens) - Math.min(totalTokens, reservedTokens), 0L);
    }

    private long nonNegative(Long tokens) {
        return tokens == null ? 0L : nonNegative(tokens.longValue());
    }

    private long nonNegative(long tokens) {
        return Math.max(tokens, 0L);
    }

    private long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException error) {
            throw new ServiceException("模型返回的Token数量超出系统支持范围");
        }
    }
}
