import type { FormSchemaGetter } from '#/adapter/form';
import type { VxeGridProps } from '#/adapter/vxe-table';

import { DictEnum } from '@vben/constants';
import { getPopupContainer } from '@vben/utils';

import { z } from '#/adapter/form';
import { getDictOptions } from '#/utils/dict';

export const querySchema: FormSchemaGetter = () => [
  {
    component: 'Input',
    fieldName: 'userName',
    label: '用户账号',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'Input',
    fieldName: 'nickName',
    label: '用户昵称',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'Input',
    fieldName: 'phonenumber',
    label: '手机号码',
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
    label: '用户状态',
  },
  {
    component: 'RangePicker',
    fieldName: 'createTime',
    label: '创建时间',
    formItemClass: 'col-start-1 row-start-2',
  },
];

export const drawerSchema: FormSchemaGetter = () => [
  {
    component: 'Input',
    dependencies: { show: () => false, triggerFields: [''] },
    fieldName: 'userId',
    label: '用户主键',
  },
  {
    component: 'Input',
    componentProps: { maxLength: 30 },
    fieldName: 'userName',
    label: '用户账号',
    rules: 'required',
  },
  {
    component: 'Input',
    componentProps: { maxLength: 30 },
    fieldName: 'nickName',
    label: '用户昵称',
    rules: 'required',
  },
  {
    component: 'InputPassword',
    componentProps: { maxLength: 20 },
    fieldName: 'password',
    label: '用户密码',
    rules: z.string().min(5, '密码长度为5 - 20').max(20, '密码长度为5 - 20'),
  },
  {
    component: 'TreeSelect',
    componentProps: { getPopupContainer },
    fieldName: 'deptId',
    label: '所属部门',
    rules: 'selectRequired',
  },
  {
    component: 'Select',
    componentProps: {
      getPopupContainer,
      mode: 'multiple',
      options: [],
      placeholder: '请先选择部门',
    },
    defaultValue: [],
    fieldName: 'postIds',
    label: '岗位',
  },
  {
    component: 'Select',
    componentProps: {
      getPopupContainer,
      mode: 'multiple',
      optionFilterProp: 'title',
      options: [],
    },
    defaultValue: [],
    fieldName: 'roleIds',
    label: '角色',
    rules: 'selectRequired',
  },
  {
    component: 'Input',
    componentProps: { maxLength: 11 },
    fieldName: 'phonenumber',
    label: '手机号码',
    rules: z
      .string()
      .regex(/^1[3-9]\d{9}$/, '请输入正确的手机号码')
      .or(z.literal(''))
      .optional(),
  },
  {
    component: 'Input',
    componentProps: { maxLength: 50 },
    fieldName: 'email',
    label: '邮箱',
    rules: z.string().email('请输入正确的邮箱').or(z.literal('')).optional(),
  },
  {
    component: 'RadioGroup',
    componentProps: { options: getDictOptions(DictEnum.SYS_USER_SEX) },
    defaultValue: '2',
    fieldName: 'sex',
    label: '性别',
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
    field: 'userId',
    title: '用户编号',
    minWidth: 90,
  },
  {
    field: 'userName',
    title: '用户名称',
    minWidth: 120,
  },
  {
    field: 'nickName',
    title: '用户昵称',
    minWidth: 130,
  },
  {
    field: 'deptName',
    title: '部门',
    minWidth: 130,
  },
  {
    field: 'phonenumber',
    title: '手机号码',
    minWidth: 120,
  },
  {
    field: 'status',
    title: '状态',
    minWidth: 90,
    slots: { default: 'status' },
  },
  {
    field: 'createTime',
    title: '创建时间',
    minWidth: 165,
  },
  {
    field: 'action',
    title: '操作',
    minWidth: 200,
    fixed: 'right',
    align: 'center',
    slots: { default: 'action' },
  },
];
