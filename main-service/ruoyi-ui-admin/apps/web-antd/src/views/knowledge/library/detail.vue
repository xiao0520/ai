<script setup lang="ts">
import type { KnowledgeLibrary } from '#/api/knowledge/library/model';

import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page, useVbenDrawer } from '@vben/common-ui';

import {
  Descriptions,
  DescriptionsItem,
  Spin,
  TabPane,
  Tabs,
  Tag,
} from 'ant-design-vue';

import { knowledgeLibraryInfo } from '#/api/knowledge/library';

import AclPanel from './acl-panel.vue';
import DocumentPanel from './document-panel.vue';
import IndexVersionPanel from './index-version-panel.vue';
import JobPanel from './job-panel.vue';
import LibraryDrawer from './library-drawer.vue';
import RetrievalPanel from './retrieval-panel.vue';

const route = useRoute();
const router = useRouter();
const library = ref<KnowledgeLibrary>();
const loading = ref(false);
const libraryId = String(route.params.id);
const activeTab = ref('documents');

const [LibraryFormDrawer] = useVbenDrawer({
  connectedComponent: LibraryDrawer,
});

async function load() {
  loading.value = true;
  try {
    library.value = await knowledgeLibraryInfo(libraryId);
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="mb-4">
    <a-button type="primary" @click="router.push('/knowledge/library')">
      返回列表
    </a-button>
  </div>
  <Page :auto-content-height="true" title="知识库详情">
    <Spin :spinning="loading">
      <template v-if="library">
        <Descriptions bordered class="mb-4" :column="3" size="small">
          <DescriptionsItem label="知识库名称">
            {{ library.name }}
          </DescriptionsItem>
          <DescriptionsItem label="知识库编码">
            {{ library.libraryCode }}
          </DescriptionsItem>
          <DescriptionsItem label="状态">
            <Tag :color="library.status === '0' ? 'success' : 'default'">
              {{ library.status === '0' ? '启用' : '停用' }}
            </Tag>
          </DescriptionsItem>
          <DescriptionsItem label="访问范围">
            {{
              library.accessScope === 'ALL_INTERNAL'
                ? '全体内部员工'
                : '按 ACL 授权'
            }}
          </DescriptionsItem>
          <DescriptionsItem label="当前索引版本">
            {{ library.currentIndexVersion || '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="权限修订号">
            {{ library.permissionRevision || 0 }}
          </DescriptionsItem>
          <DescriptionsItem label="说明" :span="3">
            {{ library.description || '-' }}
          </DescriptionsItem>
        </Descriptions>
        <!-- 仅挂载当前面板；切换回来时重新读取该面板的数据。 -->
        <Tabs v-model:active-key="activeTab">
          <TabPane key="documents" tab="文档与版本">
            <DocumentPanel
              v-if="activeTab === 'documents'"
              :knowledge-id="library.id"
            />
          </TabPane>
          <TabPane key="acl" tab="访问控制 ACL">
            <AclPanel v-if="activeTab === 'acl'" :knowledge-id="library.id" />
          </TabPane>
          <TabPane key="jobs" tab="解析任务">
            <JobPanel v-if="activeTab === 'jobs'" :knowledge-id="library.id" />
          </TabPane>
          <TabPane key="retrieval" tab="检索策略">
            <RetrievalPanel
              v-if="activeTab === 'retrieval'"
              :knowledge-id="library.id"
            />
          </TabPane>
          <TabPane key="index" tab="索引版本">
            <IndexVersionPanel
              v-if="activeTab === 'index'"
              :knowledge-id="library.id"
              @reload="load"
            />
          </TabPane>
        </Tabs>
      </template>
    </Spin>
    <LibraryFormDrawer @reload="load" />
  </Page>
</template>
