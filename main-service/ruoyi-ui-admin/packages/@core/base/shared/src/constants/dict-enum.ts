export const DictEnum = {
  SYS_COMMON_STATUS: 'sys_common_status',
  SYS_DEVICE_TYPE: 'sys_device_type', // 设备类型
  SYS_GRANT_TYPE: 'sys_grant_type', // 授权类型
  SYS_NORMAL_DISABLE: 'sys_normal_disable',
  SYS_NOTICE_STATUS: 'sys_notice_status', // 通知状态
  SYS_NOTICE_TYPE: 'sys_notice_type', // 通知类型
  SYS_OPER_TYPE: 'sys_oper_type', // 操作类型
  SYS_OSS_ACCESS_POLICY: 'oss_access_policy', // oss权限桶类型
  SYS_SHOW_HIDE: 'sys_show_hide', // 显示状态
  SYS_USER_SEX: 'sys_user_sex', // 性别
  SYS_YES_NO: 'sys_yes_no', // 是否
  WF_BUSINESS_STATUS: 'wf_business_status', // 业务状态
  WF_FORM_TYPE: 'wf_form_type', // 表单类型
  WF_TASK_STATUS: 'wf_task_status', // 任务状态
  CHAT_MODEL_CATEGORY: 'chat_model_category', // 模型分类
  AI_MODEL_API_MODE: 'ai_model_api_mode', // 模型 API 调用模式
  AI_AGENT_TYPE: 'ai_agent_type', // 智能体类型
  AI_AGENT_MODEL_SELECT_POLICY: 'ai_agent_model_select_policy', // 智能体模型选择策略
  AI_AGENT_KNOWLEDGE_SCOPE_POLICY: 'ai_agent_knowledge_scope_policy', // 智能体知识库范围策略
  AI_AGENT_STATUS: 'ai_agent_status', // 智能体状态
  AI_AGENT_ACL_SUBJECT_TYPE: 'ai_agent_acl_subject_type', // 智能体授权主体类型
  AI_AGENT_ACL_PERMISSION: 'ai_agent_acl_permission', // 智能体授权权限
  AI_FLAG_YES_NO: 'ai_flag_yes_no', // AI业务是否标记
  AI_MCP_SERVER_TYPE: 'ai_mcp_server_type', // MCP服务类型
  AI_MCP_TRANSPORT_TYPE: 'ai_mcp_transport_type', // MCP传输类型
  AI_MCP_AUTH_TYPE: 'ai_mcp_auth_type', // MCP认证类型
  AI_MCP_RISK_LEVEL: 'ai_mcp_risk_level', // MCP风险等级
  AI_MCP_SERVER_STATUS: 'ai_mcp_server_status', // MCP服务状态
  AI_MCP_TOOL_SOURCE_TYPE: 'ai_mcp_tool_source_type', // MCP工具来源
  AI_MCP_TOOL_STATUS: 'ai_mcp_tool_status', // MCP工具管理状态
  AI_MCP_CONFIRMATION: 'ai_mcp_confirmation', // MCP调用确认
  AI_SKILL_RUNTIME: 'ai_skill_runtime', // Skill运行时
  AI_KNOWLEDGE_ACCESS_SCOPE: 'ai_knowledge_access_scope', // 知识库访问范围
  AI_KNOWLEDGE_ACL_SUBJECT_TYPE: 'ai_knowledge_acl_subject_type', // 知识库授权主体类型
  AI_KNOWLEDGE_ACL_PERMISSION: 'ai_knowledge_acl_permission', // 知识库授权权限
} as const;

export type DictEnumKey = keyof typeof DictEnum;
