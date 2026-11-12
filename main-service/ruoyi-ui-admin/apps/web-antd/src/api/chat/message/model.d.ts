import type { PageQuery } from '#/api/common';

export interface MessageVO {
  /**
   * 主键
   */
  id: number | string;

  /**
   * 会话id
   */
  sessionId: number | string;

  /**
   * 内部用户主键
   */
  userId: number | string;

  /**
   * 用户账号
   */
  userName?: string;

  messageSeq: number;

  /**
   * 消息内容
   */
  content: string;

  /**
   * 对话角色
   */
  role: string;

  /**
   * 累计 Tokens
   */
  totalTokens: number;

  inputTokens: number;

  outputTokens: number;

  deductTokens: number;

  /**
   * 模型名称
   */
  modelName: string;

  callStatus: string;

  requestId: string;

  errorCode: string;

  errorMessage: string;

  createTime: string;
}

export interface MessageQuery extends PageQuery {
  /**
   * 会话id
   */
  sessionId?: number | string;

  /**
   * 用户账号
   */
  userName?: string;

  /**
   * 消息内容
   */
  content?: string;

  /**
   * 对话角色
   */
  role?: string;

  /**
   * 累计 Tokens
   */
  totalTokens?: number;

  /**
   * 模型名称
   */
  modelName?: string;

  callStatus?: string;

  /**
   * 日期范围参数
   */
  params?: any;
}
