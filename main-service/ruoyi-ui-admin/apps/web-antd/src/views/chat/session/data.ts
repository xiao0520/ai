import type { FormSchemaGetter } from '#/adapter/form';
import type { VxeGridProps } from '#/adapter/vxe-table';

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
    fieldName: 'sessionTitle',
    label: '会话标题',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'Input',
    fieldName: 'id',
    label: '会话 ID',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'Select',
    componentProps: {
      style: { width: '240px' },
      options: [
        { label: '进行中', value: 'ACTIVE' },
        { label: '已归档', value: 'ARCHIVED' },
      ],
    },
    fieldName: 'status',
    label: '状态',
  },
];

export const columns: VxeGridProps['columns'] = [
  { align: 'center', field: 'id', minWidth: 180, title: '会话 ID' },
  { align: 'center', field: 'userName', minWidth: 140, title: '用户账号' },
  { field: 'sessionTitle', minWidth: 180, showOverflow: true, title: '会话标题' },
  { align: 'center', field: 'messageCount', minWidth: 90, title: '消息数' },
  { align: 'center', field: 'deductTokens', minWidth: 115, title: '扣减 Token' },
  { align: 'center', field: 'status', minWidth: 95, slots: { default: 'status' }, title: '状态' },
  { align: 'center', field: 'lastMessageAt', minWidth: 165, title: '最后消息时间' },
  { align: 'center', field: 'updateTime', minWidth: 165, title: '更新时间' },
  { align: 'center', field: 'action', fixed: 'right', slots: { default: 'action' }, title: '操作', width: 180 },
];
