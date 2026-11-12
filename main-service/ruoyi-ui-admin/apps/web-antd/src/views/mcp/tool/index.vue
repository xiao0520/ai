<script setup lang="ts">
import type { VbenFormProps } from '@vben/common-ui';
import type { VxeGridProps } from '#/adapter/vxe-table';
import type { McpTool } from '#/api/mcp/tool/model';

import { useAccess } from '@vben/access';
import { Page, useVbenDrawer } from '@vben/common-ui';
import { getVxePopupContainer } from '@vben/utils';

import { Popconfirm, Space, Tag } from 'ant-design-vue';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  mcpToolChangeStatus,
  mcpToolList,
  mcpToolRemove,
} from '#/api/mcp/tool';
import { TableSwitch } from '#/components/table';

import { columns, querySchema } from './data';
import toolDrawer from './tool-drawer.vue';

const formOptions: VbenFormProps = {
  commonConfig: { componentProps: { allowClear: true }, labelWidth: 90 },
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
        mcpToolList({
          pageNum: page.currentPage,
          pageSize: page.pageSize,
          ...formValues,
        }),
    },
  },
  rowConfig: { keyField: 'id' },
  id: 'mcp-tool-catalog-index',
  showOverflow: false,
};

const [BasicTable, tableApi] = useVbenVxeGrid({ formOptions, gridOptions });
const [ToolDrawer, drawerApi] = useVbenDrawer({ connectedComponent: toolDrawer });
const { hasAccessByCodes } = useAccess();

const riskColors: Record<string, string> = {
  CRITICAL: 'magenta',
  HIGH: 'error',
  LOW: 'success',
  MEDIUM: 'warning',
};
const riskLabels: Record<string, string> = {
  CRITICAL: '关键',
  HIGH: '',
  LOW: '',
  MEDIUM: '',
};
const discoveryLabels: Record<string, string> = {
  ACTIVE: '有效',
  CHANGED: '已变',
  REMOVED: '已移',
};

function handleEdit(record: McpTool) {
  drawerApi.setData({ id: record.id });
  drawerApi.open();
}

async function handleDelete(record: McpTool) {
  await mcpToolRemove([record.id]);
  await tableApi.query();
}

</script>

<template>
  <Page :auto-content-height="true">
    <BasicTable table-title="MCP 工具目录">
      <template #riskLevel="{ row }">
        <Tag :color="riskColors[String(row.riskLevel)]">
          {{ riskLabels[String(row.riskLevel)] || row.riskLevel }}
        </Tag>
      </template>
      <template #discoveryStatus="{ row }">
        <Tag :color="row.discoveryStatus === 'ACTIVE' ? 'blue' : row.discoveryStatus === 'CHANGED' ? 'orange' : 'default'">
          {{ discoveryLabels[String(row.discoveryStatus)] || row.discoveryStatus }}
        </Tag>
      </template>
      <template #status="{ row }">
        <TableSwitch
          v-model:value="row.adminEnabled"
          :api="() => mcpToolChangeStatus(row)"
          :checked-value="'1'"
          :disabled="!hasAccessByCodes(['mcp:tool:edit'])"
          :unchecked-value="'0'"
          @reload="tableApi.query()"
        />
      </template>
      <template #action="{ row }">
        <Space>
          <ghost-button v-access:code="['mcp:tool:edit']" @click.stop="handleEdit(row)">
            策略配置
          </ghost-button>
          <Popconfirm
            v-if="row.discoveryStatus === 'REMOVED' && row.adminEnabled === '0'"
            :get-popup-container="getVxePopupContainer"
            placement="left"
            title="确认永久删除这条已移除工具？"
            @confirm="handleDelete(row)"
          >
            <ghost-button danger v-access:code="['mcp:tool:remove']">
              清理
            </ghost-button>
          </Popconfirm>
        </Space>
      </template>
    </BasicTable>
    <ToolDrawer @reload="tableApi.query()" />
  </Page>
</template>
