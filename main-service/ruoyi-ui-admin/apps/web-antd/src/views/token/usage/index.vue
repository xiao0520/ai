<script setup lang="ts">
import type { VbenFormProps } from '@vben/common-ui';

import type { VxeGridProps } from '#/adapter/vxe-table';
import type { TokenUsage } from '#/api/token/model';

import { ref } from 'vue';

import { Page } from '@vben/common-ui';

import { Descriptions, DescriptionsItem, Modal, Tag } from 'ant-design-vue';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import { tokenUsageList } from '#/api/token';

import { columns, querySchema } from './data';

const formatter = new Intl.NumberFormat('zh-CN');
const detailOpen = ref(false);
const detail = ref<TokenUsage>();

const statusMap: Record<string, { color: string; text: string }> = {
  CANCELED: { color: 'default', text: '已取' },
  EXPIRED: { color: 'orange', text: '已过' },
  FAILED: { color: 'error', text: '失败' },
  RESERVED: { color: 'processing', text: '预占' },
  SUCCESS: { color: 'success', text: '成功' },
};

const formOptions: VbenFormProps = {
  commonConfig: {
    componentProps: { allowClear: true },
    labelWidth: 80,
  },
  fieldMappingTime: [
    [
      'usageTime',
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
        tokenUsageList({
          pageNum: page.currentPage,
          pageSize: page.pageSize,
          ...formValues,
        }),
    },
  },
  rowConfig: { keyField: 'id' },
  id: 'ai-token-usage-index',
};

const [BasicTable] = useVbenVxeGrid({ formOptions, gridOptions });

function showDetail(row: TokenUsage) {
  detail.value = row;
  detailOpen.value = true;
}

function formatTokens(value?: number) {
  return formatter.format(value || 0);
}
</script>

<template>
  <Page :auto-content-height="true">
    <BasicTable table-title="Token 使用明细">
      <template #tokenNumber="{ row, column }">
        {{ formatTokens(row[column.field]) }}
      </template>
      <template #chargedTokens="{ row }">
        <span class="font-medium text-blue-600">{{ formatTokens(row.chargedTokens) }}</span>
      </template>
      <template #source="{ row }">
        <Tag>{{ row.usageSource === 'PROVIDER' ? '厂商返回' : row.usageSource === 'ESTIMATE' ? '系统估算' : row.usageSource || '-' }}</Tag>
      </template>
      <template #status="{ row }">
        <Tag :color="statusMap[row.status]?.color || 'default'">
          {{ statusMap[row.status]?.text || row.status }}
        </Tag>
      </template>
      <template #action="{ row }">
        <ghost-button @click.stop="showDetail(row)">详情</ghost-button>
      </template>
    </BasicTable>

    <Modal v-model:open="detailOpen" :footer="null" title="Token 调用详情" width="760px">
      <Descriptions v-if="detail" :column="2" bordered size="small">
        <DescriptionsItem label="员工">{{ detail.nickName || detail.userName }}</DescriptionsItem>
        <DescriptionsItem label="部门">{{ detail.deptName || '-' }}</DescriptionsItem>
        <DescriptionsItem label="模型">{{ detail.modelName || detail.modelId }}</DescriptionsItem>
        <DescriptionsItem label="厂商">{{ detail.providerCode || '-' }}</DescriptionsItem>
        <DescriptionsItem label="输入Token">{{ formatTokens(detail.inputTokens) }}</DescriptionsItem>
        <DescriptionsItem label="输出Token">{{ formatTokens(detail.outputTokens) }}</DescriptionsItem>
        <DescriptionsItem label="推理Token">{{ formatTokens(detail.reasoningTokens) }}</DescriptionsItem>
        <DescriptionsItem label="实际Token">{{ formatTokens(detail.actualTokens) }}</DescriptionsItem>
        <DescriptionsItem label="预占Token">{{ formatTokens(detail.reservedTokens) }}</DescriptionsItem>
        <DescriptionsItem label="扣减Token">{{ formatTokens(detail.chargedTokens) }}</DescriptionsItem>
        <DescriptionsItem label="预估成本">{{ detail.estimatedCost || 0 }}</DescriptionsItem>
        <DescriptionsItem label="状">{{ statusMap[detail.status]?.text || detail.status }}</DescriptionsItem>
        <DescriptionsItem label="Request ID" :span="2">{{ detail.requestId || '-' }}</DescriptionsItem>
        <DescriptionsItem label="Call ID" :span="2">{{ detail.callId }}</DescriptionsItem>
        <DescriptionsItem label="会话ID">{{ detail.sessionId || '-' }}</DescriptionsItem>
        <DescriptionsItem label="智能体ID">{{ detail.agentId || '-' }}</DescriptionsItem>
        <DescriptionsItem label="开始时间">{{ detail.startTime || '-' }}</DescriptionsItem>
        <DescriptionsItem label="结束时间">{{ detail.finishTime || '-' }}</DescriptionsItem>
        <DescriptionsItem v-if="detail.errorMessage" label="错误信息" :span="2">
          {{ detail.errorCode }} {{ detail.errorMessage }}
        </DescriptionsItem>
      </Descriptions>
    </Modal>
  </Page>
</template>
