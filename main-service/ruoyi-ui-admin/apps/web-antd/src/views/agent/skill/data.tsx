import type { FormSchemaGetter } from '#/adapter/form';
import type { VxeGridProps } from '#/adapter/vxe-table';

import { DictEnum } from '@vben/constants';
import { getDictOptions } from '#/utils/dict';

export const runtimeOptions = getDictOptions(DictEnum.AI_SKILL_RUNTIME);

export const querySchema: FormSchemaGetter = () => [
  {
    component: 'Input',
    fieldName: 'displayName',
    label: 'Skill名称',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'Input',
    fieldName: 'skillName',
    label: 'Skill编码',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'Select',
    componentProps: {
      style: { width: '240px' },
      options: runtimeOptions,
    },
    fieldName: 'runtime',
    label: '运行时',
  },
  {
    component: 'Select',
    componentProps: {
      style: { width: '240px' },
      options: getDictOptions(DictEnum.SYS_NORMAL_DISABLE),
    },
    fieldName: 'status',
    label: '状态',
  },
];

export const drawerSchema: FormSchemaGetter = () => [
  {
    component: 'Input',
    fieldName: 'skillName',
    label: 'Skill编码',
    rules: 'required',
  },
  {
    component: 'Input',
    fieldName: 'displayName',
    label: 'Skill名称',
    rules: 'required',
  },
  {
    component: 'Select',
    componentProps: {
      options: runtimeOptions,
    },
    fieldName: 'runtime',
    label: '运行时',
    rules: 'required',
  },
  {
    component: 'Input',
    fieldName: 'entrypoint',
    label: '入口点',
    rules: 'required',
  },
  {
    component: 'Textarea',
    componentProps: {
      rows: 3,
    },
    fieldName: 'description',
    label: '能力说明',
  },
  {
    component: 'Textarea',
    componentProps: {
      rows: 4,
    },
    fieldName: 'parameterSchema',
    label: '参数 Schema',
  },
  {
    component: 'Textarea',
    componentProps: {
      rows: 4,
    },
    fieldName: 'outputSchema',
    label: '输出 Schema',
  },
  {
    component: 'Textarea',
    componentProps: {
      rows: 3,
    },
    fieldName: 'resourceLimits',
    label: '资源限制',
  },
  {
    component: 'Select',
    componentProps: {
      options: getDictOptions(DictEnum.SYS_NORMAL_DISABLE),
    },
    fieldName: 'status',
    label: '状态',
    rules: 'required',
  },
];

export const columns: VxeGridProps['columns'] = [
  { type: 'checkbox', width: 50 },
  { type: 'seq', width: 50, title: '序号' },
  {
    title: 'ID',
    field: 'id',
    visible: false,
  },
  {
    title: 'Skill编码',
    field: 'skillName',
    showOverflow: true,
    minWidth: 180,
  },
  {
    title: 'Skill名称',
    field: 'displayName',
    showOverflow: true,
    minWidth: 160,
  },
  {
    title: '运行时',
    field: 'runtime',
    minWidth: 120,
    formatter({ cellValue }) {
      return runtimeOptions.find((option) => option.value === cellValue)?.label ?? cellValue;
    },
  },
  {
    title: '状态',
    field: 'status',
    minWidth: 100,
    slots: { default: 'status' },
  },
  {
    title: '创建时间',
    field: 'createTime',
    minWidth: 165,
  },
  {
    title: '操作',
    field: 'action',
    width: 240,
    fixed: 'right',
    align: 'center',
    slots: { default: 'action' },
  },
];
