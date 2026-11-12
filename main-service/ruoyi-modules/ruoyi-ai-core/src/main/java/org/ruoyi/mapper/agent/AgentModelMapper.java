package org.ruoyi.mapper.agent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.agent.AgentModel;

import java.util.Collection;
import java.util.List;

@Mapper
public interface AgentModelMapper extends BaseMapperPlus<AgentModel, AgentModel> {

    /** 统计可选模型被智能体绑定的数量。 */
    long countByModelIds(@Param("modelIds") Collection<Long> modelIds);

    int deleteByAgentIds(@Param("agentIds") Collection<Long> agentIds);

    List<Long> selectEnabledModelIdsByAgentId(@Param("agentId") Long agentId, @Param("status") String status);
}
