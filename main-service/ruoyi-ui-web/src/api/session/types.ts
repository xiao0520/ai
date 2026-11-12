import type { Component } from 'vue';

export interface GetSessionListParams {
  pageNum?: number;
  pageSize?: number;
  sessionTitle?: string;
}

/** 用户侧会话对象，仅返回当前用户可访问的会话。 */
export interface ChatSessionVo {
  id?: string;
  sessionTitle?: string;
  status?: 'ACTIVE' | 'ARCHIVED';
  messageCount?: number;
  lastMessageAt?: Date;
  createTime?: Date;
  prefixIcon?: Component;
}

/** 创建会话仅允许提交标题，归属用户由后端从登录态确定。 */
export interface CreateSessionDTO {
  sessionTitle: string;
}
