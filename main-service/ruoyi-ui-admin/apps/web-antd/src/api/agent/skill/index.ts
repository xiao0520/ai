import type { SkillBoundAgent, SkillOption, SkillVO } from './model';

import type { ID, IDS, PageQuery, PageResult } from '#/api/common';

import { commonExport } from '#/api/helper';
import { requestClient } from '#/api/request';

enum Api {
  list = '/agent/skill/list',
  options = '/agent/skill/options',
  root = '/agent/skill',
}

/**
 * Skill 分页列表
 */
export function skillList(params?: PageQuery) {
  return requestClient.get<PageResult<SkillVO>>(Api.list, { params });
}

/**
 * Skill 详情
 */
export function skillInfo(id: ID) {
  return requestClient.get<SkillVO>(`${Api.root}/${id}`);
}

/**
 * 新增 Skill
 */
export function skillAdd(data: Partial<SkillVO>) {
  return requestClient.postWithMsg<void>(Api.root, data);
}

/**
 * 修改 Skill
 */
export function skillUpdate(data: Partial<SkillVO>) {
  return requestClient.putWithMsg<void>(Api.root, data);
}

/**
 * 启用 / 停用 Skill
 */
export function skillChangeStatus(data: Pick<SkillVO, 'id' | 'status'>) {
  return requestClient.putWithMsg<void>(
    `${Api.root}/${data.id}/status?status=${data.status}`,
  );
}

/**
 * 删除 Skill（未被任何 Agent 绑定时才允许删除）
 */
export function skillRemove(ids: IDS) {
  return requestClient.deleteWithMsg<void>(`${Api.root}/${ids}`);
}

/**
 * 导出 Skill
 */
export function skillExport(data: Partial<SkillVO>) {
  return commonExport(`${Api.root}/export`, data);
}

/**
 * 启用的 Skill 下拉选项（Agent 编辑页选择用）
 */
export function skillOptions() {
  return requestClient.get<SkillOption[]>(Api.options);
}

/**
 * Skill 当前绑定的 Agent 列表（删除前展示绑定情况）
 */
export function skillBoundAgents(id: ID) {
  return requestClient.get<SkillBoundAgent[]>(`${Api.root}/${id}/agents`);
}
