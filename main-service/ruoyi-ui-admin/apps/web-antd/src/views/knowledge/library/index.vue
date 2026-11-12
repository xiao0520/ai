<script setup lang="ts">
import type { VbenFormProps } from '@vben/common-ui';
import type { VxeGridProps } from '#/adapter/vxe-table';
import type { KnowledgeLibrary } from '#/api/knowledge/library/model';

import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenDrawer } from '@vben/common-ui';
import { getVxePopupContainer } from '@vben/utils';

import { Modal, Popconfirm, Space, Tag } from 'ant-design-vue';

import { useVbenVxeGrid, vxeCheckboxChecked } from '#/adapter/vxe-table';
import {
  knowledgeLibraryChangeStatus,
  knowledgeLibraryList,
  knowledgeLibraryRemove,
} from '#/api/knowledge/library';
import { TableSwitch } from '#/components/table';

import { columns, querySchema } from './data';
import LibraryDrawer from './library-drawer.vue';

const router = useRouter();
const { hasAccessByCodes } = useAccess();

const formOptions: VbenFormProps = {
  commonConfig: { componentProps: { allowClear: true }, labelWidth: 92 },
  schema: querySchema(),
  wrapperClass:
    'grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-x-[60px]',
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
        knowledgeLibraryList({
          pageNum: page.currentPage,
          pageSize: page.pageSize,
          ...formValues,
        }),
    },
  },
  rowConfig: { keyField: 'id' },
  id: 'knowledge-library-index',
  showOverflow: false,
};

const [BasicTable, tableApi] = useVbenVxeGrid({ formOptions, gridOptions });
const [LibraryFormDrawer, drawerApi] = useVbenDrawer({
  connectedComponent: LibraryDrawer,
});

function handleAdd() {
  drawerApi.setData({});
  drawerApi.open();
}

function handleEdit(row: KnowledgeLibrary) {
  drawerApi.setData({ id: row.id });
  drawerApi.open();
}

function handleDetail(row: KnowledgeLibrary) {
  router.push(`/knowledge/library/detail/${row.id}`);
}

// 删除请求提交后刷新列表；关联产物由后端异步清理。
async function handleDelete(row: KnowledgeLibrary) {
  await knowledgeLibraryRemove([row.id]);
  await tableApi.query();
}

function handleMultiDelete() {
  const ids = tableApi.grid
    .getCheckboxRecords()
    .map((row: KnowledgeLibrary) => row.id);
  Modal.confirm({
    title: '确认删除所选知识库',
    content:
      '删除后将立即停止检索，并异步彻底清理文档、权限、索引成员、Qdrant 向量、全部 OSS 文件和索引版本。',
    okType: 'danger',
    onOk: async () => {
      await knowledgeLibraryRemove(ids);
      await tableApi.query();
    },
  });
}
</script>

<template>
  <Page :auto-content-height="true">
    <BasicTable table-title="知识库列">
      <template #toolbar-tools>
        <Space>
          <a-button
            :disabled="!vxeCheckboxChecked(tableApi)"
            danger
            v-access:code="['knowledge:library:remove']"
            @click="handleMultiDelete"
          >
            批量删除
          </a-button>
          <a-button
            type="primary"
            v-access:code="['knowledge:library:add']"
            @click="handleAdd"
          >
            新增知识
          </a-button>
        </Space>
      </template>
      <template #accessScope="{ row }">
        <Tag :color="row.accessScope === 'ALL_INTERNAL' ? 'green' : 'blue'">
          {{ row.accessScope === 'ALL_INTERNAL' ? '全体内部员工' : 'ACL 授权' }}
        </Tag>
      </template>
      <template #status="{ row }">
        <TableSwitch
          v-model:value="row.status"
          :api="() => knowledgeLibraryChangeStatus(row.id, row.status)"
          :checked-value="'0'"
          :disabled="!hasAccessByCodes(['knowledge:library:edit'])"
          :unchecked-value="'1'"
          @reload="tableApi.query()"
        />
      </template>
      <template #action="{ row }">
        <Space :size="4">
          <ghost-button
            v-access:code="['knowledge:library:query']"
            @click.stop="handleDetail(row)"
          >
            管理
          </ghost-button>
          <ghost-button
            v-access:code="['knowledge:library:edit']"
            @click.stop="handleEdit(row)"
          >
            编辑
          </ghost-button>
          <Popconfirm
            :get-popup-container="getVxePopupContainer"
            placement="left"
            title="确认彻底删除？文档、权限、索引、Qdrant 向量和 OSS 文件将异步清理。"
            @confirm="handleDelete(row)"
          >
            <ghost-button danger v-access:code="['knowledge:library:remove']">
              删除
            </ghost-button>
          </Popconfirm>
        </Space>
      </template>
    </BasicTable>
    <LibraryFormDrawer @reload="tableApi.query()" />
  </Page>
</template>
