import type { PageQuery } from '#/api/common';

export interface TokenAccountQuery extends PageQuery {
  cycleType?: string;
  deptId?: number | string;
  nickName?: string;
  status?: string;
  unlimited?: string;
  userId?: number | string;
  userName?: string;
}

export interface TokenAccountCommand {
  reason?: string;
  status?: string;
  tokens?: number;
  unlimited?: boolean;
  warningThreshold?: number;
}

export interface TokenAccount {
  createTime?: string;
  cycleEnd?: string;
  cycleNo: string;
  cycleStart?: string;
  cycleType: string;
  deptId?: number | string;
  deptName?: string;
  id: number | string;
  nickName?: string;
  remark?: string;
  remainingTokens: number;
  reservedTokens: number;
  status: string;
  totalTokens: number;
  unlimited: string;
  updateTime?: string;
  usageRate: number;
  usedTokens: number;
  userId: number | string;
  userName: string;
  version: number;
  warningThreshold: number;
}

export interface TokenUserOption {
  configured: boolean;
  deptId?: number | string;
  deptName?: string;
  nickName?: string;
  status?: string;
  userId: number | string;
  userName: string;
}

export interface ModelOption {
  category?: string;
  modelId: number | string;
  modelName: string;
  providerCode?: string;
}

export interface RoleModelPermission {
  effectiveTime?: string;
  expireTime?: string;
  modelIds: Array<number | string>;
  models: ModelOption[];
  remark?: string;
  roleId: number | string;
  roleKey: string;
  roleName: string;
  roleSort?: number;
  roleStatus: string;
}

export interface RoleModelQuery extends PageQuery {
  roleKey?: string;
  roleName?: string;
  status?: string;
}

export interface RoleModelSave {
  effectiveTime?: string;
  expireTime?: string;
  modelIds: Array<number | string>;
  remark?: string;
}

export interface TokenUsageQuery extends PageQuery {
  beginTime?: string;
  deptId?: number | string;
  endTime?: string;
  modelId?: number | string;
  providerCode?: string;
  status?: string;
  usageSource?: string;
  userId?: number | string;
  userName?: string;
}

export interface TokenUsage {
  actualTokens: number;
  agentId?: number | string;
  callId: string;
  chargedTokens: number;
  createTime?: string;
  deptId?: number | string;
  deptName?: string;
  errorCode?: string;
  errorMessage?: string;
  estimatedCost?: number;
  finishTime?: string;
  id: number | string;
  inputTokens: number;
  modelId: number | string;
  modelName?: string;
  nickName?: string;
  outputTokens: number;
  providerCode?: string;
  reasoningTokens: number;
  requestId?: string;
  reservedTokens: number;
  sessionId?: number | string;
  startTime?: string;
  status: string;
  usageSource?: string;
  userId: number | string;
  userName?: string;
}

export interface TokenQuotaLogQuery extends PageQuery {
  beginTime?: string;
  deptId?: number | string;
  endTime?: string;
  operationType?: string;
  operatorId?: number | string;
  userId?: number | string;
  userName?: string;
}

export interface TokenQuotaLog {
  accountId: number | string;
  afterRemaining: number;
  afterTotal: number;
  beforeRemaining: number;
  beforeTotal: number;
  changeTokens: number;
  createTime?: string;
  deptId?: number | string;
  deptName?: string;
  id: number | string;
  nickName?: string;
  operationType: string;
  operatorId: number | string;
  operatorName?: string;
  reason: string;
  userId: number | string;
  userName?: string;
}

export interface TokenStatItem {
  actualTokens: number;
  calls: number;
  chargedTokens: number;
  key: string;
  name?: string;
}

export interface TokenStatistics {
  actualTokens: number;
  chargedTokens: number;
  dailyTrend: TokenStatItem[];
  estimatedCost: number;
  failedCalls: number;
  inputTokens: number;
  modelRanking: TokenStatItem[];
  outputTokens: number;
  successCalls: number;
  totalCalls: number;
  userRanking: TokenStatItem[];
}
