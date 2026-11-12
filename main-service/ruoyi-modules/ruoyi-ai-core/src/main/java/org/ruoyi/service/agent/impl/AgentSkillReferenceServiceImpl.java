package org.ruoyi.service.agent.impl;

import lombok.RequiredArgsConstructor;
import org.ruoyi.domain.entity.agent.Agent;
import org.ruoyi.domain.entity.agent.AgentSkill;
import org.ruoyi.domain.vo.agent.AgentSkillBindingVo;
import org.ruoyi.mapper.agent.AgentMapper;
import org.ruoyi.mapper.agent.AgentSkillMapper;
import org.ruoyi.service.agent.IAgentSkillReferenceService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agent 与 Skill 绑定查询服务。
 *
 * 集中提供跨领域引用明细和计数，Skill 管理服务无需直接访问 Agent 持久层。
 */
@Service
@RequiredArgsConstructor
public class AgentSkillReferenceServiceImpl implements IAgentSkillReferenceService {
    private final AgentSkillMapper agentSkillMapper;
    private final AgentMapper agentMapper;

    @Override
    public List<AgentSkillBindingVo> listBindings(String skillName) {
        // 先查询关联记录，避免无绑定时继续查询智能体主表。
        List<AgentSkill> bindings = agentSkillMapper.selectBySkillName(skillName);
        if (bindings.isEmpty()) {
            return List.of();
        }

        // 批量加载智能体信息，避免为每条绑定记录发起一次查询。
        List<Long> agentIds = bindings.stream().map(AgentSkill::getAgentId).distinct().toList();
        Map<Long, Agent> agents = toAgentMap(agentMapper.selectByIds(agentIds));

        // 已删除的智能体不返回绑定信息，防止页面展示无效关联。
        return bindings.stream().map(binding -> toVo(binding, agents.get(binding.getAgentId())))
            .filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public Map<String, Long> countBindings(Collection<String> skillNames) {
        if (skillNames == null || skillNames.isEmpty()) {
            return Collections.emptyMap();
        }
        // 按 Skill 名称聚合绑定数量，供删除前的关联校验一次性使用。
        return agentSkillMapper.selectBySkillNames(skillNames)
            .stream().collect(Collectors.groupingBy(AgentSkill::getSkillName, Collectors.counting()));
    }

    /** 将智能体列表转换为按主键检索的映射，并明确处理异常重复数据。 */
    private Map<Long, Agent> toAgentMap(List<Agent> agents) {
        return agents.stream().collect(Collectors.toMap(Agent::getId, Function.identity(),
            (left, right) -> left, LinkedHashMap::new));
    }

    /** 转换绑定记录为视图对象，智能体已删除时返回空并在上游过滤。 */
    private AgentSkillBindingVo toVo(AgentSkill binding, Agent agent) {
        if (agent == null) {
            return null;
        }
        AgentSkillBindingVo vo = new AgentSkillBindingVo();
        vo.setAgentId(agent.getId());
        vo.setAgentCode(agent.getAgentCode());
        vo.setAgentName(agent.getAgentName());
        vo.setStatus(binding.getStatus());
        vo.setConfigJson(binding.getConfigJson());
        return vo;
    }
}
