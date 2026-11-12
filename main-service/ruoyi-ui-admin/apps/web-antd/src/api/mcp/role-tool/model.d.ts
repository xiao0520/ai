import type { PageQuery } from '#/api/common';

export interface McpRoleToolQuery extends PageQuery {
  roleKey?: string;
  roleName?: string;
  status?: string;
}

export interface McpRoleToolSave {
  effectiveTime?: string;
  expireTime?: string;
  remark?: string;
  toolIds: Array<number | string>;
}

export interface McpToolPermissionOption {
  displayName?: string;
  qualifiedName: string;
  riskLevel: string;
  serverName: string;
  toolId: number | string;
}

export interface McpRoleToolPermission {
  effectiveTime?: string;
  expireTime?: string;
  remark?: string;
  roleId: number | string;
  roleKey: string;
  roleName: string;
  roleSort?: number;
  roleStatus: string;
  toolIds: Array<number | string>;
  tools: McpToolPermissionOption[];
}
