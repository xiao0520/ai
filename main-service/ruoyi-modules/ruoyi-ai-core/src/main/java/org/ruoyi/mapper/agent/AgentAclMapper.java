package org.ruoyi.mapper.agent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.agent.AgentAcl;

import java.util.Collection;
import java.util.List;

@Mapper
public interface AgentAclMapper extends BaseMapperPlus<AgentAcl, AgentAcl> {

    int deleteByAgentIds(@Param("agentIds") Collection<Long> agentIds);

    List<AgentAcl> selectByAgentId(@Param("agentId") Long agentId);

    /** 按当前用户的角色、部门及时间窗查询可访问的智能体主键。 */
    List<Long> selectEffectiveAgentIds(@Param("status") String status, @Param("effectiveAt") java.util.Date effectiveAt,
                                       @Param("roleIds") Collection<Long> roleIds, @Param("deptIds") Collection<Long> deptIds,
                                       @Param("roleSubjectType") String roleSubjectType,
                                       @Param("deptSubjectType") String deptSubjectType);
}
