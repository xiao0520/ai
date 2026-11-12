export interface AgentVO {
  id: number;
  agentCode: string;
  agentName: string;
  agentType?: string;
  agentDescribe?: string;
  agentShow?: string;
  modelId: number;
  modelIds?: number[];
  modelName?: string;
  modelNames?: string[];
  modelSelectPolicy?: string;
  knowledgeScopePolicy?: string;
  enableThinking?: string;
  systemPrompt?: string;
  welcomeMessage?: string;
  suggestedQuestions?: string[];
  executionPolicy?: string;
  mcpToolIds?: number[];
  mcpToolNames?: string[];
  skillNames?: string[];
  knowledgeIds?: number[];
  knowledgeNames?: string[];
  status: string;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

export interface AgentAcl {
  id?: number;
  agentId?: number;
  subjectType: 'DEPT' | 'ROLE';
  subjectId: number;
  subjectName?: string;
  permission?: 'MANAGE' | 'USE';
  effectiveTime?: string;
  expireTime?: string;
  status?: string;
  remark?: string;
}

export interface SkillOption {
  name: string;
  description?: string;
}
