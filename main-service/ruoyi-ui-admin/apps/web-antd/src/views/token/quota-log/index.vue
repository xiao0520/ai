<script setup lang="ts">
import type { VbenFormProps } from '@vben/common-ui';

import type { VxeGridProps } from '#/adapter/vxe-table';

import { Page } from '@vben/common-ui';

import { Tag } from 'ant-design-vue';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import { tokenQuotaLogList } from '#/api/token';

import { columns, operationOptions, querySchema } from './data';

const formatter = new Intl.NumberFormat('zh-CN');
const operationMap = Object.fromEntries(
  operationOptions.map((item) => [item.value, item.label]),
);

const formOptions: VbenFormProps = {
  commonConfig: {
    componentProps: { allowClear: true },
    labelWidth: 80,
  },
  fieldMappingTime: [
    [
      'operationTime',
      ['beginTime', 'endTime'],
      ['YYYY-MM-DD 00:00:00', 'YYYY-MM-DD 23:59:59'],
    ],
  ],
  schema: querySchema(),
  wrapperClass: 'grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-x-[60px]',
};

const gridOptions: VxeGridProps = {
  columns,
  height: 'auto',
  pagerConfig: {},
  proxyConfig: {
    ajax: {
      query: async ({ page }, formValues = {}) =>
        tokenQuotaLogList({
          pageNum: page.currentPage,
          pageSize: page.pageSize,
          ...formValues,
        }),
    },
  },
  rowConfig: { keyField: 'id' },
  id: 'ai-token-quota-log-index',
};

const [BasicTable] = useVbenVxeGrid({ formOptions, gridOptions });

function formatTokens(value?: number) {
  return formatter.format(value || 0);
}
</script>

<template>
  <Page :auto-content-height="true">
    <BasicTable table-title="额度调整流水">
      <template #operationType="{ row }">
        <Tag color="blue">{{ operationMap[row.operationType] || row.operationType }}</Tag>
      </template>
      <template #changeTokens="{ row }">
        <span :class="row.changeTokens > 0 ? 'text-green-600' : row.changeTokens < 0 ? 'text-red-500' : ''">
          {{ row.changeTokens > 0 ? '+' : '' }}{{ formatTokens(row.changeTokens) }}
        </span>
      </template>
      <template #tokenNumber="{ row, column }">
        {{ formatTokens(row[column.field]) }}
      </template>
    </BasicTable>
  </Page>
</template>
