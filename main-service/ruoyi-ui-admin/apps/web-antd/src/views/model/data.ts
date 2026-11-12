import type { FormSchemaGetter } from '#/adapter/form';
import type { VxeGridProps } from '#/adapter/vxe-table';

export const querySchema: FormSchemaGetter = () => [
  {
    component: 'Input',
    fieldName: 'modelName',
    label: '模型名称',
    componentProps: {
      style: { width: '240px' },
    },
  },
];

export const columns: VxeGridProps['columns'] = [
  { type: 'checkbox', width: 60 },
  { type: 'seq', title: '序号', width: 60 },
  {
    title: '模型名称',
    field: 'modelName',
  },
  {
    title: '模型描述',
    field: 'modelDescribe',
  },
  {
    title: '模型供应商',
    field: 'providerCode',
  },
  {
    title: '模型分类',
    field: 'category',
    slots: { default: 'category' },
  },
  {
    title: '用户端可见',
    field: 'modelShow',
    slots: { default: 'modelShow' },
  },
  {
    title: '上下文窗口',
    field: 'maxContextTokens',
    formatter: ({ cellValue }) => cellValue ? `${cellValue} Token` : '-',
  },

  {
    field: 'action',
    fixed: 'right',
    slots: { default: 'action' },
    title: '操作',
    width: 180,
  },
];
