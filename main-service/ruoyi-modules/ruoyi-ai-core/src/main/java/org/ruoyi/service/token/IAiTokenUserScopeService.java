package org.ruoyi.service.token;

import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.system.domain.vo.SysUserVo;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Token用户范围查询与数据权限适配Service接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface IAiTokenUserScopeService {

    /**
     * 分页查询用户列表
     *
     * @param userId    用户ID
     * @param deptId    部门ID
     * @param userName  用户名
     * @param nickName  昵称
     * @param pageQuery 分页参数
     * @return 用户分页列表
     */
    TableDataInfo<SysUserVo> queryPage(Long userId, Long deptId, String userName,
                                        String nickName, PageQuery pageQuery);

    /**
     * 查询全部用户列表
     *
     * @param userId   用户ID
     * @param deptId   部门ID
     * @param userName 用户名
     * @return 用户列表
     */
    List<SysUserVo> queryAll(Long userId, Long deptId, String userName);

    /**
     * 根据用户ID集合查询用户映射
     *
     * @param userIds 用户ID集合
     * @return 用户ID与用户对象的映射
     */
    Map<Long, SysUserVo> mapByIds(Collection<Long> userIds);

    /**
     * 根据用户ID查询用户（带数据权限校验）
     *
     * @param userId 用户ID
     * @return 用户信息
     */
    SysUserVo requireUser(Long userId);
}
