<script setup lang="ts">
import type { VbenFormProps } from '@vben/common-ui';

import type { VxeGridProps } from '#/adapter/vxe-table';
import type { SkillVO } from '#/api/agent/skill/model';

import { ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page, useVbenDrawer } from '@vben/common-ui';
import { getVxePopupContainer } from '@vben/utils';

import { message, Modal, Popconfirm, Space, Tag } from 'ant-design-vue';

import { useVbenVxeGrid, vxeCheckboxChecked } from '#/adapter/vxe-table';
import {
  skillChangeStatus,
  skillExport,
  skillList,
  skillRemove,
} from '#/api/agent/skill';
import { TableSwitch } from '#/components/table';
import { commonDownloadExcel } from '#/utils/file/download';

import { columns, querySchema } from './data';
import SkillAgentsModal from './skill-agents-modal.vue';
import skillDrawer from './skill-drawer.vue';

const formOptions: VbenFormProps = {
  commonConfig: {
    labelWidth: 80,
    componentProps: {
      allowClear: true,
    },
  },
  schema: querySchema(),
  wrapperClass: 'grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-x-[60px]',
};

const gridOptions: VxeGridProps = {
  checkboxConfig: {
    highlight: true,
    reserve: true,
  },
  columns,
  height: 'auto',
  keepSource: true,
  pagerConfig: {},
  proxyConfig: {
    ajax: {
      query: async ({ page }, formValues = {}) => {
        return await skillList({
          pageNum: page.currentPage,
          pageSize: page.pageSize,
          ...formValues,
        });
      },
    },
  },
  rowConfig: {
    keyField: 'id',
  },
  id: 'agent-skill-index',
  showOverflow: false,
};

const [BasicTable, tableApi] = useVbenVxeGrid({
  formOptions,
  gridOptions,
});

const [SkillDrawer, drawerApi] = useVbenDrawer({
  connectedComponent: skillDrawer,
});

const { hasAccessByCodes } = useAccess();

const skillAgentsModalRef = ref<InstanceType<typeof SkillAgentsModal>>();

const runtimeColors: Record<string, string> = {
  HTTP: 'green',
  MCP: 'purple',
  PYTHON: 'blue',
};

function handleAdd() {
  drawerApi.setData({});
  drawerApi.open();
}

async function handleEdit(record: SkillVO) {
  drawerApi.setData({ id: record.id });
  drawerApi.open();
}

function handleViewAgents(record: SkillVO) {
  skillAgentsModalRef.value?.show(record);
}

async function handleDelete(row: SkillVO) {
  // 删除校验：已被绑定时提示关联 Agent，建议先停用或解除绑定
  if (row.agentCount && row.agentCount > 0) {
    message.warning('Skill 已被 Agent 绑定，请先停用或解除绑定后再删除');
    handleViewAgents(row);
    return;
  }
  await skillRemove([row.id]);
  await tableApi.query();
}

function handleMultiDelete() {
  const rows = tableApi.grid.getCheckboxRecords();
  const bound = rows.filter(
    (row: SkillVO) => row.agentCount && row.agentCount > 0,
  );
  if (bound.length > 0) {
    message.warning(
      `选中Skill${bound.map((row: SkillVO) => row.skillName).join('、')} 已被 Agent 绑定，请先解除绑定`,
    );
    return;
  }
  const ids = rows.map((row: SkillVO) => row.id);
  Modal.confirm({
    title: '提示',
    okType: 'danger',
    content: `确认删除选中${ids.length}条记录吗？`,
    onOk: async () => {
      await skillRemove(ids);
      await tableApi.query();
    },
  });
}

function handleDownloadExcel() {
  commonDownloadExcel(skillExport, 'Skill数据', tableApi.formApi.form.values);
}
</script>

<template>
  <Page :auto-content-height="true">
    <BasicTable table-title="Skill 列表">
      <template #toolbar-tools>
        <Space>
          <a-button
            v-access:code="['agent:skill:export']"
            @click="handleDownloadExcel"
          >
            {{ $t('pages.common.export') }}
          </a-button>
          <a-button
            :disabled="!vxeCheckboxChecked(tableApi)"
            danger
            type="primary"
            v-access:code="['agent:skill:remove']"
            @click="handleMultiDelete"
          >
            {{ $t('pages.common.delete') }}
          </a-button>
          <a-button
            type="primary"
            v-access:code="['agent:skill:add']"
            @click="handleAdd"
          >
            {{ $t('pages.common.add') }}
          </a-button>
        </Space>
      </template>
      <template #runtime="{ row }">
        <Tag :color="runtimeColors[String(row.runtime)] || 'default'">
          {{ row.runtime }}
        </Tag>
      </template>
      <template #status="{ row }">
        <TableSwitch
          v-model:value="row.status"
          :api="() => skillChangeStatus(row)"
          :disabled="!hasAccessByCodes(['agent:skill:edit'])"
          :confirm="true"
          :confirm-text="
            (checked) =>
              checked === '1'
                ? '停用后所有已绑定 Agent 将不可再使用Skill，确认停用吗？'
                : '确认启用Skill 吗？'
          "
          @reload="tableApi.query()"
        />
      </template>
      <template #action="{ row }">
        <Space>
          <ghost-button
            v-access:code="['agent:skill:edit']"
            @click.stop="handleEdit(row)"
          >
            {{ $t('pages.common.edit') }}
          </ghost-button>
          <ghost-button
            v-access:code="['agent:skill:query']"
            @click.stop="handleViewAgents(row)"
          >
            已绑定Agent
          </ghost-button>
          <Popconfirm
            :get-popup-container="getVxePopupContainer"
            placement="left"
            title="确认删除？"
            @confirm="handleDelete(row)"
          >
            <ghost-button
              danger
              v-access:code="['agent:skill:remove']"
              @click.stop=""
            >
              {{ $t('pages.common.delete') }}
            </ghost-button>
          </Popconfirm>
        </Space>
      </template>
    </BasicTable>
    <SkillDrawer @reload="tableApi.query()" />
    <SkillAgentsModal ref="skillAgentsModalRef" />
  </Page>
</template>
