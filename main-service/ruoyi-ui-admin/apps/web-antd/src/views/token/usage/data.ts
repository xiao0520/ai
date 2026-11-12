import type { FormSchemaGetter } from '#/adapter/form';
import type { VxeGridProps } from '#/adapter/vxe-table';

import { tokenModelOptions } from '#/api/token';

export const querySchema: FormSchemaGetter = () => [
  { component: 'Input', fieldName: 'userName', label: '员工账号' },
  {
    component: 'ApiSelect',
    componentProps: {
      api: tokenModelOptions,
      labelField: 'modelName',
      placeholder: '请选择模型',
      valueField: 'modelId',
    },
    fieldName: 'modelId',
    label: '模型',
  },
  { component: 'Input', fieldName: 'providerCode', label: '厂商编码' },
  {
    component: 'Select',
    componentProps: {
      options: [
        { label: '预占', value: 'RESERVED' },
        { label: '成功', value: 'SUCCESS' },
        { label: '失败', value: 'FAILED' },
        { label: '已取消', value: 'CANCELED' },
        { label: '已过期', value: 'EXPIRED' },
      ],
    },
    fieldName: 'status',
    label: '调用状态',
  },
  {
    component: 'Select',
    componentProps: {
      options: [
        { label: '厂商返回', value: 'PROVIDER' },
        { label: '系统估算', value: 'ESTIMATE' },
      ],
    },
    fieldName: 'usageSource',
    label: 'Token来源',
    formItemClass: 'col-start-1 row-start-2',
  },
  { component: 'RangePicker', fieldName: 'usageTime', label: '调用时间', formItemClass: 'row-start-2' },
];

export const columns: VxeGridProps['columns'] = [
  { field: 'userName', minWidth: 120, title: '员工账号' },
  { field: 'nickName', minWidth: 110, title: '员工姓名' },
  { field: 'deptName', minWidth: 130, title: '部门' },
  { field: 'modelName', minWidth: 180, title: '模型' },
  { field: 'providerCode', minWidth: 110, title: '厂商' },
  {
    field: 'inputTokens',
    minWidth: 110,
    slots: { default: 'tokenNumber' },
    title: '输入Token',
  },
  {
    field: 'outputTokens',
    minWidth: 110,
    slots: { default: 'tokenNumber' },
    title: '输出Token',
  },
  {
    field: 'actualTokens',
    minWidth: 110,
    slots: { default: 'tokenNumber' },
    title: '实际Token',
  },
  {
    field: 'chargedTokens',
    minWidth: 110,
    slots: { default: 'chargedTokens' },
    title: '扣减Token',
  },
  { field: 'usageSource', minWidth: 100, slots: { default: 'source' }, title: '来源' },
  { field: 'status', minWidth: 100, slots: { default: 'status' }, title: '状态' },
  { field: 'startTime', minWidth: 165, title: '开始时间' },
  { field: 'callId', minWidth: 210, title: 'Call ID' },
  {
    field: 'action',
    fixed: 'right',
    slots: { default: 'action' },
    title: '操作',
    width: 80,
  },
];
