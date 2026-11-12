package org.ruoyi.mapper.mcp;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.mcp.AgentMcpTool;

import java.util.Collection;
import java.util.List;

@Mapper
public interface AgentMcpToolMapper extends BaseMapperPlus<AgentMcpTool, AgentMcpTool> {

    /** 统计 MCP 工具被智能体绑定的数量。 */
    long countByToolIds(@Param("toolIds") Collection<Long> toolIds);

    /** 查询智能体启用的 MCP 工具主键。 */
    List<Long> selectEnabledToolIdsByAgentId(@Param("agentId") Long agentId,
                                             @Param("status") String status);

    int deleteByAgentIds(@Param("agentIds") Collection<Long> agentIds);
}
