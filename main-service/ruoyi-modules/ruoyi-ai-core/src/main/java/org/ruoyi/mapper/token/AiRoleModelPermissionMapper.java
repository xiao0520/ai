package org.ruoyi.mapper.token;

import org.apache.ibatis.annotations.Param;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.token.AiRoleModelPermission;

import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * 角色模型权限 Mapper。
 */
public interface AiRoleModelPermissionMapper extends BaseMapperPlus<AiRoleModelPermission, AiRoleModelPermission> {

    /** 按角色查询模型授权，并保持角色与模型主键的稳定顺序。 */
    List<AiRoleModelPermission> selectByRoleIds(@Param("roleIds") Collection<Long> roleIds);

    /** 查询当前时刻生效的角色模型主键。 */
    List<Long> selectEffectiveModelIds(@Param("roleIds") Collection<Long> roleIds,
                                       @Param("status") String status,
                                       @Param("now") Date now);

    /** 清除指定角色已有的模型授权。 */
    int deleteByRoleId(@Param("roleId") Long roleId);
}
