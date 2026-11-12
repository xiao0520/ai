import type { FormSchemaGetter } from '#/adapter/form';
import type { VxeGridProps } from '#/adapter/vxe-table';

import { DictEnum } from '@vben/constants';

import {
  agentKnowledgeOptions,
  agentMcpToolOptions,
  agentModelOptions,
} from '#/api/agent/agent';
import { skillOptions } from '#/api/agent/skill';
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
    fieldName: 'agentName',
    label: '智能体名称',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'Input',
    fieldName: 'agentCode',
    label: 'Agent编码',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'Select',
    componentProps: {
      style: { width: '240px' },
      options: getDictOptions(DictEnum.AI_AGENT_STATUS),
    },
    fieldName: 'status',
    label: '状态',
  },
];

export const columns: VxeGridProps['columns'] = [
  { type: 'checkbox', width: 60 },
  { type: 'seq', width: 50, title: '序号' },
  {
    title: '智能体名称',
    field: 'agentName',
    showOverflow: true,
    width: 180,
  },
  {
    title: 'Agent编码',
    field: 'agentCode',
    showOverflow: true,
    width: 180,
  },
  {
    title: '类型',
    field: 'agentType',
    width: 110,
    formatter({ cellValue }) {
      return dictLabel(DictEnum.AI_AGENT_TYPE, cellValue);
    },
  },
  {
    title: '描述',
    field: 'agentDescribe',
    showOverflow: true,
  },
  {
    title: '绑定模型',
    field: 'modelName',
    width: 160,
  },
  {
    title: '深度思考',
    field: 'enableThinking',
    width: 100,
    formatter({ cellValue }) {
      return dictLabel(DictEnum.AI_FLAG_YES_NO, cellValue);
    },
  },
  {
    title: '状态',
    field: 'status',
    width: 90,
    formatter({ cellValue }) {
      return dictLabel(DictEnum.AI_AGENT_STATUS, cellValue);
    },
  },
  {
    title: '创建时间',
    field: 'createTime',
    width: 160,
  },
  {
    field: 'action',
    fixed: 'right',
    slots: { default: 'action' },
    title: '操作',
    resizable: false,
    width: 'auto',
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
    componentProps: {
      placeholder: '例如 rd-knowledge-assistant，仅支持小写字母、数字、短横线',
    },
    fieldName: 'agentCode',
    label: 'Agent编码',
    rules: 'required',
  },
  {
    component: 'Input',
    fieldName: 'agentName',
    label: '智能体名称',
    rules: 'required',
    formItemClass: 'col-span-2',
  },
  {
    component: 'Select',
    componentProps: {
      options: getDictOptions(DictEnum.AI_AGENT_TYPE),
    },
    defaultValue: 'GENERAL',
    fieldName: 'agentType',
    label: 'Agent类型',
  },
  {
    component: 'Textarea',
    componentProps: {
      rows: 2,
    },
    fieldName: 'agentDescribe',
    formItemClass: 'col-span-2',
    label: '智能体描述',
  },
  {
    component: 'Input',
    fieldName: 'agentShow',
    label: '展示图标',
  },
  {
    component: 'ApiSelect',
    componentProps: {
      api: agentModelOptions,
      resultField: 'rows',
      labelField: 'modelName',
      valueField: 'id',
      mode: 'multiple',
      placeholder: '请选择可使用的聊天模型',
    },
    fieldName: 'modelIds',
    formItemClass: 'col-span-2',
    label: '绑定模型',
    rules: 'selectRequired',
  },
  {
    component: 'ApiSelect',
    componentProps: {
      api: agentModelOptions,
      resultField: 'rows',
      labelField: 'modelName',
      valueField: 'id',
      placeholder: '请选择默认模型',
    },
    fieldName: 'modelId',
    label: '默认模型',
    rules: 'selectRequired',
  },
  {
    component: 'RadioGroup',
    componentProps: {
      buttonStyle: 'solid',
      options: getDictOptions(DictEnum.AI_AGENT_MODEL_SELECT_POLICY),
      optionType: 'button',
    },
    defaultValue: 'USER_SELECT',
    fieldName: 'modelSelectPolicy',
    formItemClass: 'col-span-2',
    label: '用户选择模型',
  },
  {
    component: 'RadioGroup',
    componentProps: {
      buttonStyle: 'solid',
      options: getDictOptions(DictEnum.AI_FLAG_YES_NO),
      optionType: 'button',
    },
    defaultValue: '0',
    fieldName: 'enableThinking',
    label: '深度思考',
  },
  {
    component: 'ApiSelect',
    componentProps: {
      api: agentMcpToolOptions,
      labelField: 'label',
      valueField: 'id',
      mode: 'multiple',
      placeholder: '请选择关联的 MCP 工具',
    },
    fieldName: 'mcpToolIds',
    formItemClass: 'col-span-2',
    label: '关联工具',
  },
  {
    component: 'ApiSelect',
    componentProps: {
      api: skillOptions,
      labelField: 'description',
      valueField: 'name',
      mode: 'multiple',
      placeholder: '请选择关联的已启用 Skill',
    },
    fieldName: 'skillNames',
    formItemClass: 'col-span-2',
    label: '关联技能',
  },
  {
    component: 'ApiSelect',
    componentProps: {
      api: agentKnowledgeOptions,
      resultField: 'rows',
      labelField: 'name',
      valueField: 'id',
      mode: 'multiple',
      placeholder: '请选择关联的知识库',
    },
    fieldName: 'knowledgeIds',
    formItemClass: 'col-span-2',
    label: '关联知识库',
  },
  {
    component: 'RadioGroup',
    componentProps: {
      buttonStyle: 'solid',
      options: getDictOptions(DictEnum.AI_AGENT_KNOWLEDGE_SCOPE_POLICY),
      optionType: 'button',
    },
    defaultValue: 'ROLE_AUTHORIZED',
    fieldName: 'knowledgeScopePolicy',
    formItemClass: 'col-span-2',
    label: '知识库范围',
  },
  {
    component: 'Textarea',
    componentProps: {
      rows: 6,
    },
    fieldName: 'systemPrompt',
    formItemClass: 'col-span-2',
    label: '自定义提示词',
  },
  {
    component: 'Textarea',
    componentProps: {
      rows: 2,
    },
    fieldName: 'welcomeMessage',
    formItemClass: 'col-span-2',
    label: '欢迎语',
  },
  {
    component: 'Textarea',
    componentProps: {
      rows: 3,
      placeholder: 'JSON对象，例如 {"maxTurns":8,"timeoutSeconds":120,"maxToolCalls":5}',
    },
    fieldName: 'executionPolicy',
    formItemClass: 'col-span-2',
    label: '执行策略',
  },
  {
    component: 'RadioGroup',
    componentProps: {
      buttonStyle: 'solid',
      options: getDictOptions(DictEnum.AI_AGENT_STATUS),
      optionType: 'button',
    },
    defaultValue: '2',
    fieldName: 'status',
    label: '状态',
  },
  {
    component: 'Textarea',
    componentProps: {
      rows: 2,
    },
    fieldName: 'remark',
    formItemClass: 'col-span-2',
    label: '备注',
  },
];
