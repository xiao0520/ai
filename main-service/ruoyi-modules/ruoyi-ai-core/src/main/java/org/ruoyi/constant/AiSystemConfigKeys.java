package org.ruoyi.constant;

/** AI 运行参数键。 */
public final class AiSystemConfigKeys {

    /** 知识库上传最大大小（MB） */
    public static final String KNOWLEDGE_UPLOAD_MAX_SIZE_MB = "ai.knowledge.upload.max-size-mb";
    /** 知识库上传允许的后缀 */
    public static final String KNOWLEDGE_UPLOAD_ALLOWED_SUFFIXES = "ai.knowledge.upload.allowed-suffixes";
    /** 知识解析任务最大尝试次数 */
    public static final String KNOWLEDGE_PARSE_MAX_ATTEMPTS = "ai.knowledge.parse.max-attempts";
    /** 单个知识库入库 attempt 最大模型 Token 预算 */
    public static final String KNOWLEDGE_JOB_MAX_TOKEN_BUDGET = "ai.knowledge.job.max-token-budget";
    /** 已退休产物批次延迟清理小时数 */
    public static final String KNOWLEDGE_ARTIFACT_RETENTION_HOURS = "ai.knowledge.artifact.retention-hours";
    /** 知识库附件 OSS 业务目录参数键 */
    public static final String KNOWLEDGE_ATTACHMENT_OSS_CONFIG_KEY = "ai.oss.knowledge-attachment.config-key";
    /** 聊天上下文窗口令牌数 */
    public static final String CHAT_CONTEXT_WINDOW_TOKENS = "ai.chat.default.context-window-tokens";
    /** 聊天默认最大输入令牌数 */
    public static final String CHAT_MAX_INPUT_TOKENS = "ai.chat.default.max-input-tokens";
    /** 聊天安全边距令牌数 */
    public static final String CHAT_SAFETY_MARGIN_TOKENS = "ai.chat.default.safety-margin-tokens";
    /** 聊天历史最大轮数 */
    public static final String CHAT_HISTORY_MAX_TURNS = "ai.chat.default.history-max-turns";
    /** 聊天知识库最大块数 */
    public static final String CHAT_KNOWLEDGE_MAX_CHUNKS = "ai.chat.default.knowledge-max-chunks";
    /** 聊天知识库最大令牌数 */
    public static final String CHAT_KNOWLEDGE_MAX_TOKENS = "ai.chat.default.knowledge-max-tokens";
    /** 聊天工具结果最大令牌数 */
    public static final String CHAT_TOOL_RESULT_MAX_TOKENS = "ai.chat.default.tool-result-max-tokens";
    /** 令牌默认告警阈值 */
    public static final String TOKEN_DEFAULT_WARNING_THRESHOLD = "ai.token.default-warning-threshold";
    /** 令牌使用仪表板TopN */
    public static final String TOKEN_USAGE_DASHBOARD_TOP_N = "ai.token.usage.dashboard-top-n";
    /** MCP导入最大工具数 */
    public static final String MCP_IMPORT_MAX_TOOLS = "ai.mcp.import.max-tools";
    /** MCP导入最大Schema长度 */
    public static final String MCP_IMPORT_MAX_SCHEMA_LENGTH = "ai.mcp.import.max-schema-length";
    /** MCP确认风险级别 */
    public static final String MCP_CONFIRMATION_RISK_LEVELS = "ai.mcp.confirmation-risk-levels";
    /** Skill制品OSS业务目录 */
    public static final String SKILL_ARTIFACT_OSS_CONFIG_KEY = "ai.oss.skill-artifact.config-key";

    private AiSystemConfigKeys() {
    }
}
