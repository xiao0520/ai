<script setup lang="ts">
import type { Dayjs } from 'dayjs';
import type { VbenFormProps } from '@vben/common-ui';
import type { VxeGridProps } from '#/adapter/vxe-table';
import type {
  McpRoleToolPermission,
  McpToolPermissionOption,
} from '#/api/mcp/role-tool/model';

import { computed, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Alert,
  DatePicker,
  Form,
  FormItem,
  Input,
  message,
  Modal,
  Select,
  Spin,
  Tag,
  Textarea,
} from 'ant-design-vue';
import dayjs from 'dayjs';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  mcpPermissionToolOptions,
  mcpRoleToolInfo,
  mcpRoleToolList,
  mcpRoleToolSave,
} from '#/api/mcp/role-tool';

import { columns, querySchema } from './data';

const modalOpen = ref(false);
const modalLoading = ref(false);
const toolLoading = ref(false);
const tools = ref<McpToolPermissionOption[]>([]);
const currentRole = ref<McpRoleToolPermission>();
const formState = reactive<{
  effectiveTime?: Dayjs;
  expireTime?: Dayjs;
  remark?: string;
  toolIds: Array<number | string>;
}>({ toolIds: [] });

const toolSelectOptions = computed(() =>
  tools.value.map((tool) => ({
    label: `${tool.displayName || tool.qualifiedName}（${tool.serverName} / ${tool.riskLevel}）`,
    searchText: [tool.displayName, tool.qualifiedName, tool.serverName]
      .filter(Boolean)
      .join(' ')
      .toLowerCase(),
    value: tool.toolId,
  })),
);

function filterToolOption(input: string, option?: { searchText?: string }) {
  return option?.searchText?.includes(input.trim().toLowerCase()) ?? false;
}

const formOptions: VbenFormProps = {
  commonConfig: { componentProps: { allowClear: true }, labelWidth: 80 },
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
        mcpRoleToolList({
          pageNum: page.currentPage,
          pageSize: page.pageSize,
          ...formValues,
        }),
    },
  },
  rowConfig: { keyField: 'roleId' },
  id: 'mcp-role-tool-index',
};

const [BasicTable, tableApi] = useVbenVxeGrid({ formOptions, gridOptions });

/** 同时加载角色现有授权和可选工具，再将服务端时间转换为日期控件值。 */
async function openAssign(row: McpRoleToolPermission) {
  modalOpen.value = true;
  modalLoading.value = true;
  toolLoading.value = true;
  try {
    const [detail, options] = await Promise.all([
      mcpRoleToolInfo(row.roleId),
      mcpPermissionToolOptions(),
    ]);
    currentRole.value = detail;
    tools.value = options;
    formState.toolIds = [...detail.toolIds];
    formState.effectiveTime = detail.effectiveTime
      ? dayjs(detail.effectiveTime)
      : undefined;
    formState.expireTime = detail.expireTime
      ? dayjs(detail.expireTime)
      : undefined;
    formState.remark = detail.remark || '';
  } finally {
    modalLoading.value = false;
    toolLoading.value = false;
  }
}

// 提交当前选中的整组工具 ID；时间窗口为空时分别表示立即生效和永久有效。
async function submitAssign() {
  if (!currentRole.value) return;
  if (
    formState.effectiveTime &&
    formState.expireTime &&
    !formState.expireTime.isAfter(formState.effectiveTime)
  ) {
    message.error('失效时间必须晚于生效时间');
    return;
  }
  modalLoading.value = true;
  try {
    await mcpRoleToolSave(currentRole.value.roleId, {
      effectiveTime: formState.effectiveTime?.format('YYYY-MM-DD HH:mm:ss'),
      expireTime: formState.expireTime?.format('YYYY-MM-DD HH:mm:ss'),
      remark: formState.remark,
      toolIds: formState.toolIds,
    });
    modalOpen.value = false;
    await tableApi.query();
  } finally {
    modalLoading.value = false;
  }
}
</script>

<template>
  <Page :auto-content-height="true">
    <BasicTable table-title="角色 MCP 工具权限">
      <template #status="{ row }">
        <Tag :color="row.roleStatus === '0' ? 'success' : 'default'">
          {{ row.roleStatus === '0' ? '正常' : '停用' }}
        </Tag>
      </template>
      <template #tools="{ row }">
        <div
          v-if="row.tools?.length"
          class="flex min-h-6 w-full flex-wrap items-center justify-center gap-1.5"
        >
          <Tag
            v-for="tool in row.tools.slice(0, 4)"
            :key="tool.toolId"
            class="!m-0"
            color="blue"
          >
            {{ tool.displayName || tool.qualifiedName }}
          </Tag>
          <Tag v-if="row.tools.length > 4" class="!m-0">
            +{{ row.tools.length - 4 }}
          </Tag>
        </div>
        <span v-else class="inline-flex w-full items-center justify-center text-gray-400">
          未授权工       </span>
      </template>
      <template #effectiveTime="{ row }">
        <span :class="row.effectiveTime ? 'text-gray-700' : 'text-gray-400'">
          {{ row.effectiveTime || '立即生效' }}
        </span>
      </template>
      <template #expireTime="{ row }">
        <span :class="row.expireTime ? 'text-gray-700' : 'text-gray-400'">
          {{ row.expireTime || '永久有效' }}
        </span>
      </template>
      <template #action="{ row }">
        <ghost-button
          :disabled="String(row.roleId) === '1'"
          v-access:code="['mcp:role-tool:assign']"
          @click.stop="openAssign(row)"
        >
          分配工具
        </ghost-button>
      </template>
    </BasicTable>

    <Modal
      v-model:open="modalOpen"
      :confirm-loading="modalLoading"
      title="分配角色 MCP 工具"
      width="720px"
      @ok="submitAssign"
    >
      <Spin :spinning="modalLoading">
        <Alert
          class="mb-4"
          message="员工通过角色继承工具权限，多角色取并集。普通聊天还需开启“允许普通聊天”；选择 Agent 时再与其绑定工具取交集。"
          show-icon
          type="info"
        />
        <Form :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }">
          <FormItem label="角色名称">
            <Input :value="currentRole?.roleName" disabled />
          </FormItem>
          <FormItem label="授权工具">
            <Select
              v-model:value="formState.toolIds"
              :filter-option="filterToolOption"
              :loading="toolLoading"
              :max-tag-count="4"
              :options="toolSelectOptions"
              allow-clear
              mode="multiple"
              placeholder="请选择该角色可使用的工具"
              show-search
            />
          </FormItem>
          <FormItem label="生效时间">
            <DatePicker
              v-model:value="formState.effectiveTime"
              class="w-full"
              format="YYYY-MM-DD HH:mm:ss"
              placeholder="为空则立即生效"
              show-time
            />
          </FormItem>
          <FormItem
            :help="
              formState.effectiveTime && formState.expireTime &&
              !formState.expireTime.isAfter(formState.effectiveTime)
                ? '失效时间必须晚于生效时间'
                : undefined
            "
            :validate-status="
              formState.effectiveTime && formState.expireTime &&
              !formState.expireTime.isAfter(formState.effectiveTime)
                ? 'error'
                : undefined
            "
            label="失效时间"
          >
            <DatePicker
              v-model:value="formState.expireTime"
              class="w-full"
              format="YYYY-MM-DD HH:mm:ss"
              placeholder="为空则永久有效"
              show-time
            />
          </FormItem>
          <FormItem label="备注">
            <Textarea
              v-model:value="formState.remark"
              :maxlength="500"
              :rows="3"
              show-count
            />
          </FormItem>
        </Form>
      </Spin>
    </Modal>
  </Page>
</template>
