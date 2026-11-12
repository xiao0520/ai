<script setup lang="ts">
import type { KnowledgeRetrievalConfig } from '#/api/knowledge/library/model';
import type { KnowledgeModelOption } from '#/api/model/model';

import { computed, onMounted, reactive, ref } from 'vue';

import {
  Alert,
  Button,
  Card,
  Form,
  FormItem,
  InputNumber,
  message,
  Select,
  Spin,
  Switch,
} from 'ant-design-vue';

import {
  knowledgeRetrievalConfig,
  knowledgeRetrievalConfigUpdate,
} from '#/api/knowledge/library';
import { knowledgeModelOptions } from '#/api/model';

const props = defineProps<{ knowledgeId: number | string }>();
const config = reactive<KnowledgeRetrievalConfig>({
  denseEnabled: true,
  sparseEnabled: false,
  recallCount: 10,
  rrfK: 60,
  rerankEnabled: false,
  rerankModelId: null,
  rerankInputCount: 10,
  rerankOutputCount: 5,
  relevanceThreshold: null,
});
const loading = ref(false);
const saving = ref(false);
const thresholdEnabled = ref(false);
const rerankModels = ref<KnowledgeModelOption[]>([]);
const selectedRerankId = computed({
  get: () => config.rerankModelId ?? undefined,
  set: (value: number | string | undefined) => {
    config.rerankModelId = value;
  },
});
const selectedThreshold = computed({
  get: () => config.relevanceThreshold ?? undefined,
  set: (value: number | undefined) => {
    config.relevanceThreshold = value;
  },
});
const modelOptions = computed(() =>
  rerankModels.value.map((model) => ({
    label: model.modelName || String(model.id),
    value: model.id,
  })),
);

async function load() {
  loading.value = true;
  try {
    const [saved, models] = await Promise.all([
      knowledgeRetrievalConfig(props.knowledgeId),
      knowledgeModelOptions('rerank', 'TEXT'),
    ]);
    // 已保存策略覆盖页面初始值，避免把默认开启误认为该知识库的实际状态。
    Object.assign(config, saved);
    thresholdEnabled.value =
      saved.relevanceThreshold !== null &&
      saved.relevanceThreshold !== undefined;
    rerankModels.value = models;
  } finally {
    loading.value = false;
  }
}

/** 保存前验证召回、重排和阈值之间的约束；策略调整不触发索引重建。 */
async function save() {
  if (!config.denseEnabled && !config.sparseEnabled) {
    message.warning('Dense 和 Sparse 至少启用一种');
    return;
  }
  if (config.rerankEnabled && !config.rerankModelId) {
    message.warning('请选择已发布的 Rerank 模型');
    return;
  }
  if (
    config.rerankEnabled &&
    (config.rerankInputCount > config.recallCount ||
      config.rerankOutputCount > config.rerankInputCount)
  ) {
    message.warning(
      'Rerank 返回数量不能超过输入数量，输入数量不能超过召回数量',
    );
    return;
  }
  if (
    thresholdEnabled.value &&
    (config.relevanceThreshold === null ||
      config.relevanceThreshold === undefined)
  ) {
    message.warning('请输入相关性阈值');
    return;
  }
  // 仅 Sparse 召回且不重排时没有可用于 0–1 阈值的相关性分数。
  if (thresholdEnabled.value && !config.denseEnabled && !config.rerankEnabled) {
    message.warning('仅启用 Sparse 且不重排时，无法使用 0–1 相关性阈值');
    return;
  }
  saving.value = true;
  try {
    // 关闭开关时同步清空对应参数，避免旧模型或旧阈值继续进入执行配置。
    await knowledgeRetrievalConfigUpdate(props.knowledgeId, {
      ...config,
      rerankModelId: config.rerankEnabled ? config.rerankModelId : null,
      relevanceThreshold: thresholdEnabled.value
        ? config.relevanceThreshold
        : null,
    });
    await load();
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <Spin :spinning="loading">
    <div class="w-full max-w-5xl space-y-4 pb-6">
      <Form layout="vertical">
        <Card title="召回与融合" size="small">
          <div class="mb-5 grid gap-3 sm:grid-cols-2">
            <div
              class="flex items-center justify-between rounded-lg border p-3"
            >
              <div>
                <div class="font-medium">Dense 向量</div>
                <div class="text-xs text-gray-500">按语义相似度查找内容</div>
              </div>
              <Switch v-model:checked="config.denseEnabled" />
            </div>
            <div
              class="flex items-center justify-between rounded-lg border p-3"
            >
              <div>
                <div class="font-medium">Sparse 关键词</div>
                <div class="text-xs text-gray-500">匹配关键词和专有名词</div>
              </div>
              <Switch v-model:checked="config.sparseEnabled" />
            </div>
          </div>
          <div class="grid gap-x-5 sm:grid-cols-2">
            <FormItem label="每路召回数量" class="mb-0">
              <InputNumber
                v-model:value="config.recallCount"
                :min="1"
                :max="200"
                class="w-full"
              />
            </FormItem>
            <FormItem label="RRF 融合常数" class="mb-0">
              <InputNumber
                v-model:value="config.rrfK"
                :min="1"
                :max="1000"
                class="w-full"
              />
            </FormItem>
          </div>
        </Card>

        <Card title="重排序 Rerank" size="small" class="mt-4">
          <template #extra>
            <span class="mr-2 text-sm text-gray-500">启用</span>
            <Switch v-model:checked="config.rerankEnabled" />
          </template>
          <Alert
            v-if="modelOptions.length === 0"
            class="mb-4"
            show-icon
            type="warning"
            message="暂无已发布且支持文本的 Rerank 模型，请先到模型管理中配置。"
          />
          <template v-if="config.rerankEnabled">
            <FormItem label="Rerank 模型" required>
              <Select
                v-model:value="selectedRerankId"
                :options="modelOptions"
                placeholder="选择已发布且支持文本的重排模型"
              />
            </FormItem>
            <div class="grid gap-x-5 sm:grid-cols-2">
              <FormItem label="输入数量" class="mb-0">
                <InputNumber
                  v-model:value="config.rerankInputCount"
                  :min="1"
                  :max="200"
                  class="w-full"
                />
              </FormItem>
              <FormItem label="返回数量" class="mb-0">
                <InputNumber
                  v-model:value="config.rerankOutputCount"
                  :min="1"
                  :max="100"
                  class="w-full"
                />
              </FormItem>
            </div>
          </template>
          <div v-else class="text-sm text-gray-500">
            已关闭重排序，检索结果将直接按召回排序使用。
          </div>
        </Card>

        <Card title="相关性阈值" size="small" class="mt-4">
          <template #extra>
            <div
              class="flex flex-wrap items-center justify-end gap-x-4 gap-y-2"
            >
              <div v-if="thresholdEnabled" class="flex items-center gap-2">
                <span class="text-sm text-gray-500">阈值（0–1）</span>
                <InputNumber
                  v-model:value="selectedThreshold"
                  :min="0"
                  :max="1"
                  :step="0.05"
                  aria-label="相关性阈值（0 到 1）"
                  class="w-24"
                />
              </div>
              <div class="flex items-center gap-2">
                <span class="text-sm text-gray-500">启用</span>
                <Switch v-model:checked="thresholdEnabled" />
              </div>
            </div>
          </template>
          <p class="mb-0 text-sm text-gray-500">
            低于阈值的结果不进入回答上下文；留空时沿用系统默认判断。
          </p>
        </Card>

        <div class="mt-5 flex justify-end">
          <Button type="primary" :loading="saving" @click="save">
            保存检索策略
          </Button>
        </div>
      </Form>
    </div>
  </Spin>
</template>
