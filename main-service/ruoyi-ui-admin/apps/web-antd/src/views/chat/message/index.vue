<script setup lang="ts">
import { useRoute } from 'vue-router';

import { Page, type VbenFormProps } from '@vben/common-ui';
import { Space } from 'ant-design-vue';

import { useVbenVxeGrid, type VxeGridProps } from '#/adapter/vxe-table';

import {
  messageExport,
  messageList,
} from '#/api/chat/message';
import { commonDownloadExcel } from '#/utils/file/download';

import { columns, querySchema } from './data';

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

const route = useRoute();

function getRouteSessionId() {
  const sessionId = route.query.sessionId;
  return Array.isArray(sessionId) ? sessionId[0] : sessionId;
}

const gridOptions: VxeGridProps = {
  columns,
  height: 'auto',
  keepSource: true,
  pagerConfig: {},
  proxyConfig: {
    ajax: {
      query: async ({ page }, formValues = {}) => {
        const sessionId = getRouteSessionId();
        return await messageList({
          pageNum: page.currentPage,
          pageSize: page.pageSize,
          ...formValues,
          ...(sessionId ? { sessionId } : {}),
        });
      },
    },
  },
  rowConfig: {
    keyField: 'id',
  },
  id: 'system-message-index'
};

const [BasicTable, tableApi] = useVbenVxeGrid({
  formOptions,
  gridOptions,
});

function handleDownloadExcel() {
  const sessionId = getRouteSessionId();
  commonDownloadExcel(messageExport, '消息审计数据', {
    ...tableApi.formApi.form.values,
    ...(sessionId ? { sessionId } : {}),
  }, {
    fieldMappingTime: formOptions.fieldMappingTime,
  });
}
</script>

<template>
  <Page :auto-content-height="true">
    <BasicTable table-title="消息审计">
      <template #toolbar-tools>
        <Space>
          <a-button
            v-access:code="['chat:message:export']"
            @click="handleDownloadExcel"
          >
            {{ $t('pages.common.export') }}
          </a-button>
        </Space>
      </template>
    </BasicTable>
  </Page>
</template>
