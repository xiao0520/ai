// 智能体选项
export interface AgentVO {
  id?: number;
  agentCode?: string;
  agentName?: string;
  agentDescribe?: string;
  agentShow?: string;
  modelId?: number;
  modelName?: string;
  enableThinking?: string;
  systemPrompt?: string;
  mcpToolIds?: number[];
  skillNames?: string[];
  knowledgeIds?: number[];
  status?: string;
}
