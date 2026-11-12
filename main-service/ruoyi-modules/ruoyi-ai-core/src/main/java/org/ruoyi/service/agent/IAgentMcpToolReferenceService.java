package org.ruoyi.service.agent;

import java.util.Collection;
import java.util.List;

/**
 * Agent 与 MCP 工具关联的跨领域查询能力。
 */
public interface IAgentMcpToolReferenceService {

    /**
     * 统计指定 MCP 工具被 Agent 关联的总次数。
     *
     * @param toolIds MCP 工具主键集合
     * @return 关联记录总数；集合为空时返回 {@code 0}
     */
    long countBindings(Collection<Long> toolIds);

    /**
     * 查询 Agent 当前启用的 MCP 工具主键。
     *
     * @param agentId Agent 主键
     * @return 已启用的 MCP 工具主键列表
     */
    List<Long> listEnabledToolIds(Long agentId);
}