package org.ruoyi.service.agent;

import org.ruoyi.domain.vo.agent.AgentSkillBindingVo;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Agent 对 Skill 的绑定查询服务。
 */
public interface IAgentSkillReferenceService {

    /**
     * 查询指定 Skill 的 Agent 绑定明细。
     *
     * @param skillName Skill 唯一名称
     * @return Agent 绑定明细列表
     */
    List<AgentSkillBindingVo> listBindings(String skillName);

    /**
     * 批量统计各 Skill 的 Agent 绑定数量。
     *
     * @param skillNames Skill 唯一名称集合
     * @return Skill 名称与绑定数量的映射；未绑定的 Skill 不一定包含在结果中
     */
    Map<String, Long> countBindings(Collection<String> skillNames);
}