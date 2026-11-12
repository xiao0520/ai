package org.ruoyi.mapper.agent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.agent.AgentKnowledge;

import java.util.Collection;
import java.util.List;

@Mapper
public interface AgentKnowledgeMapper extends BaseMapperPlus<AgentKnowledge, AgentKnowledge> {

    int deleteByAgentIds(@Param("agentIds") Collection<Long> agentIds);

    List<Long> selectEnabledKnowledgeIdsByAgentId(@Param("agentId") Long agentId, @Param("status") String status);
}
