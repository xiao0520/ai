package org.ruoyi.mapper.agent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.agent.AgentSkill;

import java.util.Collection;
import java.util.List;

@Mapper
public interface AgentSkillMapper extends BaseMapperPlus<AgentSkill, AgentSkill> {

    /** 按 Skill 编码查询绑定记录。 */
    List<AgentSkill> selectBySkillName(@Param("skillName") String skillName);

    /** 按多个 Skill 编码查询绑定记录。 */
    List<AgentSkill> selectBySkillNames(@Param("skillNames") Collection<String> skillNames);

    int deleteByAgentIds(@Param("agentIds") Collection<Long> agentIds);

    List<String> selectEnabledSkillNamesByAgentId(@Param("agentId") Long agentId, @Param("status") String status);
}
