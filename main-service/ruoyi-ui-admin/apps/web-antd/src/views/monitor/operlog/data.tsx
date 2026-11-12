import type { FormSchemaGetter } from '#/adapter/form';
import type { VxeGridProps } from '#/adapter/vxe-table';

import { DictEnum } from '@vben/constants';

import { getDictOptions } from '#/utils/dict';
import { renderDict } from '#/utils/render';

export const querySchema: FormSchemaGetter = () => [
  {
    component: 'Input',
    fieldName: 'title',
    label: '系统模块',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'Input',
    fieldName: 'operName',
    label: '操作人员',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'Select',
    componentProps: {
      style: { width: '240px' },
      options: getDictOptions(DictEnum.SYS_OPER_TYPE),
    },
    fieldName: 'businessType',
    label: '操作类型',
  },
  {
    component: 'Input',
    fieldName: 'operIp',
    label: '操作IP',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'Select',
    componentProps: {
      style: { width: '240px' },
      options: getDictOptions(DictEnum.SYS_COMMON_STATUS),
    },
    fieldName: 'status',
    label: '状态',
    formItemClass: 'col-start-1 row-start-2',
  },
  {
    component: 'RangePicker',
    fieldName: 'createTime',
    label: '操作时间',
    formItemClass: 'row-start-2',
    componentProps: {
      valueFormat: 'YYYY-MM-DD HH:mm:ss',
    },
  },
];

export const columns: VxeGridProps['columns'] = [
  { type: 'checkbox', width: 60 },
  { field: 'title', title: '系统模块' },
  {
    title: '操作类型',
    field: 'businessType',
    slots: {
      default: ({ row }) => {
        return renderDict(row.businessType, DictEnum.SYS_OPER_TYPE);
      },
    },
  },
  {
    title: '请求方式',
    field: 'requestMethod',
  },
  {
    title: '操作人员',
    field: 'operName',
  },
  {
    title: '部门名称',
    field: 'deptName',
  },
  {
    title: '操作地址',
    field: 'operIp',
  },
  {
    title: '操作地点',
    field: 'operLocation',
  },
  {
    title: '操作状态',
    field: 'status',
    slots: {
      default: ({ row }) => {
        return renderDict(row.status, DictEnum.SYS_COMMON_STATUS);
      },
    },
  },
  {
    title: '操作时间',
    field: 'operTime',
  },
  {
    title: '操作',
    field: 'action',
    fixed: 'right',
    slots: { default: 'action' },
    width: 120,
  },
];
