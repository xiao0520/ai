import type { PageQuery } from '#/api/common';

export interface ChatSessionVO {
  id: number | string;
  userId: number | string;
  userName?: string;
  sessionTitle: string;
  agentId?: number | string;
  modelId?: number | string;
  status: string;
  lastMessageAt?: string;
  messageCount: number;
  inputTokens: number;
  outputTokens: number;
  deductTokens: number;
  createTime: string;
  updateTime?: string;
}

export interface ChatSessionQuery extends PageQuery {
  id?: number | string;
  userName?: string;
  sessionTitle?: string;
  agentId?: number | string;
  modelId?: number | string;
  status?: string;
  params?: any;
}
