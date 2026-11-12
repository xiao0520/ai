import type {
  ChatSessionVo,
  CreateSessionDTO,
  // CreateSessionVO,
  GetSessionListParams,
} from './types';
import { del, get, post, put } from '@/utils/request';

export function get_session_list(params: GetSessionListParams) {
  return get<ChatSessionVo[]>('/chat/my/sessions', params).json();
}

export function create_session(data: CreateSessionDTO) {
  return post('/chat/my/sessions', data).json();
}

export function update_session(data: ChatSessionVo) {
  return put(`/chat/my/sessions/${data.id}`, { sessionTitle: data.sessionTitle }).json();
}

export function get_session(id: string) {
  return get<ChatSessionVo>(`/chat/my/sessions/${id}`).json();
}

export function delete_session(ids: string[]) {
  return Promise.all(ids.map(id => del(`/chat/my/sessions/${id}`).json()));
}
