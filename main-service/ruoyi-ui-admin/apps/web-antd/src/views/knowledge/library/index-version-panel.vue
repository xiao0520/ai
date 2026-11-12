<script setup lang="ts">
import type { KnowledgeIndexVersion } from '#/api/knowledge/library/model';
import type { KnowledgeModelOption } from '#/api/model/model';

import { computed, onMounted, reactive, ref } from 'vue';

import {
  Form,
  FormItem,
  Input,
  message,
  Modal,
  Popconfirm,
  Select,
  Table,
  Tag,
} from 'ant-design-vue';

import {
  knowledgeIndexVersionActivate,
  knowledgeIndexVersionAdd,
  knowledgeIndexVersionList,
  knowledgeIndexVersionRebuild,
  knowledgeIndexVersionRemove,
} from '#/api/knowledge/library';
import { knowledgeModelOptions } from '#/api/model';

const props = defineProps<{ knowledgeId: number | string }>();
const emit = defineEmits<{ reload: [] }>();
const loading = ref(false);
const rebuildingId = ref<null | number | string>(null);
const deletingId = ref<null | number | string>(null);
const open = ref(false);
const rows = ref<KnowledgeIndexVersion[]>([]);
const textEmbeddingOptions = ref<KnowledgeModelOption[]>([]);
const multimodalEmbeddingOptions = ref<KnowledgeModelOption[]>([]);
type IndexCapability = 'MULTIMODAL' | 'TEXT';

const INDEX_VERSION_PATTERN = /^[\w-]{1,64}$/;

const form = reactive({
  embeddingProfileId: undefined as number | undefined,
  indexCapability: 'TEXT' as IndexCapability,
  indexVersion: '',
  splitterConfig: '{"chunkSize":800,"overlap":100}',
});

const indexCapabilityOptions = [
  { label: '纯文本', value: 'TEXT' },
  { label: '图文混合', value: 'MULTIMODAL' },
];

const columns = [
  {
    key: 'sequence',
    title: '序号',
    width: 70,
    customRender: ({ index }: { index: number }) => index + 1,
  },
  { dataIndex: 'indexVersion', key: 'indexVersion', title: '索引版本' },
  { key: 'indexCapability', title: '索引类型', width: 110 },
  {
    dataIndex: 'embeddingProfileId',
    key: 'embeddingProfileId',
    title: 'Embedding 模型',
    width: 520,
  },
  {
    dataIndex: 'documentCount',
    key: 'documentCount',
    title: '文档数',
    width: 80,
  },
  {
    dataIndex: 'fragmentCount',
    key: 'fragmentCount',
    title: '片段数',
    width: 80,
  },
  { dataIndex: 'status', key: 'status', title: '状态', width: 110 },
  {
    dataIndex: 'activatedAt',
    key: 'activatedAt',
    title: '激活时间',
    width: 180,
  },
  { dataIndex: 'createTime', key: 'createTime', title: '创建时间', width: 180 },
  { key: 'action', title: '操作', width: 300 },
];

async function load() {
  loading.value = true;
  try {
    rows.value = await knowledgeIndexVersionList(props.knowledgeId);
  } finally {
    loading.value = false;
  }
}

async function loadModelOptions() {
  const [text, multimodal] = await Promise.all([
    knowledgeModelOptions('vector', 'TEXT'),
    knowledgeModelOptions('vector', 'IMAGE'),
  ]);
  textEmbeddingOptions.value = text;
  multimodalEmbeddingOptions.value = multimodal;
}

// 图文混合索引只允许同时支持 TEXT 和 IMAGE 的同一模型。
const selectableEmbeddingOptions = computed(() => {
  if (form.indexCapability === 'TEXT') return textEmbeddingOptions.value;
  const textModelIds = new Set(
    textEmbeddingOptions.value.map((item) => String(item.id)),
  );
  return multimodalEmbeddingOptions.value.filter((item) =>
    textModelIds.has(String(item.id)),
  );
});

function modelSelectOptions(models: KnowledgeModelOption[]) {
  return models.map((item) => ({
    label: `${item.modelName} · r${item.publishedRevision}${
      item.modelDimension ? ` · ${item.modelDimension}维` : ''
    }`,
    value: item.id,
  }));
}

function modelLabel(id?: number | string, lockedRevision?: number) {
  if (!id) return '-';
  const model = [
    ...textEmbeddingOptions.value,
    ...multimodalEmbeddingOptions.value,
  ].find((item) => String(item.id) === String(id));
  if (!model) return lockedRevision ? `${id} · r${lockedRevision}` : String(id);
  const revision = lockedRevision ?? model.publishedRevision;
  const latest = model.publishedRevision;
  const updateHint =
    lockedRevision && latest && lockedRevision !== latest
      ? `（最新 r${latest}，需新建索引版本升级）`
      : '';
  const dimension = model.modelDimension ? ` · ${model.modelDimension}维` : '';
  return `${model.modelName} · r${revision}${dimension}${updateHint}`;
}

// 新版本以单模型显示；旧双模型版本仍保留两种模型信息供核对。
function indexEmbeddingLabel(index: Partial<KnowledgeIndexVersion>) {
  const text = modelLabel(
    index.embeddingProfileId,
    index.embeddingProfileRevision,
  );
  if (!index.multimodalEmbeddingProfileId) return text;
  if (
    String(index.embeddingProfileId) ===
    String(index.multimodalEmbeddingProfileId)
  ) {
    return modelLabel(
      index.multimodalEmbeddingProfileId,
      index.multimodalEmbeddingProfileRevision,
    );
  }
  const multimodal = modelLabel(
    index.multimodalEmbeddingProfileId,
    index.multimodalEmbeddingProfileRevision,
  );
  return `${text} + ${multimodal}（历史双模型配置）`;
}

function openAdd() {
  form.indexCapability = 'TEXT';
  form.indexVersion = '';
  form.embeddingProfileId = undefined;
  form.splitterConfig = '{"chunkSize":800,"overlap":100}';
  open.value = true;
}

function onIndexCapabilityChange() {
  form.embeddingProfileId = undefined;
}

/** 多模态索引把同一模型锁定到文本与图片通道，后续文档不能另选向量模型。 */
async function submit() {
  const indexVersion = form.indexVersion.trim();
  if (!indexVersion || !form.embeddingProfileId) {
    message.warning('请填写索引版本并选择 Embedding 模型');
    return;
  }
  if (!INDEX_VERSION_PATTERN.test(indexVersion)) {
    message.warning(
      '索引版本只能包含字母、数字、下划线和连字符，长度不能超过64个字符',
    );
    return;
  }
  try {
    JSON.parse(form.splitterConfig);
  } catch {
    message.warning('切分策略必须是有效的 JSON');
    return;
  }
  await knowledgeIndexVersionAdd({
    embeddingProfileId: form.embeddingProfileId,
    indexVersion,
    knowledgeId: props.knowledgeId,
    multimodalEmbeddingProfileId:
      form.indexCapability === 'MULTIMODAL'
        ? form.embeddingProfileId
        : undefined,
    splitterConfig: form.splitterConfig,
  });
  open.value = false;
  await load();
}

function requireIndexVersionId(row: Record<string, any>): number | string {
  if (typeof row.id !== 'number' && typeof row.id !== 'string') {
    throw new TypeError('索引版本记录缺少有效 ID');
  }
  return row.id;
}

async function rebuild(row: Record<string, any>) {
  if (rebuildingId.value !== null) return;
  const id = requireIndexVersionId(row);
  rebuildingId.value = id;
  try {
    await knowledgeIndexVersionRebuild(id);
    await load();
  } finally {
    rebuildingId.value = null;
  }
}

async function activate(row: Record<string, any>) {
  await knowledgeIndexVersionActivate(requireIndexVersionId(row));
  await load();
  emit('reload');
}

// 有文档的激活版本不可删除；空的激活版本可清理，但知识库会失去当前索引。
function canDeleteIndex(row: Record<string, any>) {
  return row.status !== 'ACTIVE' || Number(row.documentCount || 0) <= 0;
}

function deleteConfirmTitle(row: Record<string, any>) {
  if (row.status === 'ACTIVE') {
    return '确认删除当前空索引版本？删除后知识库将暂无当前索引，需要新建或激活其他版本后才能使用 RAG。';
  }
  const hasArtifacts =
    Number(row.documentCount || 0) > 0 || Number(row.fragmentCount || 0) > 0;
  return hasArtifacts
    ? '确认删除该索引版本？索引成员、Qdrant 向量、产物 OSS、关联任务和版本记录将异步清理。'
    : '确认删除该索引版本？关联任务和版本记录将异步清理。';
}

// 接口提交清理任务后刷新列表；Qdrant 和 OSS 的实际清理由后端异步完成。
async function remove(row: Record<string, any>) {
  if (deletingId.value !== null) return;
  const id = requireIndexVersionId(row);
  deletingId.value = id;
  try {
    await knowledgeIndexVersionRemove(id);
    await load();
    emit('reload');
  } finally {
    deletingId.value = null;
  }
}

onMounted(async () => {
  await Promise.all([load(), loadModelOptions()]);
});
</script>

<template>
  <div class="space-y-3">
    <div class="flex justify-end">
      <a-button
        type="primary"
        v-access:code="['knowledge:index:edit']"
        @click="openAdd"
      >
        新建索引版本
      </a-button>
    </div>
    <Table
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="false"
      :row-key="(row: KnowledgeIndexVersion) => row.id"
    >
      <template #bodyCell="{ column, record }">
        <Tag
          v-if="column.key === 'status'"
          :color="record.status === 'ACTIVE' ? 'success' : 'default'"
        >
          {{ record.status }}
        </Tag>
        <span v-else-if="column.key === 'embeddingProfileId'">{{
          indexEmbeddingLabel(record)
        }}</span>
        <Tag
          v-else-if="column.key === 'indexCapability'"
          :color="record.multimodalEmbeddingProfileId ? 'blue' : 'default'"
        >
          {{ record.multimodalEmbeddingProfileId ? '图文混合' : '纯文本' }}
        </Tag>
        <div v-else-if="column.key === 'action'" class="flex gap-2">
          <Popconfirm
            v-if="record.status !== 'ARCHIVED'"
            title="重建仍使用此索引已锁定的模型修订，不会升级到最新修订。确认继续重建？"
            :disabled="
              rebuildingId !== null || Number(record.documentCount || 0) <= 0
            "
            @confirm="rebuild(record)"
          >
            <ghost-button
              v-access:code="['knowledge:index:edit']"
              :disabled="
                rebuildingId !== null || Number(record.documentCount || 0) <= 0
              "
              :title="
                Number(record.documentCount || 0) <= 0
                  ? '当前索引没有已发布文档，无需重建'
                  : undefined
              "
            >
              {{ rebuildingId === record.id ? '重建中' : '重建' }}
            </ghost-button>
          </Popconfirm>
          <Popconfirm
            v-if="record.status === 'READY' || record.status === 'ARCHIVED'"
            :title="
              record.status === 'ARCHIVED'
                ? `确认回退到索引版本 ${record.indexVersion}？当前生效版本将转为归档。`
                : '确认将此版本切换为当前生效版本？'
            "
            @confirm="activate(record)"
          >
            <ghost-button v-access:code="['knowledge:index:edit']">
              {{ record.status === 'ARCHIVED' ? '回退' : '激活' }}
            </ghost-button>
          </Popconfirm>
          <Popconfirm
            v-if="canDeleteIndex(record)"
            :title="deleteConfirmTitle(record)"
            :disabled="deletingId !== null"
            @confirm="remove(record)"
          >
            <ghost-button
              danger
              v-access:code="['knowledge:index:edit']"
              :disabled="deletingId !== null"
            >
              {{ deletingId === record.id ? '删除中' : '删除' }}
            </ghost-button>
          </Popconfirm>
        </div>
      </template>
    </Table>
  </div>

  <Modal v-model:open="open" title="新建索引版本" @ok="submit">
    <Form layout="vertical">
      <FormItem label="索引版本" required>
        <Input
          v-model:value="form.indexVersion"
          placeholder="例如 v20260924-01（字母、数字、_、-）"
        />
      </FormItem>
      <FormItem label="索引文档类型" required>
        <Select
          v-model:value="form.indexCapability"
          :options="indexCapabilityOptions"
          @change="onIndexCapabilityChange"
        />
      </FormItem>
      <FormItem
        :label="
          form.indexCapability === 'MULTIMODAL'
            ? '多模态 Embedding'
            : '文本 Embedding'
        "
        required
      >
        <Select
          v-model:value="form.embeddingProfileId"
          :options="modelSelectOptions(selectableEmbeddingOptions)"
          :placeholder="
            form.indexCapability === 'MULTIMODAL'
              ? '选择同时支持文本和图片的向量模型'
              : '选择支持文本的向量模型'
          "
        />
      </FormItem>
      <FormItem label="切分策略 JSON">
        <Input v-model:value="form.splitterConfig" />
      </FormItem>
    </Form>
    <p class="mt-2 text-gray-500">
      每个索引版本只选择一个 Embedding
      模型。图文混合模型必须同时支持文本和图片；
      保存时会锁定模型当前修订，模型升级后需新建索引版本。
    </p>
  </Modal>
</template>
