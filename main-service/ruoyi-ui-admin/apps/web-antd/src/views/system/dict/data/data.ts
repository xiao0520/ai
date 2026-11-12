import type { FormSchemaGetter } from '#/adapter/form';
import type { VxeGridProps } from '#/adapter/vxe-table';
import type { DictData } from '#/api/system/dict/dict-data-model';

import { renderDictTag } from '#/utils/render';

export const querySchema: FormSchemaGetter = () => [
  {
    component: 'Input',
    fieldName: 'dictLabel',
    label: '字典标签',
    componentProps: {
      style: { width: '240px' },
    },
  },
];

export const columns: VxeGridProps['columns'] = [
  { type: 'checkbox', width: 60 },
  { type: 'seq', title: '序号', width: 60 },
  {
    title: '字典标签',
    field: 'cssClass',
    slots: {
      default: ({ row }) => {
        const { dictValue } = row as DictData;
        return renderDictTag(dictValue, [row]);
      },
    },
  },
  {
    title: '字典键值',
    field: 'dictValue',
  },
  {
    title: '字典排序',
    field: 'dictSort',
  },
  {
    title: '备注',
    field: 'remark',
  },
  {
    title: '创建时间',
    field: 'createTime',
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
    fieldName: 'dictCode',
    label: 'dictCode',
  },
  {
    component: 'Input',
    fieldName: 'dictLabel',
    label: '字典标签',
    rules: 'required',
  },
  {
    component: 'Input',
    fieldName: 'dictValue',
    label: '字典键值',
    rules: 'required',
  },
  {
    component: 'InputNumber',
    fieldName: 'dictSort',
    label: '字典排序',
    rules: 'required',
  },
  {
    component: 'Textarea',
    fieldName: 'remark',
    label: '备注',
  },
];
