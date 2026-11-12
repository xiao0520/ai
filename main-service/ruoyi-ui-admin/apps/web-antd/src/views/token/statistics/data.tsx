import type { FormSchemaGetter } from '#/adapter/form';

export const querySchema: FormSchemaGetter = () => [
  {
    component: 'Input',
    fieldName: 'userName',
    label: '员工账号',
    componentProps: {
      allowClear: true,
      placeholder: '请输入员工账号',
    },
  },
  {
    component: 'Select',
    fieldName: 'modelId',
    label: '模型',
    componentProps: {
      allowClear: true,
      placeholder: '请选择模型',
      showSearch: true,
    },
  },
  {
    component: 'Input',
    fieldName: 'providerCode',
    label: '厂商编码',
    componentProps: {
      allowClear: true,
      placeholder: '请输入厂商编码',
    },
  },
  {
    component: 'RangePicker',
    fieldName: 'dateRange',
    label: '统计时间',
    componentProps: {
      allowClear: true,
      placeholder: ['开始日期', '结束日期'],
    },
  },
];
