import type { FormSchemaGetter } from '#/adapter/form';
import type { VxeGridProps } from '#/adapter/vxe-table';

export const querySchema: FormSchemaGetter = () => [
  {
    component: 'Input',
    fieldName: 'userName',
    label: '员工账号',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'Input',
    fieldName: 'nickName',
    label: '员工姓名',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'Select',
    componentProps: {
      style: { width: '240px' },
      options: [
        { label: '正常', value: '0' },
        { label: '冻结', value: '1' },
        { label: '关闭', value: '2' },
      ],
    },
    fieldName: 'status',
    label: '账户状态',
  },
  {
    component: 'Select',
    componentProps: {
      style: { width: '240px' },
      options: [
        { label: '限额', value: '0' },
        { label: '不限额', value: '1' },
      ],
    },
    fieldName: 'unlimited',
    label: '不限额',
  },
];

export const columns: VxeGridProps['columns'] = [
  { align: 'center', field: 'userName', minWidth: 120, title: '员工账号' },
  { align: 'center', field: 'nickName', minWidth: 110, title: '员工姓名' },
  { align: 'center', field: 'deptName', minWidth: 130, title: '部门' },
  { align: 'center', field: 'totalTokens', minWidth: 125, slots: { default: 'tokenNumber' }, title: '总额度' },
  { align: 'center', field: 'remainingTokens', minWidth: 125, slots: { default: 'tokenNumber' }, title: '剩余额度' },
  { align: 'center', field: 'usedTokens', minWidth: 125, slots: { default: 'tokenNumber' }, title: '已用额度' },
  { align: 'center', field: 'warningThreshold', minWidth: 125, slots: { default: 'tokenNumber' }, title: '告警阈值' },
  { align: 'center', field: 'unlimited', minWidth: 100, slots: { default: 'unlimited' }, title: '不限额' },
  { align: 'center', field: 'status', minWidth: 100, slots: { default: 'status' }, title: '账户状态' },
  { align: 'center', field: 'action', fixed: 'right', slots: { default: 'action' }, title: '操作', width: 220 },
];
