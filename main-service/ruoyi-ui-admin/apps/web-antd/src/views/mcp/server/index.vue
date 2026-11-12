<script setup lang="ts">
import type { VbenFormProps } from '@vben/common-ui';
import type { VxeGridProps } from '#/adapter/vxe-table';
import type { McpServer } from '#/api/mcp/server/model';

import { computed, reactive, ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page, useVbenDrawer } from '@vben/common-ui';
import { getVxePopupContainer } from '@vben/utils';

import {
  Alert,
  Form,
  FormItem,
  message,
  Modal,
  Popconfirm,
  Space,
  Tag,
  Textarea,
} from 'ant-design-vue';

import { useVbenVxeGrid, vxeCheckboxChecked } from '#/adapter/vxe-table';
import {
  mcpServerChangeStatus,
  mcpServerImportTools,
  mcpServerList,
  mcpServerRemove,
} from '#/api/mcp/server';
import { TableSwitch } from '#/components/table';

import { columns, querySchema } from './data';
import serverDrawer from './server-drawer.vue';

const importOpen = ref(false);
const importLoading = ref(false);
const importServer = ref<McpServer>();
const importState = reactive({ manifestJson: '' });

const formOptions: VbenFormProps = {
  commonConfig: { componentProps: { allowClear: true }, labelWidth: 95 },
  schema: querySchema(),
  wrapperClass: 'grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-x-[60px]',
};

const gridOptions: VxeGridProps = {
  checkboxConfig: { highlight: true, reserve: true },
  columns,
  height: 'auto',
  keepSource: true,
  pagerConfig: {},
  proxyConfig: {
    ajax: {
      query: async ({ page }, formValues = {}) =>
        mcpServerList({
          pageNum: page.currentPage,
          pageSize: page.pageSize,
          ...formValues,
        }),
    },
  },
  rowConfig: { keyField: 'id' },
  id: 'mcp-server-index',
  showOverflow: false,
};

const [BasicTable, tableApi] = useVbenVxeGrid({ formOptions, gridOptions });
const [ServerDrawer, drawerApi] = useVbenDrawer({ connectedComponent: serverDrawer });
const { hasAccessByCodes } = useAccess();

const importTitle = computed(() =>
  importServer.value ? `导入工具 Manifest - ${importServer.value.serverName}` : '导入工具 Manifest',
);

function handleAdd() {
  drawerApi.setData({});
  drawerApi.open();
}

function handleEdit(row: McpServer) {
  drawerApi.setData({ id: row.id });
  drawerApi.open();
}

async function handleDelete(row: McpServer) {
  await mcpServerRemove([row.id]);
  await tableApi.query();
}

function handleMultiDelete() {
  const ids = tableApi.grid
    .getCheckboxRecords()
    .map((row: McpServer) => row.id);
  Modal.confirm({
    title: '确认删除所选MCP Server',
    content: '已被角色Agent 绑定工具Server 需先解除关联',
    okType: 'danger',
    onOk: async () => {
      await mcpServerRemove(ids);
      await tableApi.query();
    },
  });
}

function openImport(row: McpServer) {
  importServer.value = row;
  importState.manifestJson = '';
  importOpen.value = true;
}

// 先校验 Manifest 的 JSON 语法，工具解析与同步结果由服务端返回。
async function submitImport() {
  if (!importServer.value) return;
  try {
    JSON.parse(importState.manifestJson);
  } catch {
    message.error('请输入合法的 Manifest JSON');
    return;
  }
  importLoading.value = true;
  try {
    await mcpServerImportTools(importServer.value.id, {
      manifestJson: importState.manifestJson,
    });
    importOpen.value = false;
    await tableApi.query();
  } finally {
    importLoading.value = false;
  }
}

</script>

<template>
  <Page :auto-content-height="true">
    <BasicTable table-title="MCP Server 管理">
      <template #toolbar-tools>
        <Space>
          <a-button
            :disabled="!vxeCheckboxChecked(tableApi)"
            danger
            v-access:code="['mcp:server:remove']"
            @click="handleMultiDelete"
          >
            批量删除
          </a-button>
          <a-button
            type="primary"
            v-access:code="['mcp:server:add']"
            @click="handleAdd"
          >
            新增 Server
          </a-button>
        </Space>
      </template>
      <template #status="{ row }">
        <TableSwitch
          v-model:value="row.status"
          :api="() => mcpServerChangeStatus(row)"
          :checked-value="'ENABLED'"
          :disabled="!hasAccessByCodes(['mcp:server:edit'])"
          :unchecked-value="'DISABLED'"
          @reload="tableApi.query()"
        />
      </template>
      <template #runtimeStatus="{ row }">
        <Tag v-if="row.runtimeStatus === 'SYNCED'" color="success">已同步</Tag>
        <Tag v-else-if="row.runtimeStatus === 'ERROR'" color="error">同步失败</Tag>
        <Tag v-else>未同步</Tag>
      </template>
      <template #action="{ row }">
        <Space :size="4">
          <ghost-button v-access:code="['mcp:server:edit']" @click.stop="handleEdit(row)">
            编辑
          </ghost-button>
          <ghost-button v-access:code="['mcp:server:import']" @click.stop="openImport(row)">
            导入工具
          </ghost-button>
          <Popconfirm
            :get-popup-container="getVxePopupContainer"
            placement="left"
            title="确认删除？"
            @confirm="handleDelete(row)"
          >
            <ghost-button danger v-access:code="['mcp:server:remove']">删除</ghost-button>
          </Popconfirm>
        </Space>
      </template>
    </BasicTable>
    <ServerDrawer @reload="tableApi.query()" />

    <Modal
      v-model:open="importOpen"
      :confirm-loading="importLoading"
      :title="importTitle"
      width="760px"
      @ok="submitImport"
    >
      <Alert
        class="mb-4"
        message="此操作只更新工具目录，不连接 MCP Server。聊天获得工具授权后才会连接并校验实时定义。请粘贴完整 tools/list JSON（支持 result.tools、tools 或工具数组）。"
        show-icon
        type="info"
      />
      <Form layout="vertical">
        <FormItem label="Manifest JSON" required>
          <Textarea
            v-model:value="importState.manifestJson"
            :rows="18"
            class="font-mono"
            placeholder='{"tools":[{"name":"search","description":"搜索","inputSchema":{"type":"object"}}]}'
          />
        </FormItem>
      </Form>
    </Modal>
  </Page>
</template>
