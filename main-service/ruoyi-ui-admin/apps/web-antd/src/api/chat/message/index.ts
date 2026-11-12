import type { MessageQuery, MessageVO } from './model';

import type { ID, PageResult } from '#/api/common';

import { commonExport } from '#/api/helper';
import { requestClient } from '#/api/request';

/**
 * 查询聊天消息列表
 * @param params
 * @returns 聊天消息列表
 */
export function messageList(params?: MessageQuery) {
  return requestClient.get<PageResult<MessageVO>>('/chat/messages', {
    params,
  });
}

/**
 * 导出聊天消息列表
 * @param params
 * @returns 聊天消息列表
 */
export function messageExport(params?: MessageQuery) {
  return commonExport('/chat/messages/export', params ?? {});
}

/**
 * 查询聊天消息详情
 * @param id id
 * @returns 聊天消息详情
 */
export function messageInfo(id: ID) {
  return requestClient.get<MessageVO>(`/chat/messages/${id}`);
}
