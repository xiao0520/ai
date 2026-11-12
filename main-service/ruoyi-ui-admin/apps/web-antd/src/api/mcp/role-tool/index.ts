import type { PageResult } from '#/api/common';

import { requestClient } from '#/api/request';

import type {
  McpRoleToolPermission,
  McpRoleToolQuery,
  McpRoleToolSave,
  McpToolPermissionOption,
} from './model';

const root = '/mcp/role-tool';

export function mcpRoleToolList(params?: McpRoleToolQuery) {
  return requestClient.get<PageResult<McpRoleToolPermission>>(`${root}/roles`, {
    params,
  });
}

export function mcpRoleToolInfo(roleId: number | string) {
  return requestClient.get<McpRoleToolPermission>(
    `${root}/roles/${roleId}/tools`,
  );
}

export function mcpRoleToolSave(
  roleId: number | string,
  data: McpRoleToolSave,
) {
  return requestClient.putWithMsg<void>(`${root}/roles/${roleId}/tools`, data);
}

export function mcpPermissionToolOptions() {
  return requestClient.get<McpToolPermissionOption[]>(`${root}/tools/options`);
}
