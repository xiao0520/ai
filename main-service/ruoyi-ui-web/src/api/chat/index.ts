import type {
  ChatCitationVo,
  ChatKnowledgeOptionVO,
  ChatMessageVo,
  GetChatListParams,
  SendDTO,
} from './types';
import { get, post } from '@/utils/request';

// 发送消息
export const send = (data: SendDTO) => post('/chat/send', data);

// 获取当前会话的聊天记录
export function getChatList(params: GetChatListParams) {
  return get<ChatMessageVo[]>('/chat/my/messages', params).json();
}

/** 获取当前用户可读且索引已经就绪的聊天知识库。 */
export function getChatKnowledgeOptions() {
  return get<ChatKnowledgeOptionVO[]>('/chat/knowledge-options').json();
}
/** 回访单条引用，Java 会重新校验会话归属、ACL 和来源状态。 */
export function getChatCitation(messageId: number, citationId: string) {
  return get<ChatCitationVo>(`/chat/my/messages/${messageId}/citations/${encodeURIComponent(citationId)}`).json();
}
