package org.ruoyi.service.token.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.constant.AiSystemConfigKeys;
import org.ruoyi.domain.bo.token.AiTokenUsageQueryBo;
import org.ruoyi.domain.entity.token.AiTokenUsage;
import org.ruoyi.domain.vo.token.AiTokenStatItemVo;
import org.ruoyi.domain.vo.token.AiTokenStatisticsVo;
import org.ruoyi.domain.vo.token.AiTokenUsageVo;
import org.ruoyi.mapper.token.AiTokenUsageMapper;
import org.ruoyi.service.token.IAiTokenUserScopeService;
import org.ruoyi.service.token.IAiTokenUsageService;
import org.ruoyi.support.AiRuntimeConfig;
import org.ruoyi.system.domain.vo.SysUserVo;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Token用量查询管理Service实现类
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Service
@RequiredArgsConstructor
public class AiTokenUsageServiceImpl implements IAiTokenUsageService {

    private final AiTokenUsageMapper usageMapper;
    private final IAiTokenUserScopeService userScopeService;
    private final AiRuntimeConfig runtimeConfig;

    @Override
    public TableDataInfo<AiTokenUsageVo> queryPage(AiTokenUsageQueryBo query, PageQuery pageQuery) {
        // 用量明细必须先收敛到当前操作人可访问的用户范围。
        Map<Long, SysUserVo> userMap = allowedUsers(query);
        if (userMap.isEmpty()) {
            return new TableDataInfo<>(Collections.emptyList(), 0);
        }

        // 筛选、排序和分页由 Mapper XML 统一维护，避免 Service 承担持久化条件细节。
        Page<AiTokenUsage> page = pageQuery.build();
        page.setRecords(usageMapper.selectUsagePage(page, query, userMap.keySet()));
        List<AiTokenUsageVo> rows = page.getRecords().stream().map(usage -> {
            AiTokenUsageVo vo = new AiTokenUsageVo();
            BeanUtil.copyProperties(usage, vo);
            SysUserVo user = userMap.get(usage.getUserId());
            if (user != null) {
                vo.setDeptId(user.getDeptId());
                vo.setUserName(user.getUserName());
                vo.setNickName(user.getNickName());
            }
            return vo;
        }).toList();
        return new TableDataInfo<>(rows, page.getTotal());
    }

    @Override
    public AiTokenStatisticsVo statistics(AiTokenUsageQueryBo query) {
        // 统计与明细使用同一用户数据范围，避免面板数值泄露不可见用户的信息。
        Map<Long, SysUserVo> userMap = allowedUsers(query);
        if (userMap.isEmpty()) {
            return new AiTokenStatisticsVo();
        }

        // 先查询总调用量与 Token 汇总，空结果按零值对象返回给前端。
        AiTokenStatisticsVo result = usageMapper.selectStatistics(query, userMap.keySet());
        if (result == null) {
            result = new AiTokenStatisticsVo();
        }
        // 模型排行直接由数据库聚合，展示数量受系统参数上限控制。
        result.setModelRanking(usageMapper.selectModelRanking(query, userMap.keySet(), dashboardTopN()));

        // 用户排行仅返回主键，服务层批量补充已校验范围内的用户昵称。
        Map<String, String> userNames = new LinkedHashMap<>();
        userMap.forEach((id, user) -> userNames.put(String.valueOf(id), user.getNickName()));
        List<AiTokenStatItemVo> userRanking = usageMapper.selectUserRanking(query, userMap.keySet(), dashboardTopN());
        userRanking.forEach(item -> item.setName(userNames.getOrDefault(item.getKey(), item.getKey())));
        result.setUserRanking(userRanking);

        // 趋势按数据库日期聚合，避免将原始用量明细加载到内存后二次统计。
        result.setDailyTrend(usageMapper.selectDailyTrend(query, userMap.keySet()));
        return result;
    }

    /** 查询当前操作人可查看的用户范围。 */
    private Map<Long, SysUserVo> allowedUsers(AiTokenUsageQueryBo query) {
        List<SysUserVo> users = userScopeService.queryAll(query.getUserId(), query.getDeptId(), query.getUserName());
        if (CollUtil.isEmpty(users)) {
            return Collections.emptyMap();
        }
        Map<Long, SysUserVo> result = new LinkedHashMap<>();
        users.forEach(user -> result.put(user.getUserId(), user));
        return result;
    }

    /** 获取统计面板排名展示数量。 */
    private int dashboardTopN() {
        return runtimeConfig.getPositiveInt(AiSystemConfigKeys.TOKEN_USAGE_DASHBOARD_TOP_N, 10, 100);
    }
}
