import type { FormSchemaGetter } from '#/adapter/form';
import type { VxeGridProps } from '#/adapter/vxe-table';

import { h } from 'vue';

import { Tag } from 'ant-design-vue';

const accessPolicyOptions = [
  { color: 'orange', label: '私有', value: '0' },
  { color: 'green', label: '公开', value: '1' },
  { color: 'blue', label: '自定义', value: '2' },
];

export const querySchema: FormSchemaGetter = () => [
  {
    component: 'Input',
    fieldName: 'configKey',
    label: '配置名称',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'Input',
    fieldName: 'bucketName',
    label: '桶名称',
    componentProps: {
      style: { width: '240px' },
    },
  },
  {
    component: 'Select',
    componentProps: {
      style: { width: '240px' },
      options: [
        { label: '是', value: '0' },
        { label: '否', value: '1' },
      ],
    },
    fieldName: 'status',
    label: '是否默认',
  },
];

export const drawerSchema: FormSchemaGetter = () => [
  {
    component: 'Input',
    dependencies: {
      show: () => false,
      triggerFields: [''],
    },
    fieldName: 'ossConfigId',
    label: '配置主键',
  },
  {
    component: 'Input',
    defaultValue: '1',
    dependencies: {
      show: () => false,
      triggerFields: [''],
    },
    fieldName: 'status',
    label: '是否默认',
  },
  {
    component: 'Input',
    componentProps: { maxLength: 100 },
    fieldName: 'configKey',
    label: '配置名称',
    rules: 'required',
  },
  {
    component: 'Input',
    componentProps: { maxLength: 100 },
    fieldName: 'endpoint',
    label: '访问站点',
    rules: 'required',
  },
  {
    component: 'Input',
    fieldName: 'domain',
    label: '自定义域名',
  },
  {
    component: 'Input',
    dependencies: {
      show: (values) => !!values.domain,
      triggerFields: ['domain'],
    },
    fieldName: 'tip',
    hideLabel: true,
  },
  {
    component: 'Input',
    componentProps: { maxLength: 100 },
    fieldName: 'accessKey',
    label: 'Access Key',
    rules: 'required',
  },
  {
    component: 'InputPassword',
    componentProps: { maxLength: 100 },
    fieldName: 'secretKey',
    label: 'Secret Key',
    rules: 'required',
  },
  {
    component: 'Input',
    componentProps: { maxLength: 100 },
    fieldName: 'bucketName',
    label: '桶名称',
    rules: 'required',
  },
  {
    component: 'Input',
    fieldName: 'prefix',
    label: '文件前缀',
  },
  {
    component: 'Input',
    fieldName: 'region',
    label: '域',
  },
  {
    component: 'RadioGroup',
    componentProps: {
      options: [
        { label: '是', value: 'Y' },
        { label: '否', value: 'N' },
      ],
    },
    defaultValue: 'Y',
    fieldName: 'isHttps',
    label: '使用 HTTPS',
    rules: 'selectRequired',
  },
  {
    component: 'RadioGroup',
    componentProps: {
      options: accessPolicyOptions,
    },
    defaultValue: '0',
    fieldName: 'accessPolicy',
    label: '访问策略',
    rules: 'selectRequired',
  },
  {
    component: 'Textarea',
    fieldName: 'ext1',
    label: '扩展配置',
  },
  {
    component: 'Textarea',
    fieldName: 'remark',
    label: '备注',
  },
];

export const columns: VxeGridProps['columns'] = [
  { type: 'checkbox', width: 60 },
  {
    title: '配置名称',
    field: 'configKey',
  },
  {
    title: '访问站点',
    field: 'endpoint',
    showOverflow: true,
  },
  {
    title: '桶名称',
    field: 'bucketName',
  },
  {
    title: '域',
    field: 'region',
  },
  {
    title: '访问策略',
    field: 'accessPolicy',
    slots: {
      default: ({ row }) => {
        const option = accessPolicyOptions.find(
          (item) => item.value === row.accessPolicy,
        );
        return h(Tag, { color: option?.color }, () => option?.label);
      },
    },
  },
  {
    title: '是否默认',
    field: 'status',
    slots: {
      default: ({ row }) => {
        return row.status === '0' ? '是' : '否';
      },
    },
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
    width: 200,
  },
];
