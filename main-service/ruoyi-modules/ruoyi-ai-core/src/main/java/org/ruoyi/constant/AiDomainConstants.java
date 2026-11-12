package org.ruoyi.constant;

import java.util.Set;

/** AI 领域中不可由运行参数或字典改变的协议值。 */
public final class AiDomainConstants {

    /** 状态：活跃 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 状态：已归档 */
    public static final String STATUS_ARCHIVED = "ARCHIVED";
    /** 状态：待处理 */
    public static final String STATUS_PENDING = "PENDING";
    /** 状态：就绪 */
    public static final String STATUS_READY = "READY";
    /** 状态：失败 */
    public static final String STATUS_FAILED = "FAILED";
    /** 状态：构建中 */
    public static final String STATUS_BUILDING = "BUILDING";
    /** 聊天角色：系统 */
    public static final String CHAT_ROLE_SYSTEM = "system";
    /** 聊天角色：用户 */
    public static final String CHAT_ROLE_USER = "user";
    /** 聊天角色：助手 */
    public static final String CHAT_ROLE_ASSISTANT = "assistant";
    /** 主体类型：角色 */
    public static final String SUBJECT_ROLE = "ROLE";
    /** 主体类型：部门 */
    public static final String SUBJECT_DEPT = "DEPT";
    /** 权限：读 */
    public static final String PERMISSION_READ = "READ";
    /** 权限：写 */
    public static final String PERMISSION_WRITE = "WRITE";
    /** 权限：管理 */
    public static final String PERMISSION_MANAGE = "MANAGE";
    /** 权限：使用 */
    public static final String PERMISSION_USE = "USE";
    /** 知识库范围：所有内部 */
    public static final String KNOWLEDGE_SCOPE_ALL_INTERNAL = "ALL_INTERNAL";
    /** 知识库范围：仅ACL */
    public static final String KNOWLEDGE_SCOPE_ACL_ONLY = "ACL_ONLY";
    /** Qdrant collection 固定前缀。 */
    public static final String KNOWLEDGE_COLLECTION_PREFIX = "kb";
    /** 索引版本名称格式；该名称同时作为 Qdrant collection 名称片段。 */
    public static final String KNOWLEDGE_INDEX_VERSION_PATTERN = "[A-Za-z0-9_-]{1,64}";
    /** 智能体知识库范围：绑定交集 */
    public static final String AGENT_KNOWLEDGE_SCOPE_BOUND_INTERSECTION = "BOUND_INTERSECTION";
    /** 智能体模型选择策略：固定 */
    public static final String AGENT_MODEL_SELECT_POLICY_FIXED = "FIXED";
    /** 智能体模型选择策略：用户选择 */
    public static final String AGENT_MODEL_SELECT_POLICY_USER_SELECT = "USER_SELECT";
    /** 智能体类型：业务 */
    public static final String AGENT_TYPE_BUSINESS = "BUSINESS";
    /** 智能体知识库范围：角色授权 */
    public static final String AGENT_KNOWLEDGE_SCOPE_ROLE_AUTHORIZED = "ROLE_AUTHORIZED";
    /** MCP状态：已启用 */
    public static final String MCP_STATUS_ENABLED = "ENABLED";
    /** MCP状态：已禁用 */
    public static final String MCP_STATUS_DISABLED = "DISABLED";
    /** MCP运行状态：未部署 */
    public static final String MCP_RUNTIME_STATUS_NOT_DEPLOYED = "NOT_DEPLOYED";
    /** MCP传输类型：Streamable HTTP */
    public static final String MCP_TRANSPORT_STREAMABLE_HTTP = "STREAMABLE_HTTP";
    /** MCP传输类型：标准输入输出 */
    public static final String MCP_TRANSPORT_STDIO = "STDIO";
    /** MCP认证类型：无需认证 */
    public static final String MCP_AUTH_NONE = "NONE";
    /** MCP认证类型：Bearer Token */
    public static final String MCP_AUTH_BEARER = "BEARER";
    /** MCP工具来源：清单导入 */
    public static final String MCP_TOOL_SOURCE_IMPORTED = "IMPORTED";
    /** MCP工具发现状态：活跃 */
    public static final String MCP_TOOL_DISCOVERY_STATUS_ACTIVE = "ACTIVE";
    /** MCP工具发现状态：Schema 已变更 */
    public static final String MCP_TOOL_DISCOVERY_STATUS_CHANGED = "CHANGED";
    /** MCP工具发现状态：已移除 */
    public static final String MCP_TOOL_DISCOVERY_STATUS_REMOVED = "REMOVED";
    /** MCP风险等级：低 */
    public static final String MCP_RISK_LEVEL_LOW = "LOW";
    /** MCP风险等级：高 */
    public static final String MCP_RISK_LEVEL_HIGH = "HIGH";
    /** 标志：已启用 */
    public static final String FLAG_ENABLED = "1";
    /** 标志：已禁用 */
    public static final String FLAG_DISABLED = "0";
    /** 配置修订类型：模型配置 */
    public static final String CONFIG_REVISION_TYPE_MODEL_PROFILE = "MODEL_PROFILE";
    /** 配置修订类型：知识库配置 */
    public static final String CONFIG_REVISION_TYPE_KNOWLEDGE_CONFIG = "KNOWLEDGE_CONFIG";
    /** 配置修订状态：已发布 */
    public static final String CONFIG_REVISION_STATUS_PUBLISHED = "PUBLISHED";
    /** 知识库状态：已删除 */
    public static final String KNOWLEDGE_STATUS_DELETED = "DELETED";
    /** 知识文档状态：解析中 */
    public static final String KNOWLEDGE_DOCUMENT_STATUS_PROCESSING = "PROCESSING";
    /** 知识文档状态：可用 */
    public static final String KNOWLEDGE_DOCUMENT_STATUS_READY = "READY";
    /** 知识文档状态：已停用 */
    public static final String KNOWLEDGE_DOCUMENT_STATUS_DISABLED = "DISABLED";
    /** 知识文档来源：上传 */
    public static final String KNOWLEDGE_DOCUMENT_SOURCE_UPLOAD = "UPLOAD";
    /** 文档处理模式：纯文本 */
    public static final String KNOWLEDGE_PROCESSING_MODE_TEXT = "TEXT";
    /** 文档处理模式：多模态 */
    public static final String KNOWLEDGE_PROCESSING_MODE_MULTIMODAL = "MULTIMODAL";
    /** 文档处理模式集合 */
    public static final Set<String> KNOWLEDGE_PROCESSING_MODES = Set.of(
        KNOWLEDGE_PROCESSING_MODE_TEXT, KNOWLEDGE_PROCESSING_MODE_MULTIMODAL);
    /** 当前解析链路支持的知识文档后缀；运行参数只能在此集合内收窄。 */
    public static final Set<String> KNOWLEDGE_DOCUMENT_SUPPORTED_SUFFIXES = Set.of(
        "txt", "md", "markdown", "pdf", "docx", "csv", "xlsx", "pptx");
    /** 模型输入模态：文本 */
    public static final String MODEL_INPUT_MODALITY_TEXT = "TEXT";
    /** 模型输入模态：图片 */
    public static final String MODEL_INPUT_MODALITY_IMAGE = "IMAGE";
    /** 模型输入模态集合 */
    public static final Set<String> MODEL_INPUT_MODALITIES = Set.of(
        MODEL_INPUT_MODALITY_TEXT, MODEL_INPUT_MODALITY_IMAGE);
    /** 模型 API 模式：对话补全 */
    public static final String MODEL_API_MODE_CHAT_COMPLETIONS = "CHAT_COMPLETIONS";
    /** 模型 API 模式：Responses */
    public static final String MODEL_API_MODE_RESPONSES = "RESPONSES";
    /** 模型 API 模式：OpenAI 兼容向量 */
    public static final String MODEL_API_MODE_OPENAI_EMBEDDINGS = "OPENAI_EMBEDDINGS";
    /** 模型 API 模式：方舟多模态向量 */
    public static final String MODEL_API_MODE_ARK_MULTIMODAL_EMBEDDINGS = "ARK_MULTIMODAL_EMBEDDINGS";
    /** 聊天和视觉模型允许的 API 模式 */
    public static final Set<String> MODEL_CHAT_API_MODES = Set.of(
        MODEL_API_MODE_CHAT_COMPLETIONS, MODEL_API_MODE_RESPONSES);
    /** 向量模型允许的 API 模式 */
    public static final Set<String> MODEL_EMBEDDING_API_MODES = Set.of(
        MODEL_API_MODE_OPENAI_EMBEDDINGS, MODEL_API_MODE_ARK_MULTIMODAL_EMBEDDINGS);
    /** 豆包供应商编码 */
    public static final String MODEL_PROVIDER_DOUBAO = "doubao";
    /** 阿里云百炼供应商编码 */
    public static final String MODEL_PROVIDER_QIANWEN = "qianwen";
    /** 重排序适配器：Cohere v2 兼容协议 */
    public static final String RERANK_ADAPTER_COHERE_V2 = "COHERE_RERANK_V2";
    /** 重排序适配器：阿里云百炼文本排序协议 */
    public static final String RERANK_ADAPTER_DASHSCOPE_TEXT = "DASHSCOPE_TEXT_RERANK";
    /** 重排序适配器：火山引擎知识库 AK/SK 签名协议 */
    public static final String RERANK_ADAPTER_VOLCENGINE_KNOWLEDGE =
        "VOLCENGINE_KNOWLEDGE_RERANK";
    /** Python 执行端当前已注册的重排序适配器编码 */
    public static final Set<String> RERANK_ADAPTER_CODES = Set.of(
        RERANK_ADAPTER_COHERE_V2, RERANK_ADAPTER_DASHSCOPE_TEXT,
        RERANK_ADAPTER_VOLCENGINE_KNOWLEDGE);
    /** 文档 Rerank 策略：使用 Profile */
    public static final String DOCUMENT_RERANK_MODE_PROFILE = "PROFILE";
    /** 文档 Rerank 策略：明确关闭 */
    public static final String DOCUMENT_RERANK_MODE_NONE = "NONE";
    /** 文档 Rerank 策略集合 */
    public static final Set<String> DOCUMENT_RERANK_MODES = Set.of(
        DOCUMENT_RERANK_MODE_PROFILE, DOCUMENT_RERANK_MODE_NONE);
    /** 知识文档允许持久化的状态集合 */
    public static final Set<String> KNOWLEDGE_DOCUMENT_STATUSES = Set.of(
        STATUS_PENDING, KNOWLEDGE_DOCUMENT_STATUS_PROCESSING, KNOWLEDGE_DOCUMENT_STATUS_READY,
        STATUS_FAILED, KNOWLEDGE_DOCUMENT_STATUS_DISABLED, KNOWLEDGE_STATUS_DELETED);
    /** 知识解析任务类型：文档入库 */
    public static final String KNOWLEDGE_PARSE_JOB_TYPE_INGESTION = "INGESTION";
    /** 知识解析任务类型：索引重建 */
    public static final String KNOWLEDGE_PARSE_JOB_TYPE_REBUILD = "REBUILD";
    /** 知识解析任务类型：精确产物批次清理 */
    public static final String KNOWLEDGE_PARSE_JOB_TYPE_CLEANUP = "CLEANUP";
    /** 知识解析任务类型：索引产物孤儿对账 */
    public static final String KNOWLEDGE_PARSE_JOB_TYPE_RECONCILE = "RECONCILE";
    /** 知识解析任务状态：已排队 */
    public static final String KNOWLEDGE_PARSE_JOB_STATUS_QUEUED = "QUEUED";
    /** 知识解析任务状态：执行中 */
    public static final String KNOWLEDGE_PARSE_JOB_STATUS_RUNNING = "RUNNING";
    /** 知识解析任务状态：取消请求已发送，等待执行端回报 */
    public static final String KNOWLEDGE_PARSE_JOB_STATUS_CANCEL_REQUESTED = "CANCEL_REQUESTED";
    /** 知识解析任务状态：等待重试 */
    public static final String KNOWLEDGE_PARSE_JOB_STATUS_RETRY_WAIT = "RETRY_WAIT";
    /** 知识解析任务状态：已成功 */
    public static final String KNOWLEDGE_PARSE_JOB_STATUS_SUCCEEDED = "SUCCEEDED";
    /** 知识解析任务状态：已取消 */
    public static final String KNOWLEDGE_PARSE_JOB_STATUS_CANCELLED = "CANCELLED";
    /** 知识解析任务在管理列表中可见 */
    public static final String KNOWLEDGE_PARSE_JOB_LIST_VISIBLE = "0";
    /** 知识解析任务已从管理列表隐藏 */
    public static final String KNOWLEDGE_PARSE_JOB_LIST_HIDDEN = "1";
    /** 尚未存在生效索引时用于幂等键的占位符 */
    public static final String KNOWLEDGE_PARSE_JOB_NO_ACTIVE_INDEX = "NO_ACTIVE_INDEX";
    /** 知识解析任务允许取消的状态集合 */
    public static final Set<String> KNOWLEDGE_PARSE_JOB_ACTIVE_STATUSES = Set.of(
        STATUS_PENDING, KNOWLEDGE_PARSE_JOB_STATUS_QUEUED, KNOWLEDGE_PARSE_JOB_STATUS_RUNNING,
        KNOWLEDGE_PARSE_JOB_STATUS_RETRY_WAIT, KNOWLEDGE_PARSE_JOB_STATUS_CANCEL_REQUESTED);
    /** 知识解析任务不可再流转的终态集合 */
    public static final Set<String> KNOWLEDGE_PARSE_JOB_TERMINAL_STATUSES = Set.of(
        KNOWLEDGE_PARSE_JOB_STATUS_SUCCEEDED, STATUS_FAILED, KNOWLEDGE_PARSE_JOB_STATUS_CANCELLED);
    /** 知识解析任务允许重试的状态集合 */
    public static final Set<String> KNOWLEDGE_PARSE_JOB_RETRYABLE_STATUSES = Set.of(
        STATUS_FAILED, KNOWLEDGE_PARSE_JOB_STATUS_CANCELLED);
    /** 知识解析任务最大重试次数的安全上限 */
    public static final int KNOWLEDGE_PARSE_JOB_MAX_ATTEMPTS_LIMIT = 100;
    /** Skill编码格式 */
    public static final String SKILL_NAME_PATTERN = "[a-z][a-z0-9-]{1,127}";
    /** SHA-256十六进制摘要格式 */
    public static final String SHA_256_HEX_PATTERN = "[a-fA-F0-9]{64}";
    /** Skill制品文件后缀 */
    public static final String SKILL_ARTIFACT_EXTENSION = "md";
    /** 空 JSON 数组的稳定存储表示 */
    public static final String EMPTY_JSON_ARRAY = "[]";

    private AiDomainConstants() {
    }
}
