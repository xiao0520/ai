import type {
  KnowledgeModelOption,
  ModelForm,
  ModelQuery,
  ModelVO,
} from './model';

import type { ID, IDS, PageResult } from '#/api/common';

import { commonExport } from '#/api/helper';
import { requestClient } from '#/api/request';

/**
 * 查询模型管理列表
 * @param params
 * @returns 模型管理列表
 */
export function modelList(params?: ModelQuery) {
  return requestClient.get<PageResult<ModelVO>>('/model/list', {
    params,
  });
}

/**
 * 查询向量模型列表
 * @returns 向量模型列表
 */
export function embeddingModelList() {
  return modelList({ category: 'vector', pageSize: 1000 });
}

/**
 * 查询重排序模型列表
 * @returns 重排序模型列表
 */
export function rerankModelList() {
  return modelList({ category: 'rerank', pageSize: 1000 });
}

/** 查询知识库配置可选的已发布模型。 */
export function knowledgeModelOptions(
  category: string,
  inputModality?: string,
) {
  return requestClient.get<KnowledgeModelOption[]>('/model/knowledge-options', {
    params: { category, inputModality },
  });
}

/**
 * 导出模型管理列表
 * @param params
 * @returns 模型管理列表
 */
export function modelExport(params?: ModelQuery) {
  return commonExport('/model/export', params ?? {});
}

/**
 * 查询模型管理详情
 * @param id id
 * @returns 模型管理详情
 */
export function modelInfo(id: ID) {
  return requestClient.get<ModelVO>(`/model/${id}`);
}

/**
 * 新增模型管理
 * @param data
 * @returns void
 */
export function modelAdd(data: ModelForm) {
  return requestClient.postWithMsg<void>('/model', data);
}

/**
 * 更新模型管理
 * @param data
 * @returns void
 */
export function modelUpdate(data: ModelForm) {
  return requestClient.putWithMsg<void>('/model', data);
}

/**
 * 修改模型在用户端的可见性
 */
export function modelVisibilityUpdate(data: Pick<ModelVO, 'id' | 'modelShow'>) {
  return requestClient.putWithMsg<void>(
    `/model/${data.id}/visibility?modelShow=${data.modelShow}`,
  );
}

/**
 * 删除模型管理
 * @param id id
 * @returns void
 */
export function modelRemove(id: ID | IDS) {
  return requestClient.deleteWithMsg<void>(`/model/${id}`);
}
