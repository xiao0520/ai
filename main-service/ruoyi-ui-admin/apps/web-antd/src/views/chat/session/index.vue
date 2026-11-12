<script setup lang="ts">
import type { VbenFormProps } from '@vben/common-ui';
import type { VxeGridProps } from '#/adapter/vxe-table';
import type { ChatSessionVO } from '#/api/chat/session/model';

import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { getVxePopupContainer } from '@vben/utils';

import { Popconfirm, Space, Tag } from 'ant-design-vue';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  chatSessionChangeStatus,
  chatSessionExport,
  chatSessionList,
} from '#/api/chat/session';
import { commonDownloadExcel } from '#/utils/file/download';

import { columns, querySchema } from './data';

const router = useRouter();
const { hasAccessByCodes } = useAccess();

const formOptions: VbenFormProps = {
  commonConfig: { componentProps: { allowClear: true }, labelWidth: 85 },
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
        chatSessionList({
          pageNum: page.currentPage,
          pageSize: page.pageSize,
          ...formValues,
        }),
    },
  },
  rowConfig: { keyField: 'id' },
  id: 'chat-session-index',
  showOverflow: false,
};

const [BasicTable, tableApi] = useVbenVxeGrid({ formOptions, gridOptions });

function handleMessageAudit(row: ChatSessionVO) {
  router.push({ path: '/chat/message', query: { sessionId: String(row.id) } });
}

async function handleArchive(row: ChatSessionVO) {
  const status = row.status === 'ARCHIVED' ? 'ACTIVE' : 'ARCHIVED';
  await chatSessionChangeStatus(row.id, status);
  await tableApi.query();
}

function handleDownloadExcel() {
  commonDownloadExcel(chatSessionExport, '会话管理数据', tableApi.formApi.form.values, {
    fieldMappingTime: formOptions.fieldMappingTime,
  });
}
</script>

<template>
  <Page :auto-content-height="true">
    <BasicTable table-title="会话管理">
      <template #toolbar-tools>
        <a-button
          v-access:code="['chat:session:export']"
          @click="handleDownloadExcel"
        >
          {{ $t('pages.common.export') }}
        </a-button>
      </template>
      <template #status="{ row }">
        <Tag :color="row.status === 'ARCHIVED' ? 'default' : 'green'">
          {{ row.status === 'ARCHIVED' ? '已归' : '进行'}}
        </Tag>
      </template>
      <template #action="{ row }">
        <Space :size="4">
          <ghost-button
            v-access:code="['chat:message:list']"
            @click.stop="handleMessageAudit(row)"
          >
            消息审计
          </ghost-button>
          <Popconfirm
            :get-popup-container="getVxePopupContainer"
            :title="row.status === 'ARCHIVED' ? '确认恢复该会话？' : '确认归档该会话？'"
            @confirm="handleArchive(row)"
          >
            <ghost-button
              v-access:code="['chat:session:archive']"
              :disabled="!hasAccessByCodes(['chat:session:archive'])"
              @click.stop=""
            >
              {{ row.status === 'ARCHIVED' ? '恢复' : '归档' }}
            </ghost-button>
          </Popconfirm>
        </Space>
      </template>
    </BasicTable>
  </Page>
</template>
