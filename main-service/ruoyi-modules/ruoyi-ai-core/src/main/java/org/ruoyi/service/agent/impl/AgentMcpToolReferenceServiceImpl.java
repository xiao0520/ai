package org.ruoyi.service.agent.impl;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.mapper.mcp.AgentMcpToolMapper;
import org.ruoyi.service.agent.IAgentMcpToolReferenceService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * Agent 与 MCP 工具关联查询服务。
 *
 * 为 MCP 和 Agent 领域提供最小化的跨领域引用查询，避免双方直接依赖彼此的 Mapper
 */
@Service
@RequiredArgsConstructor
public class AgentMcpToolReferenceServiceImpl implements IAgentMcpToolReferenceService {

    private final AgentMcpToolMapper agentToolMapper;

    @Override
    public long countBindings(Collection<Long> toolIds) {
        // 空集合直接返回，避免无意义的数据库查询。
        if (toolIds == null || toolIds.isEmpty()) {
            return 0;
        }
        return agentToolMapper.countByToolIds(toolIds);
    }

    @Override
    public List<Long> listEnabledToolIds(Long agentId) {
        // 仅返回状态正常的工具绑定，已停用的工具不再参与运行时调用。
        if (agentId == null) {
            return List.of();
        }
        return agentToolMapper.selectEnabledToolIdsByAgentId(agentId, SystemConstants.NORMAL);
    }
}
