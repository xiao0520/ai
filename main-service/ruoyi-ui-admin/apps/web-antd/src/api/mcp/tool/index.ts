import type { PageQuery, PageResult } from '#/api/common';

import { requestClient } from '#/api/request';

import type { McpTool, McpToolOption } from './model';

const root = '/mcp/tool';

export function mcpToolList(params?: PageQuery) {
  return requestClient.get<PageResult<McpTool>>(`${root}/list`, { params });
}

export function mcpToolOptions() {
  return requestClient.get<McpToolOption[]>(`${root}/options`);
}

export function mcpToolInfo(id: number | string) {
  return requestClient.get<McpTool>(`${root}/${id}`);
}

export function mcpToolPolicyUpdate(data: Partial<McpTool>) {
  return requestClient.putWithMsg<void>(`${root}/policy`, data);
}

export function mcpToolChangeStatus(data: Pick<McpTool, 'adminEnabled' | 'id'>) {
  return requestClient.putWithMsg<void>(
    `${root}/${data.id}/status?adminEnabled=${data.adminEnabled}`,
  );
}

export function mcpToolRemove(ids: Array<number | string>) {
  return requestClient.deleteWithMsg<void>(`${root}/${ids.join(',')}`);
}
