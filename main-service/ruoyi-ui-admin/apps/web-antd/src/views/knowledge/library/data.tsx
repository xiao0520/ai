import type { FormSchemaGetter } from '#/adapter/form';
import type { VxeGridProps } from '#/adapter/vxe-table';

import { DictEnum } from '@vben/constants';

import { getDictOptions } from '#/utils/dict';
export const querySchema: FormSchemaGetter = () => [
  { component: 'Input', fieldName: 'name', label: '知识库名称' },
  { component: 'Input', fieldName: 'libraryCode', label: '知识库编码' },
  {
    component: 'Select',
    componentProps: {
      options: getDictOptions(DictEnum.SYS_NORMAL_DISABLE),
    },
    fieldName: 'status',
    label: '状态',
  },
];

export const columns: VxeGridProps['columns'] = [
  { type: 'checkbox', width: 50 },
  { type: 'seq', width: 50, title: '序号' },
  { field: 'name', minWidth: 180, title: '知识库名称' },
  { field: 'libraryCode', minWidth: 180, title: '知识库编码' },
  {
    align: 'center',
    field: 'accessScope',
    minWidth: 125,
    slots: { default: 'accessScope' },
    title: '访问范围',
  },
  { field: 'description', minWidth: 240, showOverflow: true, title: '说明' },
  { align: 'center', field: 'documentCount', minWidth: 90, title: '文档数' },
  { align: 'center', field: 'currentIndexVersion', minWidth: 145, title: '当前索引版本' },
  {
    align: 'center',
    field: 'status',
    minWidth: 90,
    slots: { default: 'status' },
    title: '状态',
  },
  { align: 'center', field: 'updateTime', minWidth: 165, title: '更新时间' },
  {
    align: 'center',
    field: 'action',
    fixed: 'right',
    slots: { default: 'action' },
    title: '操作',
    width: 230,
  },
];

export const drawerSchema: FormSchemaGetter = () => [
  {
    component: 'Input',
    dependencies: { show: () => false, triggerFields: ['id'] },
    fieldName: 'id',
    label: 'ID',
  },
  {
    component: 'Input',
    componentProps: { maxlength: 100 },
    fieldName: 'name',
    label: '知识库名称',
    rules: 'required',
  },
  {
    component: 'Input',
    componentProps: { placeholder: '留空由系统生成，例如 hr-policy' },
    fieldName: 'libraryCode',
    help: '创建后编码不可修改，供后续 Python 解析与检索侧稳定引用。',
    label: '知识库编码',
  },
  {
    component: 'Select',
    componentProps: {
      options: getDictOptions(DictEnum.AI_KNOWLEDGE_ACCESS_SCOPE),
    },
    defaultValue: 'ACL_ONLY',
    fieldName: 'accessScope',
    label: '访问范围',
    rules: 'selectRequired',
  },
  {
    component: 'Textarea',
    componentProps: { maxlength: 500, rows: 3, showCount: true },
    fieldName: 'description',
    formItemClass: 'col-span-2',
    label: '知识库说明',
  },
  {
    component: 'Textarea',
    componentProps: {
      placeholder: '{"chunkSize":800,"overlap":100}',
      rows: 4,
    },
    fieldName: 'splitterConfig',
    formItemClass: 'col-span-2',
    help: '仅保存切分策略；第一阶段不会在 Java 端执行解析。必须填写合法 JSON。',
    label: '切分策略',
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
  },
  {
    component: 'Textarea',
    componentProps: { maxlength: 500, rows: 3, showCount: true },
    fieldName: 'remark',
    formItemClass: 'col-span-2',
    label: '备注',
  },
];
