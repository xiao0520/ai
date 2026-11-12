import type { FormSchemaGetter } from '#/adapter/form';
import type { VxeGridProps } from '#/adapter/vxe-table';

import { DictEnum } from '@vben/constants';

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
    fieldName: 'serverName',
    label: 'Server名称',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'Input',
    fieldName: 'serverCode',
    label: 'Server编码',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'Select',
    componentProps: {
      style: { width: '240px' },
      options: getDictOptions(DictEnum.AI_MCP_TRANSPORT_TYPE),
    },
    fieldName: 'transportType',
    label: '传输方式',
  },
  {
    component: 'Select',
    componentProps: {
      style: { width: '240px' },
      options: getDictOptions(DictEnum.AI_MCP_SERVER_STATUS),
    },
    fieldName: 'status',
    label: '状态',
  },
];

export const drawerSchema: FormSchemaGetter = () => [
  {
    component: 'Input',
    fieldName: 'serverName',
    label: 'Server名称',
    rules: 'required',
  },
  {
    component: 'Input',
    fieldName: 'serverCode',
    label: 'Server编码',
    rules: 'required',
  },
  {
    component: 'Select',
    componentProps: {
      options: getDictOptions(DictEnum.AI_MCP_SERVER_TYPE),
    },
    fieldName: 'serverType',
    label: 'Server类型',
    rules: 'required',
  },
  {
    component: 'Select',
    componentProps: {
      options: getDictOptions(DictEnum.AI_MCP_TRANSPORT_TYPE),
    },
    fieldName: 'transportType',
    label: '传输方式',
    rules: 'required',
  },
  {
    component: 'Input',
    fieldName: 'endpoint',
    help: 'Streamable HTTP 服务地址，例如 http://127.0.0.1:3001/mcp',
    label: '服务地址',
  },
  {
    component: 'Input',
    fieldName: 'runtimeKey',
    help: '仅 STDIO 配置使用；当前聊天工具执行只支持 Streamable HTTP',
    label: '运行时键',
  },
  {
    component: 'Input',
    fieldName: 'protocolVersion',
    label: '协议版本',
    rules: 'required',
  },
  {
    component: 'Select',
    componentProps: {
      options: getDictOptions(DictEnum.AI_MCP_AUTH_TYPE),
    },
    defaultValue: 'NONE',
    fieldName: 'authType',
    help: '当前聊天工具执行只支持无需认证和 Bearer Token',
    label: '认证方式',
    rules: 'required',
  },
  {
    component: 'Input',
    fieldName: 'credentialKey',
    help: 'Bearer 时填写 Python AI_PROVIDER_CREDENTIALS 中的别名，不填写密钥原文',
    label: '凭证别名',
  },
  {
    component: 'InputNumber',
    componentProps: { min: 1, max: 300 },
    defaultValue: 30,
    fieldName: 'connectTimeout',
    label: '连接超时（秒）',
    rules: 'required',
  },
  {
    component: 'Select',
    componentProps: {
      options: getDictOptions(DictEnum.AI_MCP_RISK_LEVEL),
    },
    defaultValue: 'LOW',
    fieldName: 'defaultRiskLevel',
    label: '默认风险等级',
    rules: 'required',
  },
  {
    component: 'Select',
    componentProps: {
      options: getDictOptions(DictEnum.AI_MCP_SERVER_STATUS),
    },
    defaultValue: 'ENABLED',
    fieldName: 'status',
    label: '状态',
    rules: 'required',
  },
  {
    component: 'Textarea',
    fieldName: 'remark',
    formItemClass: 'col-span-2',
    label: '备注',
  },
];

export const columns: VxeGridProps['columns'] = [
  { type: 'checkbox', width: 50 },
  { type: 'seq', width: 50, title: '序号' },
  { field: 'serverName', minWidth: 160, title: 'Server名称' },
  { field: 'serverCode', minWidth: 160, title: 'Server编码' },
  {
    align: 'center',
    field: 'serverType',
    minWidth: 120,
    title: 'Server类型',
    formatter({ cellValue }) {
      return dictLabel(DictEnum.AI_MCP_SERVER_TYPE, cellValue);
    },
  },
  {
    align: 'center',
    field: 'transportType',
    minWidth: 145,
    title: '传输方式',
    formatter({ cellValue }) {
      return dictLabel(DictEnum.AI_MCP_TRANSPORT_TYPE, cellValue);
    },
  },
  { field: 'endpoint', minWidth: 240, showOverflow: true, title: '服务地址/运行时' },
  { align: 'center', field: 'protocolVersion', minWidth: 120, title: '协议版本' },
  { align: 'center', field: 'status', minWidth: 100, slots: { default: 'status' }, title: '状态' },
  { align: 'center', field: 'createTime', minWidth: 165, title: '创建时间' },
  { align: 'center', field: 'action', fixed: 'right', slots: { default: 'action' }, title: '操作', width: 220 },
];
