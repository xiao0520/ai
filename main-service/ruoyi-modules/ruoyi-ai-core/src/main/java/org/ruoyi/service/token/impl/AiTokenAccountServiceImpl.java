package org.ruoyi.service.token.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.constant.AiTokenConstants;
import org.ruoyi.domain.bo.token.AiTokenAccountCommandBo;
import org.ruoyi.domain.bo.token.AiTokenAccountQueryBo;
import org.ruoyi.domain.bo.token.AiTokenQuotaLogQueryBo;
import org.ruoyi.domain.entity.token.AiTokenQuotaLog;
import org.ruoyi.domain.entity.token.AiUserTokenAccount;
import org.ruoyi.system.domain.vo.SysUserVo;
import org.ruoyi.domain.vo.token.AiTokenQuotaLogVo;
import org.ruoyi.domain.vo.token.AiTokenUserOptionVo;
import org.ruoyi.domain.vo.token.AiUserTokenAccountVo;
import org.ruoyi.mapper.token.AiTokenQuotaLogMapper;
import org.ruoyi.mapper.token.AiUserTokenAccountMapper;
import org.ruoyi.service.token.IAiTokenAccountTransactionService;
import org.ruoyi.service.token.IAiTokenUserScopeService;
import org.ruoyi.service.token.IAiTokenAccountService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户Token额度账户管理Service实现类
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Service
@RequiredArgsConstructor
public class AiTokenAccountServiceImpl implements IAiTokenAccountService {

    private final AiUserTokenAccountMapper accountMapper;
    private final AiTokenQuotaLogMapper logMapper;
    private final IAiTokenUserScopeService userScopeService;
    private final IAiTokenAccountTransactionService transactionService;

    @Override
    public TableDataInfo<AiUserTokenAccountVo> queryPage(AiTokenAccountQueryBo query, PageQuery pageQuery) {
        // 先按用户、部门和账号条件限定数据范围，额度表不能绕过用户权限直接分页。
        List<SysUserVo> allowedUsers = userScopeService.queryAll(
            query.getUserId(), query.getDeptId(), query.getUserName());
        if (CollUtil.isEmpty(allowedUsers)) {
            return new TableDataInfo<>(Collections.emptyList(), 0);
        }
        Map<Long, SysUserVo> userMap = allowedUsers.stream()
            .collect(java.util.stream.Collectors.toMap(SysUserVo::getUserId, user -> user, (left, right) -> left));

        // 账户筛选、排序和分页统一由 Mapper XML 承担，避免 Service 持有持久化查询细节。
        Page<AiUserTokenAccount> page = pageQuery.build();
        page.setRecords(accountMapper.selectAccountPage(page, userMap.keySet(), query));
        List<AiUserTokenAccountVo> rows = page.getRecords().stream()
            .map(account -> toAccountVo(account, userMap.get(account.getUserId())))
            .toList();
        return new TableDataInfo<>(rows, page.getTotal());
    }

    @Override
    public TableDataInfo<AiTokenUserOptionVo> queryUserOptions(AiTokenAccountQueryBo query, PageQuery pageQuery) {
        // 先按数据范围查询可选用户，再标记其中已配置额度的用户。
        TableDataInfo<SysUserVo> users = userScopeService.queryPage(query.getUserId(), query.getDeptId(),
            query.getUserName(), query.getNickName(), pageQuery);
        List<Long> userIds = users.getRows().stream().map(SysUserVo::getUserId).toList();
        Set<Long> configured = new LinkedHashSet<>();
        if (CollUtil.isNotEmpty(userIds)) {
            // 只查询当前页用户的主键，避免加载完整额度账户后在业务层取字段。
            configured.addAll(accountMapper.selectConfiguredUserIds(userIds, AiTokenConstants.CYCLE_MANUAL));
        }
        List<AiTokenUserOptionVo> rows = users.getRows().stream().map(user -> {
            AiTokenUserOptionVo vo = new AiTokenUserOptionVo();
            BeanUtil.copyProperties(user, vo);
            vo.setConfigured(configured.contains(user.getUserId()));
            return vo;
        }).toList();
        return new TableDataInfo<>(rows, users.getTotal());
    }

    @Override
    public AiUserTokenAccountVo queryByUserId(Long userId) {
        // 先验证目标用户处于当前操作人的数据范围内。
        SysUserVo user = userScopeService.requireUser(userId);
        AiUserTokenAccount account = accountMapper.selectManualByUserId(userId, AiTokenConstants.CYCLE_MANUAL);
        return account == null ? null : toAccountVo(account, user);
    }

    // 以下额度操作统一先校验数据范围，再委托事务服务在同一事务内完成账户变更与流水记录。

    @Override
    public boolean initialGrant(Long userId, AiTokenAccountCommandBo command) {
        userScopeService.requireUser(userId);
        return transactionService.initialGrant(userId, command);
    }

    @Override
    public boolean appendGrant(Long userId, AiTokenAccountCommandBo command) {
        userScopeService.requireUser(userId);
        return transactionService.appendGrant(userId, command);
    }

    @Override
    public boolean setRemaining(Long userId, AiTokenAccountCommandBo command) {
        userScopeService.requireUser(userId);
        return transactionService.setRemaining(userId, command);
    }

    @Override
    public boolean setWarningThreshold(Long userId, AiTokenAccountCommandBo command) {
        userScopeService.requireUser(userId);
        return transactionService.setWarningThreshold(userId, command);
    }

    @Override
    public boolean setUnlimited(Long userId, AiTokenAccountCommandBo command) {
        userScopeService.requireUser(userId);
        return transactionService.setUnlimited(userId, command);
    }

    @Override
    public boolean changeStatus(Long userId, AiTokenAccountCommandBo command) {
        userScopeService.requireUser(userId);
        return transactionService.changeStatus(userId, command);
    }

    @Override
    public TableDataInfo<AiTokenQuotaLogVo> queryLogPage(AiTokenQuotaLogQueryBo query, PageQuery pageQuery) {
        // 流水同样以可访问用户范围为前提，不能仅凭操作人或时间条件查询。
        List<SysUserVo> allowedUsers = userScopeService.queryAll(
            query.getUserId(), query.getDeptId(), query.getUserName());
        if (CollUtil.isEmpty(allowedUsers)) {
            return new TableDataInfo<>(Collections.emptyList(), 0);
        }
        Map<Long, SysUserVo> userMap = allowedUsers.stream()
            .collect(java.util.stream.Collectors.toMap(SysUserVo::getUserId, user -> user, (left, right) -> left));
        // 日志筛选和时间排序交由 Mapper XML，服务层仅补充关联用户展示信息。
        Page<AiTokenQuotaLog> page = pageQuery.build();
        page.setRecords(logMapper.selectLogPage(page, userMap.keySet(), query));

        // 操作人可能不在被查询用户集合内，需要额外补齐其展示名称。
        Set<Long> relatedIds = new LinkedHashSet<>(userMap.keySet());
        page.getRecords().forEach(log -> relatedIds.add(log.getOperatorId()));
        Map<Long, SysUserVo> relatedUsers = userScopeService.mapByIds(relatedIds);
        List<AiTokenQuotaLogVo> rows = page.getRecords().stream().map(log -> {
            AiTokenQuotaLogVo vo = new AiTokenQuotaLogVo();
            BeanUtil.copyProperties(log, vo);
            fillUser(vo, userMap.get(log.getUserId()));
            SysUserVo operator = relatedUsers.get(log.getOperatorId());
            if (operator != null) {
                vo.setOperatorName(operator.getNickName());
            }
            return vo;
        }).toList();
        return new TableDataInfo<>(rows, page.getTotal());
    }

    /** 组装账户视图，补充用户展示信息并计算剩余额度与使用率。 */
    private AiUserTokenAccountVo toAccountVo(AiUserTokenAccount account, SysUserVo user) {
        AiUserTokenAccountVo vo = new AiUserTokenAccountVo();
        BeanUtil.copyProperties(account, vo);
        if (user != null) {
            vo.setDeptId(user.getDeptId());
            vo.setUserName(user.getUserName());
            vo.setNickName(user.getNickName());
        }

        // 剩余额度排除已使用与预占部分，使用率按总额度百分比保留两位小数。
        long remaining = account.getTotalTokens() - account.getUsedTokens() - account.getReservedTokens();
        vo.setRemainingTokens(remaining);
        if (account.getTotalTokens() == 0) {
            vo.setUsageRate(BigDecimal.ZERO);
        } else {
            long consumed = account.getUsedTokens() + account.getReservedTokens();
            vo.setUsageRate(BigDecimal.valueOf(consumed)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(account.getTotalTokens()), 2, RoundingMode.HALF_UP));
        }
        return vo;
    }

    /** 回填流水的用户展示信息，用户缺失时保持主键原样。 */
    private void fillUser(AiTokenQuotaLogVo vo, SysUserVo user) {
        if (user == null) {
            return;
        }
        vo.setDeptId(user.getDeptId());
        vo.setUserName(user.getUserName());
        vo.setNickName(user.getNickName());
    }
}
