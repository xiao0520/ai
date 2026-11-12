package org.ruoyi.service.token.impl;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.constant.AiTokenConstants;
import org.ruoyi.constant.AiSystemConfigKeys;
import org.ruoyi.domain.bo.token.AiTokenAccountCommandBo;
import org.ruoyi.domain.entity.token.AiTokenQuotaLog;
import org.ruoyi.domain.entity.token.AiUserTokenAccount;
import org.ruoyi.mapper.token.AiTokenQuotaLogMapper;
import org.ruoyi.mapper.token.AiUserTokenAccountMapper;
import org.ruoyi.service.token.IAiTokenAccountTransactionService;
import org.ruoyi.support.AiRuntimeConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * 用户Token额度账户事务管理Service实现类
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Service
@RequiredArgsConstructor
public class AiTokenAccountTransactionServiceImpl implements IAiTokenAccountTransactionService {

    private final AiUserTokenAccountMapper accountMapper;
    private final AiTokenQuotaLogMapper logMapper;
    private final AiRuntimeConfig runtimeConfig;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean initialGrant(Long userId, AiTokenAccountCommandBo command) {
        // 不限额账户允许以零额度初始化；限额账户则必须显式给出正数额度。
        long tokens;
        if (Boolean.TRUE.equals(command.getUnlimited())) {
            tokens = command.getTokens() == null ? 0L : command.getTokens();
            if (tokens < 0) {
                throw new ServiceException("Token数量不能小于0");
            }
        } else {
            tokens = requirePositiveTokens(command);
        }
        AiUserTokenAccount existing = selectManualAccount(userId);
        if (existing != null) {
            throw new ServiceException("该员工已配置Token额度，请使用追加额度或设置剩余额度");
        }

        // 新账户与首笔流水必须在同一事务内落库，避免出现无流水的额度账户。
        AiUserTokenAccount account = new AiUserTokenAccount();
        account.setUserId(userId);
        account.setCycleType(AiTokenConstants.CYCLE_MANUAL);
        account.setCycleNo(AiTokenConstants.CYCLE_MANUAL);
        account.setTotalTokens(tokens);
        account.setUsedTokens(0L);
        account.setReservedTokens(0L);
        account.setUnlimited(Boolean.TRUE.equals(command.getUnlimited())
            ? AiTokenConstants.YES : AiTokenConstants.NO);
        account.setWarningThreshold(command.getWarningThreshold() == null
            ? defaultWarningThreshold() : command.getWarningThreshold());
        account.setCycleStart(new Date());
        account.setStatus(AiTokenConstants.STATUS_NORMAL);
        account.setVersion(0);
        account.setRemark(command.getReason());
        if (accountMapper.insert(account) != 1) {
            throw new ServiceException("Token额度首次分配失败");
        }
        writeLog(account, AiTokenConstants.INITIAL_GRANT, tokens, 0L, 0L, command.getReason());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean appendGrant(Long userId, AiTokenAccountCommandBo command) {
        // 仅允许追加正数额度，避免用追加操作绕过剩余额度的计算规则。
        long tokens = requirePositiveTokens(command);
        AiUserTokenAccount account = requireMutableAccount(userId);
        long beforeTotal = account.getTotalTokens();
        long beforeRemaining = remaining(account);

        // 写入账户后记录前后额度，确保管理员操作具备可追溯流水。
        account.setTotalTokens(safeAdd(beforeTotal, tokens));
        updateAccount(account);
        writeLog(account, AiTokenConstants.APPEND_GRANT, tokens, beforeTotal, beforeRemaining, command.getReason());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean setRemaining(Long userId, AiTokenAccountCommandBo command) {
        // 目标剩余量不能为负，已使用和已预占额度始终需要保留。
        if (command.getTokens() == null || command.getTokens() < 0) {
            throw new ServiceException("目标剩余额度不能为空且不能小于0");
        }
        AiUserTokenAccount account = requireMutableAccount(userId);
        long beforeTotal = account.getTotalTokens();
        long beforeRemaining = remaining(account);
        long committed = safeAdd(account.getUsedTokens(), account.getReservedTokens());

        // 通过已承诺额度反推总额度，避免修改余额时覆盖已使用或已预占 Token。
        account.setTotalTokens(safeAdd(committed, command.getTokens()));
        long change = account.getTotalTokens() - beforeTotal;
        updateAccount(account);
        writeLog(account, AiTokenConstants.SET_REMAINING, change, beforeTotal, beforeRemaining, command.getReason());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean setWarningThreshold(Long userId, AiTokenAccountCommandBo command) {
        // 告警阈值是百分比协议，必须限定在有效区间内。
        if (command.getWarningThreshold() == null
            || command.getWarningThreshold() < AiTokenConstants.WARNING_THRESHOLD_MIN
            || command.getWarningThreshold() > AiTokenConstants.WARNING_THRESHOLD_MAX) {
            throw new ServiceException("告警阈值必须在1到100之间");
        }
        AiUserTokenAccount account = requireMutableAccount(userId);
        long beforeTotal = account.getTotalTokens();
        long beforeRemaining = remaining(account);

        // 阈值不改变账户余额，但仍记录独立流水以保留调整轨迹。
        account.setWarningThreshold(command.getWarningThreshold());
        updateAccount(account);
        writeLog(account, AiTokenConstants.UPDATE_WARNING_THRESHOLD, 0L,
            beforeTotal, beforeRemaining, command.getReason());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean setUnlimited(Long userId, AiTokenAccountCommandBo command) {
        // 开关状态必须由调用方明确指定，避免空值被误解为关闭不限额。
        if (command.getUnlimited() == null) {
            throw new ServiceException("不限额状态不能为空");
        }
        AiUserTokenAccount account = requireMutableAccount(userId);
        long beforeTotal = account.getTotalTokens();
        long beforeRemaining = remaining(account);

        // 不限额标记与变更流水原子保存，不调整历史总额度。
        account.setUnlimited(command.getUnlimited() ? AiTokenConstants.YES : AiTokenConstants.NO);
        updateAccount(account);
        String operation = command.getUnlimited()
            ? AiTokenConstants.ENABLE_UNLIMITED : AiTokenConstants.DISABLE_UNLIMITED;
        writeLog(account, operation, 0L, beforeTotal, beforeRemaining, command.getReason());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean changeStatus(Long userId, AiTokenAccountCommandBo command) {
        // 管理端只允许冻结或恢复，关闭状态只能通过受控的账户生命周期流程处理。
        if (!AiTokenConstants.STATUS_NORMAL.equals(command.getStatus())
            && !AiTokenConstants.STATUS_FROZEN.equals(command.getStatus())) {
            throw new ServiceException("额度账户状态只能设置为正常或冻结");
        }
        AiUserTokenAccount account = requireMutableAccount(userId);
        long beforeTotal = account.getTotalTokens();
        long beforeRemaining = remaining(account);

        // 状态变化不会修改额度，但需要写入流水以便审计账户不可用的原因。
        account.setStatus(command.getStatus());
        updateAccount(account);
        String operation = AiTokenConstants.STATUS_FROZEN.equals(command.getStatus())
            ? AiTokenConstants.FREEZE : AiTokenConstants.UNFREEZE;
        writeLog(account, operation, 0L, beforeTotal, beforeRemaining, command.getReason());
        return true;
    }

    /** 查询并校验可被管理端修改的手工额度账户。 */
    private AiUserTokenAccount requireMutableAccount(Long userId) {
        AiUserTokenAccount account = selectManualAccount(userId);
        if (account == null) {
            throw new ServiceException("该员工尚未配置Token额度");
        }
        if (AiTokenConstants.STATUS_CLOSED.equals(account.getStatus())) {
            throw new ServiceException("已关闭的Token额度账户不能修改");
        }
        return account;
    }

    /** 额度管理固定操作手工账期，不与自动周期账户混用。 */
    private AiUserTokenAccount selectManualAccount(Long userId) {
        return accountMapper.selectManualByUserId(userId, AiTokenConstants.CYCLE_MANUAL);
    }

    /** 检查更新结果，防止并发调整静默覆盖。 */
    private void updateAccount(AiUserTokenAccount account) {
        if (accountMapper.updateById(account) != 1) {
            throw new ServiceException("Token额度已被其他操作修改，请刷新后重试");
        }
    }

    /** 持久化账户调整的审计快照。 */
    private void writeLog(AiUserTokenAccount account, String operationType, Long changeTokens,
                          Long beforeTotal, Long beforeRemaining, String reason) {
        AiTokenQuotaLog log = new AiTokenQuotaLog();
        log.setAccountId(account.getId());
        log.setUserId(account.getUserId());
        log.setOperationType(operationType);
        log.setChangeTokens(changeTokens);
        log.setBeforeTotal(beforeTotal);
        log.setAfterTotal(account.getTotalTokens());
        log.setBeforeRemaining(beforeRemaining);
        log.setAfterRemaining(remaining(account));
        log.setOperatorId(LoginHelper.getUserId());
        log.setReason(StringUtils.isBlank(reason) ? operationType : reason);
        if (logMapper.insert(log) != 1) {
            throw new ServiceException("Token额度调整流水保存失败");
        }
    }

    /** 追加和限额初始化共用的正数额度约束。 */
    private long requirePositiveTokens(AiTokenAccountCommandBo command) {
        if (command.getTokens() == null || command.getTokens() <= 0) {
            throw new ServiceException("Token数量必须大于0");
        }
        return command.getTokens();
    }

    /** 剩余额度需排除已使用和模型调用预占的 Token。 */
    private long remaining(AiUserTokenAccount account) {
        return account.getTotalTokens() - account.getUsedTokens() - account.getReservedTokens();
    }

    /** 使用精确加法阻止超大额度溢出为负数。 */
    private long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw new ServiceException("Token额度超过系统支持的最大值");
        }
    }

    /** 获取新建 Token 账户的默认告警阈值。 */
    private int defaultWarningThreshold() {
        return runtimeConfig.getPositiveInt(AiSystemConfigKeys.TOKEN_DEFAULT_WARNING_THRESHOLD,
            AiTokenConstants.DEFAULT_WARNING_THRESHOLD, AiTokenConstants.WARNING_THRESHOLD_MAX);
    }
}
