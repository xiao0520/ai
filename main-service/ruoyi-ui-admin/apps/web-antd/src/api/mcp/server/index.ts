import type { PageQuery, PageResult } from '#/api/common';

import { requestClient } from '#/api/request';

import type { McpServer, McpToolImportCommand } from './model';

const root = '/mcp/server';

export function mcpServerList(params?: PageQuery) {
  return requestClient.get<PageResult<McpServer>>(`${root}/list`, { params });
}

export function mcpServerAll() {
  return requestClient.get<McpServer[]>(`${root}/all`);
}

export function mcpServerInfo(id: number | string) {
  return requestClient.get<McpServer>(`${root}/${id}`);
}

export function mcpServerAdd(data: Partial<McpServer>) {
  return requestClient.postWithMsg<void>(root, data);
}

export function mcpServerUpdate(data: Partial<McpServer>) {
  return requestClient.putWithMsg<void>(root, data);
}

export function mcpServerRemove(ids: Array<number | string>) {
  return requestClient.deleteWithMsg<void>(`${root}/${ids.join(',')}`);
}

export function mcpServerChangeStatus(data: Pick<McpServer, 'id' | 'status'>) {
  return requestClient.putWithMsg<void>(
    `${root}/${data.id}/status?status=${data.status}`,
  );
}

export function mcpServerImportTools(
  id: number | string,
  data: McpToolImportCommand,
) {
  return requestClient.postWithMsg<number>(`${root}/${id}/tools/import`, data);
}
