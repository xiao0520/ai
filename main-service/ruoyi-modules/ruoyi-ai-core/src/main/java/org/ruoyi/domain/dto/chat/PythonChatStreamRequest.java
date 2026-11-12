package org.ruoyi.domain.dto.chat;

import org.ruoyi.domain.dto.knowledge.rag.McpToolGrant;

import java.util.List;

/**
 * Java 调用 Python 普通流式聊天接口的请求契约。
 *
 * @param requestId       Java 生成的服务端请求标识
 * @param clientRequestId 客户端幂等请求标识
 * @param sessionId       会话 ID
 * @param actor           Java 已鉴权的调用主体
 * @param input           已完成裁剪的消息输入
 * @param modelProfile    不含真实供应商密钥的模型发布快照
 * @param executionPolicy 本次调用的输入、输出和超时限制
 * @param tools           本次 Java 已授权的 MCP 工具；空集合沿用原聊天链路
 */
public record PythonChatStreamRequest(String requestId, String clientRequestId, String sessionId,
                                      PythonChatActor actor, PythonChatInput input,
                                      PythonModelProfile modelProfile,
                                      PythonExecutionPolicy executionPolicy, List<McpToolGrant> tools) {
    public PythonChatStreamRequest {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** 保留旧调用方构造方式，缺省不启用 MCP。 */
    public PythonChatStreamRequest(String requestId, String clientRequestId, String sessionId,
                                   PythonChatActor actor, PythonChatInput input,
                                   PythonModelProfile modelProfile, PythonExecutionPolicy executionPolicy) {
        this(requestId, clientRequestId, sessionId, actor, input, modelProfile, executionPolicy, List.of());
    }
}
