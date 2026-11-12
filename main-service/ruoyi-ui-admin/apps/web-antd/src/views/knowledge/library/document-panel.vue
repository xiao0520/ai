<script setup lang="ts">
import type {
  KnowledgeDocument,
  KnowledgeDocumentProcessingForm,
  KnowledgeDocumentVersion,
  KnowledgeIndexVersion,
} from '#/api/knowledge/library/model';
import type { KnowledgeModelOption } from '#/api/model/model';

import { computed, onMounted, reactive, ref } from 'vue';

import {
  DeleteOutlined,
  FileTextOutlined,
  InboxOutlined,
} from '@ant-design/icons-vue';
import {
  Form,
  FormItem,
  message,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Upload,
} from 'ant-design-vue';

import {
  knowledgeDocumentList,
  knowledgeDocumentRemove,
  knowledgeDocumentReparse,
  knowledgeDocumentUpload,
  knowledgeDocumentUploadVersion,
  knowledgeDocumentVersions,
  knowledgeIndexVersionList,
} from '#/api/knowledge/library';
import { knowledgeModelOptions } from '#/api/model';

const props = defineProps<{ knowledgeId: number | string }>();
const UploadDragger = Upload.Dragger;
const acceptedFileTypes = '.txt,.md,.markdown,.pdf,.docx,.csv,.xlsx,.pptx';

const loading = ref(false);
const uploadOpen = ref(false);
const reparseOpen = ref(false);
const versionsOpen = ref(false);
const uploadingVersion = ref(false);
const selectedDocument = ref<KnowledgeDocument>();
const file = ref<File>();
const documents = ref<KnowledgeDocument[]>([]);
const versions = ref<KnowledgeDocumentVersion[]>([]);
const indexVersions = ref<KnowledgeIndexVersion[]>([]);
const textEmbeddingModels = ref<KnowledgeModelOption[]>([]);
const multimodalEmbeddingModels = ref<KnowledgeModelOption[]>([]);
const visionModels = ref<KnowledgeModelOption[]>([]);
const submitting = ref(false);

const processing = reactive<KnowledgeDocumentProcessingForm>({
  processingMode: 'TEXT',
  rerankMode: 'NONE',
  targetIndexVersion: '',
  textEmbeddingProfileId: '',
});

const selectedIndex = computed(() =>
  indexVersions.value.find(
    (item) => item.indexVersion === processing.targetIndexVersion,
  ),
);
const indexOptions = computed(() =>
  indexVersions.value.map((item) => ({
    label: `${item.indexVersion}${item.status === 'ACTIVE' ? '（当前）' : ''}`,
    value: item.indexVersion,
  })),
);
const processingModeOptions = [
  { label: '纯文本', value: 'TEXT' },
  { label: '图文/多模态', value: 'MULTIMODAL' },
];

const documentColumns = [
  {
    key: 'sequence',
    title: '序号',
    width: 70,
    customRender: ({ index }: { index: number }) => index + 1,
  },
  { dataIndex: 'name', key: 'name', title: '文档名称' },
  { dataIndex: 'documentCode', key: 'documentCode', title: '文档编码' },
  {
    dataIndex: 'currentVersionNo',
    key: 'currentVersionNo',
    title: '当前版本',
    width: 100,
  },
  { dataIndex: 'status', key: 'status', title: '状态', width: 100 },
  { dataIndex: 'updateTime', key: 'updateTime', title: '更新时间', width: 180 },
  { key: 'action', title: '操作', width: 280 },
];

const versionColumns = [
  {
    key: 'sequence',
    title: '序号',
    width: 70,
    customRender: ({ index }: { index: number }) => index + 1,
  },
  { dataIndex: 'versionNo', key: 'versionNo', title: '版本', width: 70 },
  { dataIndex: 'originalName', key: 'originalName', title: '原文件名' },
  {
    dataIndex: 'targetIndexVersion',
    key: 'targetIndexVersion',
    title: '索引版本',
    width: 130,
  },
  {
    dataIndex: 'processingMode',
    key: 'processingMode',
    title: '处理模式',
    width: 105,
  },
  {
    dataIndex: 'textEmbeddingProfileId',
    key: 'textEmbeddingProfileId',
    title: '文本向量',
    width: 110,
  },
  {
    dataIndex: 'visionProfileId',
    key: 'visionProfileId',
    title: '视觉模型',
    width: 100,
  },
  {
    dataIndex: 'parseStatus',
    key: 'parseStatus',
    title: '解析状态',
    width: 110,
  },
  {
    dataIndex: 'parseErrorMessage',
    key: 'parseErrorMessage',
    title: '失败原因',
  },
  { dataIndex: 'createTime', key: 'createTime', title: '上传时间', width: 180 },
];

function modelOptions(models: KnowledgeModelOption[]) {
  return models.map((item) => ({
    label: `${item.modelName} · ${item.providerCode} · r${item.publishedRevision}`,
    value: item.id,
  }));
}

function modelLabel(id?: number | string) {
  if (!id) return '-';
  const model = [...visionModels.value].find(
    (item) => String(item.id) === String(id),
  );
  return model
    ? `${model.modelName} · r${model.publishedRevision}`
    : String(id);
}

function embeddingLabel(id?: number | string, revision?: number | string) {
  if (!id) return '目标索引未配置';
  const model = [
    ...textEmbeddingModels.value,
    ...multimodalEmbeddingModels.value,
  ].find((item) => String(item.id) === String(id));
  const revisionLabel = revision ? `r${revision}` : '未发布';
  return model
    ? `${model.modelName} · ${model.providerCode} · ${revisionLabel}`
    : `${id} · ${revisionLabel}`;
}

async function loadDocuments() {
  loading.value = true;
  try {
    const result = await knowledgeDocumentList(props.knowledgeId, {
      pageNum: 1,
      pageSize: 100,
    });
    documents.value = result.rows;
  } finally {
    loading.value = false;
  }
}

async function loadConfigurationOptions() {
  const [indices, textEmbedding, multimodalEmbedding, vision] =
    await Promise.all([
      knowledgeIndexVersionList(props.knowledgeId),
      knowledgeModelOptions('vector', 'TEXT'),
      knowledgeModelOptions('vector', 'IMAGE'),
      knowledgeModelOptions('image', 'IMAGE'),
    ]);
  indexVersions.value = indices;
  textEmbeddingModels.value = textEmbedding;
  multimodalEmbeddingModels.value = multimodalEmbedding;
  visionModels.value = vision;
}

/** 文档沿用目标索引锁定的 Embedding；未指定版本时优先选择当前激活版本。 */
function applyIndex(indexVersion?: string) {
  const index =
    indexVersions.value.find((item) => item.indexVersion === indexVersion) ??
    indexVersions.value.find((item) => item.status === 'ACTIVE') ??
    indexVersions.value[0];
  processing.targetIndexVersion = index?.indexVersion || '';
  processing.textEmbeddingProfileId = index?.embeddingProfileId || '';
  processing.multimodalEmbeddingProfileId =
    processing.processingMode === 'MULTIMODAL'
      ? index?.multimodalEmbeddingProfileId
      : undefined;
  if (
    !index?.multimodalEmbeddingProfileId &&
    processing.processingMode === 'MULTIMODAL'
  ) {
    processing.processingMode = 'TEXT';
  }
}

function resetProcessing(version?: KnowledgeDocumentVersion) {
  processing.processingMode =
    version?.processingMode === 'MULTIMODAL' ? 'MULTIMODAL' : 'TEXT';
  processing.rerankMode = 'NONE';
  processing.visionProfileId = version?.visionProfileId;
  processing.textRerankProfileId = undefined;
  processing.multimodalRerankProfileId = undefined;
  applyIndex(version?.targetIndexVersion);
}

function onIndexChange(value: unknown) {
  applyIndex(typeof value === 'string' ? value : undefined);
}

// 切回纯文本时清除图片通道配置，避免提交上一轮多模态选项。
function onProcessingModeChange(value: unknown) {
  if (value === 'TEXT') {
    processing.multimodalEmbeddingProfileId = undefined;
    processing.visionProfileId = undefined;
    return;
  }
  applyIndex(processing.targetIndexVersion);
}

function validateProcessing() {
  if (!processing.targetIndexVersion || !processing.textEmbeddingProfileId) {
    message.warning('请先选择目标索引版本和文本 Embedding');
    return false;
  }
  if (
    processing.processingMode === 'MULTIMODAL' &&
    !processing.multimodalEmbeddingProfileId
  ) {
    message.warning('目标索引没有配置多模态 Embedding');
    return false;
  }
  if (
    processing.processingMode === 'MULTIMODAL' &&
    !processing.visionProfileId
  ) {
    message.warning('请选择视觉/OCR 模型');
    return false;
  }
  return true;
}

function beforeUpload(uploadFile: File) {
  file.value = uploadFile;
  return false;
}

function openUpload(document?: KnowledgeDocument | Record<string, any>) {
  selectedDocument.value = document as KnowledgeDocument | undefined;
  uploadingVersion.value = !!document;
  file.value = undefined;
  resetProcessing();
  uploadOpen.value = true;
}

function closeUpload() {
  uploadOpen.value = false;
  file.value = undefined;
}

// 只提交有效处理参数；未选的模型字段不应作为空字符串传给后端。
function appendProcessing(data: FormData) {
  Object.entries(processing).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '')
      data.append(key, String(value));
  });
}

/** 首次上传与上传新版本共用处理配置，并让后端创建解析任务。 */
async function submitUpload() {
  if (!file.value) return void message.warning('请选择要上传的文件');
  if (!validateProcessing()) return;
  submitting.value = true;
  try {
    const data = new FormData();
    data.append('file', file.value);
    data.append('autoParse', 'true');
    appendProcessing(data);
    await (uploadingVersion.value && selectedDocument.value
      ? knowledgeDocumentUploadVersion(selectedDocument.value.id, data)
      : knowledgeDocumentUpload(props.knowledgeId, data));
    message.success('文档已上传，并按所选模型创建解析任务');
    closeUpload();
    await loadDocuments();
  } finally {
    submitting.value = false;
  }
}

// 重解析从版本列表首项恢复模式与视觉模型，再按目标索引重新锁定 Embedding。
async function openReparse(
  document: KnowledgeDocument | Record<string, unknown>,
) {
  const typed = document as KnowledgeDocument;
  selectedDocument.value = typed;
  const history = await knowledgeDocumentVersions(typed.id);
  resetProcessing(history[0]);
  reparseOpen.value = true;
}

async function submitReparse() {
  if (!selectedDocument.value || !validateProcessing()) return;
  submitting.value = true;
  try {
    await knowledgeDocumentReparse(selectedDocument.value.id, {
      ...processing,
    });
    message.success('已按新模型配置提交重新解析任务');
    reparseOpen.value = false;
  } finally {
    submitting.value = false;
  }
}

async function showVersions(
  document: KnowledgeDocument | Record<string, unknown>,
) {
  const typed = document as KnowledgeDocument;
  selectedDocument.value = typed;
  versions.value = await knowledgeDocumentVersions(typed.id);
  versionsOpen.value = true;
}

async function remove(document: KnowledgeDocument | Record<string, unknown>) {
  await knowledgeDocumentRemove((document as KnowledgeDocument).id);
  await loadDocuments();
}

function formatFileSize(size?: number) {
  if (!size) return '0 B';
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

onMounted(async () => {
  await Promise.all([loadDocuments(), loadConfigurationOptions()]);
});
</script>

<template>
  <div class="space-y-3">
    <div class="flex justify-end">
      <a-button
        type="primary"
        v-access:code="['knowledge:document:add']"
        @click="openUpload()"
      >
        上传文档
      </a-button>
    </div>
    <Table
      :columns="documentColumns"
      :data-source="documents"
      :loading="loading"
      :pagination="false"
      :row-key="(row: KnowledgeDocument) => row.id"
      size="middle"
    >
      <template #bodyCell="{ column, record }">
        <Tag
          v-if="column.key === 'status'"
          :color="record.status === 'ENABLED' ? 'success' : 'default'"
        >
          {{ record.status === 'ENABLED' ? '启用' : record.status }}
        </Tag>
        <Space v-else-if="column.key === 'action'" :size="4">
          <ghost-button
            v-access:code="['knowledge:document:query']"
            @click="showVersions(record)"
          >
            版本
          </ghost-button>
          <ghost-button
            v-access:code="['knowledge:document:add']"
            @click="openUpload(record)"
          >
            上传新版本
          </ghost-button>
          <ghost-button
            v-access:code="['knowledge:document:edit']"
            @click="openReparse(record)"
          >
            重新解析
          </ghost-button>
          <Popconfirm
            title="确认彻底删除该文档？其全部版本、索引成员、Qdrant 向量和 OSS 文件将异步清理。"
            @confirm="remove(record)"
          >
            <ghost-button danger v-access:code="['knowledge:document:remove']">
              删除
            </ghost-button>
          </Popconfirm>
        </Space>
      </template>
    </Table>
  </div>

  <Modal
    v-model:open="uploadOpen"
    :title="uploadingVersion ? '上传文档新版本' : '上传知识库文档'"
    width="760px"
    :footer="null"
  >
    <UploadDragger
      :before-upload="beforeUpload"
      :max-count="1"
      :multiple="false"
      :show-upload-list="false"
      :accept="acceptedFileTypes"
    >
      <template v-if="file">
        <div class="selected-file">
          <FileTextOutlined />
          <span>{{ file.name }}</span>
          <small>{{ formatFileSize(file.size) }}</small>
          <a-button type="text" danger @click.stop="file = undefined">
            <DeleteOutlined /> 移除
          </a-button>
        </div>
      </template>
      <template v-else>
        <div class="upload-icon"><InboxOutlined /></div>
        <div>
          点击或拖拽 TXT、Markdown、PDF、Word、Excel、CSV、PowerPoint 文件
        </div>
      </template>
    </UploadDragger>
    <Form class="mt-4" layout="vertical">
      <div class="grid grid-cols-2 gap-x-4">
        <FormItem label="目标索引版本" required>
          <Select
            v-model:value="processing.targetIndexVersion"
            :options="indexOptions"
            @change="onIndexChange"
          />
        </FormItem>
        <FormItem label="处理模式" required>
          <Select
            v-model:value="processing.processingMode"
            :options="processingModeOptions"
            @change="onProcessingModeChange"
          />
        </FormItem>
        <FormItem label="文本 Embedding（随索引锁定）">
          <div class="locked-model-value">
            {{
              embeddingLabel(
                selectedIndex?.embeddingProfileId,
                selectedIndex?.embeddingProfileRevision,
              )
            }}
          </div>
        </FormItem>
        <FormItem
          v-if="processing.processingMode === 'MULTIMODAL'"
          label="多模态 Embedding（随索引锁定）"
        >
          <div class="locked-model-value">
            {{
              embeddingLabel(
                selectedIndex?.multimodalEmbeddingProfileId,
                selectedIndex?.multimodalEmbeddingProfileRevision,
              )
            }}
          </div>
        </FormItem>
        <FormItem
          v-if="processing.processingMode === 'MULTIMODAL'"
          label="视觉/OCR 模型"
        >
          <Select
            v-model:value="processing.visionProfileId"
            :options="modelOptions(visionModels)"
          />
        </FormItem>
      </div>
    </Form>
    <div class="flex justify-end gap-3">
      <a-button @click="closeUpload">取消</a-button>
      <a-button type="primary" :loading="submitting" @click="submitUpload">
        上传并解析
      </a-button>
    </div>
  </Modal>

  <Modal
    v-model:open="reparseOpen"
    title="重新解析文档"
    width="700px"
    :confirm-loading="submitting"
    @ok="submitReparse"
  >
    <Form layout="vertical">
      <div class="grid grid-cols-2 gap-x-4">
        <FormItem label="目标索引版本" required>
          <Select
            v-model:value="processing.targetIndexVersion"
            :options="indexOptions"
            @change="onIndexChange"
          />
        </FormItem>
        <FormItem label="处理模式" required>
          <Select
            v-model:value="processing.processingMode"
            :options="processingModeOptions"
            @change="onProcessingModeChange"
          />
        </FormItem>
        <FormItem label="文本 Embedding（随索引锁定）">
          <div class="locked-model-value">
            {{
              embeddingLabel(
                selectedIndex?.embeddingProfileId,
                selectedIndex?.embeddingProfileRevision,
              )
            }}
          </div>
        </FormItem>
        <FormItem
          v-if="processing.processingMode === 'MULTIMODAL'"
          label="多模态 Embedding（随索引锁定）"
        >
          <div class="locked-model-value">
            {{
              embeddingLabel(
                selectedIndex?.multimodalEmbeddingProfileId,
                selectedIndex?.multimodalEmbeddingProfileRevision,
              )
            }}
          </div>
        </FormItem>
        <FormItem
          v-if="processing.processingMode === 'MULTIMODAL'"
          label="视觉/OCR 模型"
        >
          <Select
            v-model:value="processing.visionProfileId"
            :options="modelOptions(visionModels)"
          />
        </FormItem>
      </div>
    </Form>
  </Modal>

  <Modal
    v-model:open="versionsOpen"
    :title="`文档版本 - ${selectedDocument?.name || ''}`"
    width="1200px"
    :footer="null"
  >
    <Table
      :columns="versionColumns"
      :data-source="versions"
      :pagination="false"
      :row-key="(row: KnowledgeDocumentVersion) => row.id"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <Tag
          v-if="column.key === 'parseStatus'"
          :color="record.parseStatus === 'FAILED' ? 'error' : 'blue'"
        >
          {{ record.parseStatus }}
        </Tag>
        <span v-else-if="column.key === 'textEmbeddingProfileId'">{{
          record.textEmbeddingProfileId
            ? `${record.textEmbeddingProfileId} · r${record.textEmbeddingProfileRevision}`
            : '-'
        }}</span>
        <span v-else-if="column.key === 'visionProfileId'">{{
          modelLabel(record.visionProfileId)
        }}</span>
      </template>
    </Table>
  </Modal>
</template>

<style scoped>
.upload-icon {
  margin-bottom: 8px;
  color: #1677ff;
  font-size: 36px;
}
.selected-file {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
}
.selected-file span {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.selected-file small {
  color: #86909c;
}

.locked-model-value {
  min-height: 32px;
  padding: 4px 11px;
  border: 1px solid #d9d9d9;
  border-radius: 6px;
  background: #f5f5f5;
  color: rgb(0 0 0 / 45%);
  line-height: 22px;
}
</style>
