package org.ruoyi.service.token.impl;

import cn.hutool.core.collection.CollUtil;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.service.token.IAiTokenUserScopeService;
import org.ruoyi.system.domain.bo.SysUserBo;
import org.ruoyi.system.domain.vo.SysUserVo;
import org.ruoyi.system.service.ISysUserService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Token用户范围查询与数据权限适配Service实现类
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Service
@RequiredArgsConstructor
public class AiTokenUserScopeServiceImpl implements IAiTokenUserScopeService {

    private final ISysUserService userService;

    @Override
    public TableDataInfo<SysUserVo> queryPage(Long userId, Long deptId, String userName,
                                               String nickName, PageQuery pageQuery) {
        SysUserBo query = createUserQueryBo(userId, deptId, userName, nickName);
        return userService.selectPageUserList(query, pageQuery);
    }

    @Override
    public List<SysUserVo> queryAll(Long userId, Long deptId, String userName) {
        // 复用分页查询的默认页大小作为全量上限，避免一次性加载过多用户。
        TableDataInfo<SysUserVo> page = queryPage(userId, deptId, userName, null,
            new PageQuery(PageQuery.DEFAULT_PAGE_SIZE, PageQuery.DEFAULT_PAGE_NUM));
        return CollUtil.isEmpty(page.getRows()) ? Collections.emptyList() : page.getRows();
    }

    @Override
    public Map<Long, SysUserVo> mapByIds(Collection<Long> userIds) {
        // 去重后批量加载用户，返回按主键索引的映射供展示信息回填。
        if (CollUtil.isEmpty(userIds)) {
            return Collections.emptyMap();
        }
        List<SysUserVo> users = userService.selectUserByIds(userIds.stream().distinct().toList(), null);
        Map<Long, SysUserVo> result = new LinkedHashMap<>();
        for (SysUserVo user : users) {
            result.put(user.getUserId(), user);
        }
        return result;
    }

    @Override
    public SysUserVo requireUser(Long userId) {
        // 先校验当前操作人的数据范围，再确认目标用户真实存在。
        if (userId == null) {
            throw new ServiceException("员工ID不能为空");
        }
        userService.checkUserDataScope(userId);
        SysUserVo user = userService.selectUserById(userId);
        if (user == null) {
            throw new ServiceException("员工不存在或已删除");
        }
        return user;
    }

    /** 适配系统用户服务的查询对象，不包含 Token 业务筛选。 */
    private SysUserBo createUserQueryBo(Long userId, Long deptId, String userName, String nickName) {
        SysUserBo query = new SysUserBo();
        query.setUserId(userId);
        query.setDeptId(deptId);
        query.setUserName(userName);
        query.setNickName(nickName);
        return query;
    }
}
