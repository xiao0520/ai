package org.ruoyi.enums;

/** 聊天知识来源模式；普通聊天不会隐式扫描用户可访问的知识库。 */
public enum ChatKnowledgeMode {
    /** 仅使用模型和本轮授权的 MCP 工具。 */
    GENERAL,
    /** 检索请求中明确选择的知识库。 */
    KNOWLEDGE
}
