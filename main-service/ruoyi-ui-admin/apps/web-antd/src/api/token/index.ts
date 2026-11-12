import type { PageResult } from '#/api/common';

import { requestClient } from '#/api/request';

import type {
  ModelOption,
  RoleModelPermission,
  RoleModelQuery,
  RoleModelSave,
  TokenAccount,
  TokenAccountCommand,
  TokenAccountQuery,
  TokenQuotaLog,
  TokenQuotaLogQuery,
  TokenStatistics,
  TokenUsage,
  TokenUsageQuery,
  TokenUserOption,
} from './model';

const quotaRoot = '/token/quota';
const usageRoot = '/token/usage';

export function tokenAccountList(params?: TokenAccountQuery) {
  return requestClient.get<PageResult<TokenAccount>>(`${quotaRoot}/users`, {
    params,
  });
}

export function tokenUserOptions(params?: TokenAccountQuery) {
  return requestClient.get<PageResult<TokenUserOption>>(
    `${quotaRoot}/users/options`,
    { params },
  );
}

export function tokenAccountInfo(userId: number | string) {
  return requestClient.get<null | TokenAccount>(`${quotaRoot}/users/${userId}`);
}

export function tokenInitialGrant(
  userId: number | string,
  data: TokenAccountCommand,
) {
  return requestClient.postWithMsg<void>(
    `${quotaRoot}/users/${userId}/initial-grant`,
    data,
  );
}

export function tokenAppendGrant(
  userId: number | string,
  data: TokenAccountCommand,
) {
  return requestClient.postWithMsg<void>(
    `${quotaRoot}/users/${userId}/append`,
    data,
  );
}

export function tokenSetRemaining(
  userId: number | string,
  data: TokenAccountCommand,
) {
  return requestClient.putWithMsg<void>(
    `${quotaRoot}/users/${userId}/remaining`,
    data,
  );
}

export function tokenSetWarningThreshold(
  userId: number | string,
  data: TokenAccountCommand,
) {
  return requestClient.putWithMsg<void>(
    `${quotaRoot}/users/${userId}/warning-threshold`,
    data,
  );
}

export function tokenSetUnlimited(
  userId: number | string,
  data: TokenAccountCommand,
) {
  return requestClient.putWithMsg<void>(
    `${quotaRoot}/users/${userId}/unlimited`,
    data,
  );
}

export function tokenChangeStatus(
  userId: number | string,
  data: TokenAccountCommand,
) {
  return requestClient.putWithMsg<void>(
    `${quotaRoot}/users/${userId}/status`,
    data,
  );
}

export function roleModelList(params?: RoleModelQuery) {
  return requestClient.get<PageResult<RoleModelPermission>>(
    `${quotaRoot}/roles`,
    { params },
  );
}

export function tokenModelOptions() {
  return requestClient.get<ModelOption[]>(`${quotaRoot}/models/options`);
}

export function roleModelInfo(roleId: number | string) {
  return requestClient.get<RoleModelPermission>(
    `${quotaRoot}/roles/${roleId}/models`,
  );
}

export function roleModelSave(
  roleId: number | string,
  data: RoleModelSave,
) {
  return requestClient.putWithMsg<void>(
    `${quotaRoot}/roles/${roleId}/models`,
    data,
  );
}

export function effectiveModelList(userId: number | string) {
  return requestClient.get<ModelOption[]>(
    `${quotaRoot}/users/${userId}/effective-models`,
  );
}

export function tokenQuotaLogList(params?: TokenQuotaLogQuery) {
  return requestClient.get<PageResult<TokenQuotaLog>>(`${quotaRoot}/logs`, {
    params,
  });
}

export function tokenUsageList(params?: TokenUsageQuery) {
  return requestClient.get<PageResult<TokenUsage>>(`${usageRoot}/list`, {
    params,
  });
}

export function tokenUsageStatistics(params?: TokenUsageQuery) {
  return requestClient.get<TokenStatistics>(`${usageRoot}/statistics`, {
    params,
  });
}
