import type { BaseEntity, PageQuery } from '#/api/common';

export interface ModelVO {
  /**
   * 主键
   */
  id: number | string;

  /**
   * 模型分类
   */
  category: string;

  /**
   * 模型名称
   */
  modelName: string;

  /**
   * 模型供应商
   */
  providerCode: number | string;

  /**
   * 模型描述
   */
  modelDescribe: string;

  /**
   * 是否显示
   */
  modelShow: string;

  /**
   * 模型维度
   */
  modelDimension: number;

  /** AUTO 路由能力标签 JSON 数组。 */
  capabilities?: string;

  /** 每百万输入 Token 成本。 */
  inputCostPerMillion?: number;

  /** 每百万输出 Token 成本。 */
  outputCostPerMillion?: number;

  /** AUTO 路由优先级，复用模型灰度权重字段。 */
  grayWeight?: number;

  /** 最大上下文 Token */
  maxContextTokens: number;

  /** 最大输入 Token */
  maxInputTokens: number;

  /** 最大输出 Token */
  maxOutputTokens: number;

  /** 上下文安全余量 Token */
  safetyMarginTokens: number;

  /** 最近历史最大轮数 */
  historyMaxTurns: number;

  /** 知识库最大检索片段数 */
  knowledgeMaxChunks: number;

  /** 知识库资料最大 Token */
  knowledgeMaxTokens: number;

  /** 单次工具结果最大 Token */
  toolResultMaxTokens: number;

  /**
   * 请求地址
   */
  apiHost: string;

  /** API 调用模式 */
  apiMode: string;

  /** 输入模态 JSON 数组。 */
  inputModalities?: string;

  /** Provider 适配器编码。 */
  adapterCode?: string;

  /** 向量是否归一化。 */
  normalized?: boolean;

  /** Rerank 单文档最大 Token。 */
  maxDocumentTokens?: number;

  /** 当前发布修订。 */
  publishedRevision?: number;

  /**
   * 凭证键
   */
  credentialKey: string;

  /**
   * 备注
   */
  remark: string;
}

export interface ModelForm extends BaseEntity {
  /**
   * 主键
   */
  id?: number | string;

  /**
   * 模型分类
   */
  category?: string;

  /**
   * 模型名称
   */
  modelName?: string;

  /**
   * 模型供应商
   */
  providerCode?: number | string;

  /**
   * 模型描述
   */
  modelDescribe?: string;

  /**
   * 是否显示
   */
  modelShow?: string;

  /**
   * 模型维度
   */
  modelDimension?: number;

  capabilities?: string;

  inputCostPerMillion?: number;

  outputCostPerMillion?: number;

  grayWeight?: number;

  maxContextTokens?: number;

  maxInputTokens?: number;

  maxOutputTokens?: number;

  safetyMarginTokens?: number;

  historyMaxTurns?: number;

  knowledgeMaxChunks?: number;

  knowledgeMaxTokens?: number;

  toolResultMaxTokens?: number;

  /**
   * 请求地址
   */
  apiHost?: string;

  /** API 调用模式 */
  apiMode?: string;

  inputModalities?: string;

  adapterCode?: string;

  normalized?: boolean;

  maxDocumentTokens?: number;

  /**
   * 凭证键
   */
  credentialKey?: string;

  /**
   * 备注
   */
  remark?: string;
}

export interface KnowledgeModelOption {
  id: number | string;
  modelName: string;
  modelDescribe?: string;
  providerCode: string;
  category: 'image' | 'rerank' | 'vector' | string;
  apiMode?: string;
  adapterCode?: string;
  inputModalities: string;
  modelDimension?: number;
  normalized?: boolean;
  maxInputTokens?: number;
  maxDocumentTokens?: number;
  publishedRevision: number;
}

export interface ModelQuery extends PageQuery {
  /**
   * 模型分类
   */
  category?: string;

  /**
   * 模型名称
   */
  modelName?: string;

  /**
   * 模型供应商
   */
  providerCode?: number | string;

  /**
   * 模型描述
   */
  modelDescribe?: string;

  /**
   * 是否显示
   */
  modelShow?: string;

  /**
   * 模型维度
   */
  modelDimension?: number;

  /**
   * 请求地址
   */
  apiHost?: string;

  /** API 调用模式 */
  apiMode?: string;

  /**
   * 凭证键
   */
  credentialKey?: string;

  /**
   * 日期范围参数
   */
  params?: any;
}
