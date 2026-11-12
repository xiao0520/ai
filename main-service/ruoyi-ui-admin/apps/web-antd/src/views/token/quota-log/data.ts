import type { FormSchemaGetter } from '#/adapter/form';
import type { VxeGridProps } from '#/adapter/vxe-table';

export const operationOptions = [
  { label: '首次分配', value: 'INITIAL_GRANT' },
  { label: '追加额度', value: 'APPEND_GRANT' },
  { label: '设置剩余', value: 'SET_REMAINING' },
  { label: '修改告警阈值', value: 'UPDATE_WARNING_THRESHOLD' },
  { label: '开启不限额', value: 'ENABLE_UNLIMITED' },
  { label: '关闭不限额', value: 'DISABLE_UNLIMITED' },
  { label: '冻结', value: 'FREEZE' },
  { label: '解冻', value: 'UNFREEZE' },
];

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
    component: 'Select',
    componentProps: {
      style: { width: '240px' },
      options: operationOptions,
    },
    fieldName: 'operationType',
    label: '操作类型',
  },
  {
    component: 'RangePicker',
    fieldName: 'operationTime',
    label: '操作时间',
  },
];

export const columns: VxeGridProps['columns'] = [
  { field: 'userName', minWidth: 120, title: '员工账号' },
  { field: 'nickName', minWidth: 110, title: '员工姓名' },
  { field: 'deptName', minWidth: 130, title: '部门' },
  { field: 'operationType', minWidth: 130, slots: { default: 'operationType' }, title: '操作类型' },
  { field: 'changeTokens', minWidth: 120, slots: { default: 'changeTokens' }, title: '变化量' },
  { field: 'beforeTotal', minWidth: 125, slots: { default: 'tokenNumber' }, title: '调整前总额' },
  { field: 'afterTotal', minWidth: 125, slots: { default: 'tokenNumber' }, title: '调整后总额' },
  { field: 'beforeRemaining', minWidth: 125, slots: { default: 'tokenNumber' }, title: '调整前剩余' },
  { field: 'afterRemaining', minWidth: 125, slots: { default: 'tokenNumber' }, title: '调整后剩余' },
  { field: 'operatorName', minWidth: 110, title: '操作人' },
  { field: 'reason', minWidth: 220, title: '操作原因' },
  { field: 'createTime', minWidth: 165, title: '操作时间' },
];
