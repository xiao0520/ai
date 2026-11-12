import type { ID, PageResult } from '#/api/common';
import type { ChatSessionQuery, ChatSessionVO } from './model';

import { commonExport } from '#/api/helper';
import { requestClient } from '#/api/request';

export function chatSessionList(params?: ChatSessionQuery) {
  return requestClient.get<PageResult<ChatSessionVO>>('/chat/sessions', { params });
}

export function chatSessionInfo(id: ID) {
  return requestClient.get<ChatSessionVO>(`/chat/sessions/${id}`);
}

export function chatSessionExport(params?: ChatSessionQuery) {
  return commonExport('/chat/sessions/export', params ?? {});
}

export function chatSessionChangeStatus(id: ID, status: string) {
  return requestClient.postWithMsg<void>(`/chat/sessions/${id}/status?status=${status}`);
}
