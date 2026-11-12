package org.ruoyi.mapper.mcp;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.mcp.McpRoleToolPermission;

import java.util.Collection;
import java.util.Date;
import java.util.List;

@Mapper
public interface McpRoleToolPermissionMapper extends BaseMapperPlus<McpRoleToolPermission, McpRoleToolPermission> {

    /** 统计指定工具仍存在的角色授权数。 */
    long countByToolIds(@Param("toolIds") Collection<Long> toolIds);

    List<McpRoleToolPermission> selectByRoleIds(@Param("roleIds") Collection<Long> roleIds);

    List<McpRoleToolPermission> selectByRoleId(@Param("roleId") Long roleId);

    List<Long> selectEffectiveToolIds(@Param("roleIds") Collection<Long> roleIds,
                                      @Param("status") String status, @Param("effectiveAt") Date effectiveAt);

    int deleteByRoleId(@Param("roleId") Long roleId);
}
