import type { FormSchemaGetter } from '#/adapter/form';
import type { VxeGridProps } from '#/adapter/vxe-table';

import { DictEnum } from '@vben/constants';

import { mcpServerAll } from '#/api/mcp/server';
import { getDictOptions } from '#/utils/dict';

function dictLabel(dictName: string, value: unknown) {
  return (
    getDictOptions(dictName).find((option) => option.value === String(value))
      ?.label ?? String(value ?? '')
  );
}

export const querySchema: FormSchemaGetter = () => [
  {
    component: 'Input',
    fieldName: 'toolName',
    label: '工具名称',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'ApiSelect',
    componentProps: {
      api: mcpServerAll,
      labelField: 'serverName',
      valueField: 'id',
      placeholder: '请选择MCP Server',
      style: { width: '240px' },
    },
    fieldName: 'serverId',
    label: '所属Server',
  },
  {
    component: 'Select',
    componentProps: {
      style: { width: '240px' },
      options: getDictOptions(DictEnum.AI_MCP_RISK_LEVEL),
    },
    fieldName: 'riskLevel',
    label: '风险等级',
  },
  {
    component: 'Select',
    componentProps: {
      style: { width: '240px' },
      options: getDictOptions(DictEnum.AI_MCP_TOOL_STATUS),
    },
    fieldName: 'adminEnabled',
    label: '管理状态',
  },
];

export const drawerSchema: FormSchemaGetter = () => [
  {
    component: 'Input',
    dependencies: {
      show: () => false,
      triggerFields: [''],
    },
    fieldName: 'id',
    label: 'id',
  },
  {
    component: 'Input',
    fieldName: 'displayName',
    label: '显示名称',
  },
  {
    component: 'Select',
    componentProps: {
      options: getDictOptions(DictEnum.AI_MCP_RISK_LEVEL),
    },
    fieldName: 'riskLevel',
    label: '风险等级',
    rules: 'required',
  },
  {
    component: 'Select',
    componentProps: {
      options: getDictOptions(DictEnum.AI_MCP_TOOL_STATUS),
    },
    fieldName: 'adminEnabled',
    label: '管理状态',
    rules: 'required',
  },
  {
    component: 'Select',
    componentProps: {
      options: getDictOptions(DictEnum.AI_MCP_CONFIRMATION),
    },
    fieldName: 'requireConfirmation',
    label: '调用确认',
    rules: 'required',
  },
  {
    component: 'Select',
    componentProps: {
      options: getDictOptions(DictEnum.AI_FLAG_YES_NO),
    },
    defaultValue: '0',
    fieldName: 'generalChatEnabled',
    help: '仅无 Agent 聊天使用；还需角色授权、低风险、只读且无需确认',
    label: '允许普通聊天',
    rules: 'required',
  },
  {
    component: 'InputNumber',
    fieldName: 'timeoutSeconds',
    label: '超时时间(秒)',
  },
  {
    component: 'Textarea',
    componentProps: {
      rows: 3,
    },
    fieldName: 'visibleResultFields',
    label: '模型可见字段',
  },
];

export const columns: VxeGridProps['columns'] = [
  { field: 'displayName', minWidth: 160, title: '显示名称' },
  {
    field: 'qualifiedName',
    minWidth: 250,
    showOverflow: true,
    title: '工具标识',
  },
  { field: 'serverName', minWidth: 150, title: '所属Server' },
  {
    field: 'description',
    minWidth: 260,
    showOverflow: true,
    title: '工具描述',
  },
  {
    align: 'center',
    field: 'sourceType',
    minWidth: 100,
    title: '来源',
    formatter({ cellValue }) {
      return dictLabel(DictEnum.AI_MCP_TOOL_SOURCE_TYPE, cellValue);
    },
  },
  {
    align: 'center',
    field: 'riskLevel',
    minWidth: 100,
    title: '风险等级',
    formatter({ cellValue }) {
      return dictLabel(DictEnum.AI_MCP_RISK_LEVEL, cellValue);
    },
  },
  {
    align: 'center',
    field: 'adminEnabled',
    minWidth: 100,
    slots: { default: 'status' },
    title: '管理状态',
  },
  { align: 'center', field: 'createTime', minWidth: 165, title: '创建时间' },
  {
    align: 'center',
    field: 'action',
    fixed: 'right',
    slots: { default: 'action' },
    title: '操作',
    width: 180,
  },
];
