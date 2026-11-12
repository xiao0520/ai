import type { FormSchemaGetter } from '#/adapter/form';
import type { VxeGridProps } from '#/adapter/vxe-table';

import { DictEnum } from '@vben/constants';
import { getPopupContainer } from '@vben/utils';

import { getDictOptions } from '#/utils/dict';
import { renderDict } from '#/utils/render';

export const querySchema: FormSchemaGetter = () => [
  {
    component: 'Input',
    fieldName: 'postCode',
    label: '岗位编码',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'Input',
    fieldName: 'postName',
    label: '岗位名称',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'Select',
    componentProps: {
      getPopupContainer,
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
    dependencies: { show: () => false, triggerFields: [''] },
    fieldName: 'postId',
    label: '岗位主键',
  },
  {
    component: 'TreeSelect',
    componentProps: {
      getPopupContainer,
      showSearch: true,
      treeNodeFilterProp: 'label',
    },
    fieldName: 'deptId',
    label: '所属部门',
    rules: 'selectRequired',
  },
  {
    component: 'Input',
    componentProps: { maxLength: 64 },
    fieldName: 'postCode',
    label: '岗位编码',
    rules: 'required',
  },
  {
    component: 'Input',
    componentProps: { maxLength: 50 },
    fieldName: 'postName',
    label: '岗位名称',
    rules: 'required',
  },
  {
    component: 'Input',
    componentProps: { maxLength: 100 },
    fieldName: 'postCategory',
    label: '类别编码',
  },
  {
    component: 'InputNumber',
    componentProps: { min: 0, precision: 0 },
    defaultValue: 0,
    fieldName: 'postSort',
    label: '显示顺序',
    rules: 'required',
  },
  {
    component: 'RadioGroup',
    componentProps: {
      buttonStyle: 'solid',
      options: getDictOptions(DictEnum.SYS_NORMAL_DISABLE),
      optionType: 'button',
    },
    defaultValue: '0',
    fieldName: 'status',
    label: '状态',
    rules: 'selectRequired',
  },
  {
    component: 'Textarea',
    fieldName: 'remark',
    label: '备注',
  },
];

export const columns: VxeGridProps['columns'] = [
  {
    title: '岗位编号',
    field: 'postId',
  },
  {
    title: '岗位编码',
    field: 'postCode',
  },
  {
    title: '岗位名称',
    field: 'postName',
  },
  {
    title: '排序',
    field: 'postSort',
  },
  {
    title: '状态',
    field: 'status',
    slots: {
      default: ({ row }) => {
        return renderDict(row.status, DictEnum.SYS_NORMAL_DISABLE);
      },
    },
  },
  {
    title: '创建时间',
    field: 'createTime',
  },
  {
    title: '操作',
    field: 'action',
    fixed: 'right',
    slots: { default: 'action' },
    width: 200,
  },
];
