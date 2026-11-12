<script setup lang="ts">
import type { KnowledgeParseJob } from '#/api/knowledge/library/model';

import { onMounted, ref } from 'vue';

import {
  Popconfirm,
  Progress,
  Space,
  Table,
  Tag,
  Tooltip,
} from 'ant-design-vue';

import {
  knowledgeJobCancel,
  knowledgeJobList,
  knowledgeJobRemove,
  knowledgeJobRetry,
} from '#/api/knowledge/library';

const props = defineProps<{ knowledgeId: number | string }>();
const loading = ref(false);
const rows = ref<KnowledgeParseJob[]>([]);

const columns = [
  {
    key: 'sequence',
    title: '序号',
    width: 70,
    customRender: ({ index }: { index: number }) => index + 1,
  },
  { dataIndex: 'requestId', key: 'requestId', title: '请求标识', width: 220 },
  { dataIndex: 'jobType', key: 'jobType', title: '任务类型', width: 130 },
  { dataIndex: 'documentId', key: 'documentId', title: '文档 ID', width: 105 },
  {
    dataIndex: 'targetIndexVersion',
    key: 'targetIndexVersion',
    title: '目标索引版本',
    width: 150,
  },
  { dataIndex: 'status', key: 'status', title: '状态', width: 120 },
  { dataIndex: 'progress', key: 'progress', title: '进度', width: 150 },
  {
    dataIndex: 'attemptCount',
    key: 'attemptCount',
    title: '尝试次数',
    width: 110,
  },
  { dataIndex: 'errorMessage', key: 'errorMessage', title: '失败原因' },
  { dataIndex: 'updateTime', key: 'updateTime', title: '更新时间', width: 180 },
  { key: 'action', title: '操作', width: 180 },
];

const statusColors: Record<string, string> = {
  CANCELLED: 'default',
  CANCEL_REQUESTED: 'warning',
  FAILED: 'error',
  PENDING: 'blue',
  QUEUED: 'cyan',
  RETRY_WAIT: 'warning',
  RUNNING: 'processing',
  SUCCEEDED: 'success',
};

const jobTypeLabels: Record<string, string> = {
  CLEANUP: '产物清理',
  INGESTION: '文档向量化',
  REBUILD: '索引重建',
  RECONCILE: '索引对账',
};

const jobTypeColors: Record<string, string> = {
  CLEANUP: 'orange',
  INGESTION: 'blue',
  REBUILD: 'purple',
  RECONCILE: 'cyan',
};

const terminalStatuses = new Set(['CANCELLED', 'FAILED', 'SUCCEEDED']);

// 此页只展示文档向量化任务；索引重建、对账与清理任务仍由后端执行。
async function load() {
  loading.value = true;
  try {
    const result = await knowledgeJobList(props.knowledgeId, {
      jobType: 'INGESTION',
      pageNum: 1,
      pageSize: 100,
    });
    rows.value = result.rows;
  } finally {
    loading.value = false;
  }
}

function requireJobId(row: Record<string, unknown>): number | string {
  if (typeof row.id !== 'number' && typeof row.id !== 'string') {
    throw new TypeError('解析任务记录缺少有效 ID');
  }
  return row.id;
}

async function retry(row: Record<string, unknown>) {
  await knowledgeJobRetry(requireJobId(row));
  await load();
}

async function cancel(row: Record<string, unknown>) {
  await knowledgeJobCancel(requireJobId(row));
  await load();
}

async function remove(row: Record<string, unknown>) {
  await knowledgeJobRemove(requireJobId(row));
  await load();
}

onMounted(load);
</script>

<template>
  <Table
    :columns="columns"
    :data-source="rows"
    :loading="loading"
    :pagination="false"
    :row-key="(row: KnowledgeParseJob) => row.id"
  >
    <template #bodyCell="{ column, record }">
      <Tag
        v-if="column.key === 'jobType'"
        :color="jobTypeColors[record.jobType] || 'default'"
      >
        {{ jobTypeLabels[record.jobType] || record.jobType || '-' }}
      </Tag>
      <Tag
        v-else-if="column.key === 'status'"
        :color="statusColors[record.status] || 'default'"
      >
        {{ record.status }}
      </Tag>
      <Progress
        v-else-if="column.key === 'progress'"
        :percent="record.progress || 0"
        :show-info="true"
        size="small"
      />
      <Tooltip
        v-else-if="column.key === 'errorMessage'"
        :title="record.errorMessage"
      >
        <span class="block max-w-48 truncate">{{
          record.errorMessage || '-'
        }}</span>
      </Tooltip>
      <Space v-else-if="column.key === 'action'" :size="4">
        <ghost-button
          v-if="record.status === 'FAILED' || record.status === 'CANCELLED'"
          v-access:code="['knowledge:job:edit']"
          @click="retry(record)"
        >
          重试
        </ghost-button>
        <Popconfirm
          v-if="record.status === 'PENDING' || record.status === 'RUNNING'"
          title="确认取消该解析任务？"
          @confirm="cancel(record)"
        >
          <ghost-button danger v-access:code="['knowledge:job:edit']">
            取消
          </ghost-button>
        </Popconfirm>
        <Popconfirm
          v-if="terminalStatuses.has(record.status)"
          title="确认从列表删除该任务？索引和审计数据会保留。"
          @confirm="remove(record)"
        >
          <ghost-button danger v-access:code="['knowledge:job:edit']">
            删除
          </ghost-button>
        </Popconfirm>
      </Space>
    </template>
  </Table>
</template>
