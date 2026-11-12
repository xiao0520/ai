import type { FormSchemaGetter } from '#/adapter/form';
import type { VxeGridProps } from '#/adapter/vxe-table';

export const querySchema: FormSchemaGetter = () => [
  { component: 'Input', fieldName: 'roleName', label: '角色名称' },
  { component: 'Input', fieldName: 'roleKey', label: '权限字符' },
  {
    component: 'Select',
    componentProps: {
      options: [
        { label: '正常', value: '0' },
        { label: '停用', value: '1' },
      ],
    },
    fieldName: 'status',
    label: '角色状态',
  },
];

export const columns: VxeGridProps['columns'] = [
  { align: 'center', field: 'roleName', minWidth: 150, title: '角色名称' },
  { align: 'center', field: 'roleKey', minWidth: 150, title: '权限字符' },
  {
    align: 'center',
    field: 'roleStatus',
    minWidth: 90,
    slots: { default: 'status' },
    title: '角色状态',
  },
  {
    align: 'center',
    field: 'tools',
    minWidth: 460,
    slots: { default: 'tools' },
    title: '已授权工具',
  },
  {
    align: 'center',
    field: 'effectiveTime',
    minWidth: 165,
    slots: { default: 'effectiveTime' },
    title: '生效时间',
  },
  {
    align: 'center',
    field: 'expireTime',
    minWidth: 165,
    slots: { default: 'expireTime' },
    title: '失效时间',
  },
  { align: 'center', field: 'remark', minWidth: 180, title: '备注' },
  {
    align: 'center',
    field: 'action',
    fixed: 'right',
    slots: { default: 'action' },
    title: '操作',
    width: 120,
  },
];
