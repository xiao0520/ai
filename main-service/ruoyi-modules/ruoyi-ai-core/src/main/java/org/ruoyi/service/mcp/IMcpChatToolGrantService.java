package org.ruoyi.service.mcp;

import org.ruoyi.domain.dto.knowledge.rag.McpToolGrant;
import org.ruoyi.domain.vo.model.ModelVo;

import java.util.List;

/** 为单次聊天生成 Java 已授权的 MCP 工具快照。 */
public interface IMcpChatToolGrantService {

    /**
     * 无 Agent 时按角色授权与普通聊天白名单筛选；有 Agent 时按角色与 Agent 绑定取交集。两种场景都只下发只读低风险工具。
     *
     * @param userId 当前用户 ID
     * @param agentId 本次会话的 Agent ID；为空时使用普通聊天白名单
     * @param model 已授权的聊天模型快照
     * @return 本次可执行的工具清单
     */
    List<McpToolGrant> resolve(Long userId, Long agentId, ModelVo model);
}
