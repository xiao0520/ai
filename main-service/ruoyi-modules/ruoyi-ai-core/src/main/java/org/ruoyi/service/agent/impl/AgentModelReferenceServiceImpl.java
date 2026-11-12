package org.ruoyi.service.agent.impl;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.mapper.agent.AgentMapper;
import org.ruoyi.mapper.agent.AgentModelMapper;
import org.ruoyi.service.agent.IAgentModelReferenceService;
import org.springframework.stereotype.Service;

import java.util.Collection;

/**
 * Agent 模型绑定引用保护服务。
 *
 * 模型删除前同时检查默认模型和可选模型关系，防止留下无法执行的 Agent 配置。
 */
@Service
@RequiredArgsConstructor
public class AgentModelReferenceServiceImpl implements IAgentModelReferenceService {

    private final AgentMapper agentMapper;
    private final AgentModelMapper agentModelMapper;

    @Override
    public void requireModelsUnreferenced(Collection<Long> modelIds) {
        if (modelIds == null || modelIds.isEmpty()) {
            return;
        }
        // 默认模型与可选模型均属于删除保护范围，任一引用存在时都禁止删除模型。
        long defaultModelReferences = agentMapper.countByModelIds(modelIds);
        long optionalModelReferences = agentModelMapper.countByModelIds(modelIds);
        if (defaultModelReferences > 0 || optionalModelReferences > 0) {
            throw new ServiceException("待删除模型仍被智能体引用，请先解除智能体的模型绑定");
        }
    }
}
