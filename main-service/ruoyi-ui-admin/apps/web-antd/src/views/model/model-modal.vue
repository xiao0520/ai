<!--
使用 Ant Design Vue 原生 Form 组件生成表单
详细用法参考: https://antdv.com/components/form-cn
注意: 如果 VSCode 配置了自动移除未使用的导入，可能会误删某些组件导入
-->
<script setup lang="ts">
import type { RuleObject } from 'ant-design-vue/es/form';

import type { ModelForm } from '#/api/model/model';

import { computed, nextTick, onMounted, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { DictEnum } from '@vben/constants';
import { $t } from '@vben/locales';
import { cloneDeep } from '@vben/utils';

import {
  Col,
  Form,
  FormItem,
  Input,
  InputNumber,
  Row,
  Select,
  Tag,
  Textarea,
} from 'ant-design-vue';
import { pick } from 'lodash-es';

import { modelAdd, modelInfo, modelUpdate } from '#/api/model';
import { providerList } from '#/api/model/provider';
import { getDictOptions } from '#/utils/dict';

const emit = defineEmits<{ reload: [] }>();

const isUpdate = ref(false);
// 编辑加载数据时的标志，用于跳过watch避免apiHost被误清空
const isLoading = ref(false);
const providerOptions = ref<Array<{ label: string; value: number | string }>>(
  [],
);
const providersMap = ref<Map<number | string, any>>(new Map());
interface ApiModeOption {
  disabled?: boolean;
  isDefault?: string;
  label: string;
  value: string;
}

interface RerankAdapterOption {
  disabled?: boolean;
  label: string;
  value: string;
}

const apiModeOptions = computed<ApiModeOption[]>(() => {
  const options: ApiModeOption[] = getDictOptions(
    DictEnum.AI_MODEL_API_MODE,
  ).map((option) => ({
    isDefault: option.isDefault,
    label: String(option.label),
    value: String(option.value),
  }));
  const requiredOptions = [
    {
      label: 'OpenAI Chat Completions',
      value: 'CHAT_COMPLETIONS',
    },
    { label: 'OpenAI Responses', value: 'RESPONSES' },
    { label: 'OpenAI Embeddings', value: 'OPENAI_EMBEDDINGS' },
    {
      label: 'Ark Multimodal Embeddings',
      value: 'ARK_MULTIMODAL_EMBEDDINGS',
    },
  ];
  for (const option of requiredOptions) {
    if (!options.some((item) => item.value === option.value)) {
      options.push(option);
    }
  }
  return options;
});
const categoryOptions = computed(() => {
  const options = [...getDictOptions(DictEnum.CHAT_MODEL_CATEGORY)] as any[];
  ensureCategoryOption(options, 'image', '图片', 'orange');
  ensureCategoryOption(options, 'audio', '语音', 'purple');
  ensureCategoryOption(options, 'video', '视频', 'red');
  ensureCategoryOption(options, 'rerank', '重排序', 'magenta');
  return options;
});

function ensureCategoryOption(
  options: any[],
  value: string,
  label: string,
  cssClass: string,
) {
  if (!options.some((opt) => opt.value === value)) {
    options.push({ label, value, cssClass });
  }
}

const title = computed(() => {
  return isUpdate.value ? $t('pages.common.edit') : $t('pages.common.add');
});

onMounted(async () => {
  loadProviders();
});

async function loadProviders() {
  try {
    const res = await providerList({ pageNum: 1, pageSize: 999 });
    providerOptions.value = res.rows.map((item) => ({
      label: String(item.providerName),
      value: item.providerCode,
    }));
    // 存储供应商完整信息，以便后续查询apiHost
    providersMap.value.clear();
    res.rows.forEach((item) => {
      providersMap.value.set(item.providerCode, item);
    });
  } catch (error) {
    console.error('Failed to load providers:', error);
  }
}

/**
 * 定义默认值 用于reset
 */
const defaultValues: Partial<ModelForm> = {
  id: undefined,
  category: undefined,
  modelName: undefined,
  providerCode: undefined,
  modelDescribe: undefined,
  modelDimension: undefined,
  capabilities: undefined,
  inputCostPerMillion: undefined,
  outputCostPerMillion: undefined,
  grayWeight: undefined,
  maxContextTokens: undefined,
  maxInputTokens: undefined,
  maxOutputTokens: undefined,
  safetyMarginTokens: undefined,
  historyMaxTurns: undefined,
  knowledgeMaxChunks: undefined,
  knowledgeMaxTokens: undefined,
  toolResultMaxTokens: undefined,
  apiHost: undefined,
  apiMode: undefined,
  inputModalities: undefined,
  adapterCode: undefined,
  normalized: undefined,
  maxDocumentTokens: undefined,
  credentialKey: undefined,
  remark: undefined,
};

/**
 * 表单数据ref
 */
const formData = ref(defaultValues);

/** Python 执行端已注册的重排序协议；厂商只决定推荐项，模型仍可覆盖。 */
const cohereRerankOption: RerankAdapterOption = {
  label: '通用 Cohere Rerank v2（COHERE_RERANK_V2）',
  value: 'COHERE_RERANK_V2',
};
const providerRerankOptions: Record<string, RerankAdapterOption[]> = {
  doubao: [
    {
      label: '火山引擎知识库 Doubao Seed Rerank（VOLCENGINE_KNOWLEDGE_RERANK）',
      value: 'VOLCENGINE_KNOWLEDGE_RERANK',
    },
    cohereRerankOption,
  ],
  qianwen: [
    {
      label: '阿里云百炼文本重排序（DASHSCOPE_TEXT_RERANK）',
      value: 'DASHSCOPE_TEXT_RERANK',
    },
    cohereRerankOption,
  ],
};
const preferredRerankAdapterByProvider: Record<string, string> = {
  doubao: 'VOLCENGINE_KNOWLEDGE_RERANK',
  qianwen: 'DASHSCOPE_TEXT_RERANK',
};

const rerankAdapterOptions = computed<RerankAdapterOption[]>(() => {
  const providerCode = String(formData.value.providerCode || '').toLowerCase();
  const options = [
    ...(providerRerankOptions[providerCode] || [cohereRerankOption]),
  ];
  const currentValue = formData.value.adapterCode;
  if (
    currentValue &&
    !options.some((option) => option.value === currentValue)
  ) {
    options.push({
      disabled: true,
      label: `当前配置未注册：${currentValue}`,
      value: currentValue,
    });
  }
  return options;
});

const defaultApiModeValuesByCategory: Record<string, string[]> = {
  chat: ['CHAT_COMPLETIONS', 'RESPONSES'],
  image: ['CHAT_COMPLETIONS', 'RESPONSES'],
  vector: ['OPENAI_EMBEDDINGS', 'ARK_MULTIMODAL_EMBEDDINGS'],
};
const providerApiModeValues: Record<string, Record<string, string[]>> = {
  deepseek: {
    chat: ['CHAT_COMPLETIONS'],
  },
  doubao: {
    chat: ['CHAT_COMPLETIONS', 'RESPONSES'],
    image: ['CHAT_COMPLETIONS', 'RESPONSES'],
    vector: ['OPENAI_EMBEDDINGS', 'ARK_MULTIMODAL_EMBEDDINGS'],
  },
  kimi: {
    chat: ['CHAT_COMPLETIONS'],
    image: ['CHAT_COMPLETIONS'],
  },
  moonshot: {
    chat: ['CHAT_COMPLETIONS'],
    image: ['CHAT_COMPLETIONS'],
  },
  qianwen: {
    chat: ['CHAT_COMPLETIONS'],
    image: ['CHAT_COMPLETIONS'],
    vector: ['OPENAI_EMBEDDINGS'],
  },
  zhipu: {
    chat: ['CHAT_COMPLETIONS'],
    image: ['CHAT_COMPLETIONS'],
    vector: ['OPENAI_EMBEDDINGS'],
  },
};
const allowedApiModeValues = computed(() => {
  const category = String(formData.value.category || '');
  const providerCode = String(formData.value.providerCode || '').toLowerCase();
  return (
    providerApiModeValues[providerCode]?.[category] ||
    defaultApiModeValuesByCategory[category] ||
    []
  );
});
// 旧模型的未注册协议保留为禁用项供识别，避免编辑时静默丢失原配置。
const selectableApiModeOptions = computed(() => {
  const options = apiModeOptions.value.filter((option) =>
    allowedApiModeValues.value.includes(option.value),
  );
  const currentValue = formData.value.apiMode;
  if (
    currentValue &&
    !options.some((option) => option.value === currentValue)
  ) {
    options.push({
      disabled: true,
      label: `当前配置未注册：${currentValue}`,
      value: currentValue,
    });
  }
  return options;
});

/** 新建模型时带出推荐协议；已填写的模型级配置不会被厂商默认值覆盖。 */
function applyDefaultApiMode() {
  if (isLoading.value) return;
  if (formData.value.category === 'rerank') {
    formData.value.apiMode = undefined;
    return;
  }
  if (
    formData.value.apiMode &&
    allowedApiModeValues.value.includes(formData.value.apiMode)
  ) {
    return;
  }
  const defaultOption = apiModeOptions.value.find(
    (option) =>
      option.isDefault === 'Y' &&
      allowedApiModeValues.value.includes(option.value),
  );
  formData.value.apiMode =
    defaultOption?.value ||
    (allowedApiModeValues.value.length === 1
      ? allowedApiModeValues.value[0]
      : undefined);
}

watch(
  [
    () => formData.value.category,
    () => formData.value.providerCode,
    () =>
      apiModeOptions.value
        .map((option) => `${option.value}:${option.isDefault}`)
        .join('|'),
  ],
  applyDefaultApiMode,
  { immediate: true },
);

const showApiHost = computed(() => Boolean(formData.value.providerCode));

const apiModeHelp = computed(() => {
  if (selectableApiModeOptions.value.length === 1) {
    return '当前厂商和分类只有一个已注册协议，已自动带出；该值仍按当前模型保存。';
  }
  return '请选择当前模型实际支持的调用协议；同一厂商的不同模型可以不同。';
});
const rerankAdapterHelp = computed(() => {
  if (formData.value.adapterCode === 'VOLCENGINE_KNOWLEDGE_RERANK') {
    return '调用火山引擎知识库 Rerank，使用 AK/SK 签名；不能使用方舟 ark- 开头的 API Key。';
  }
  return '适配器按当前模型保存；Cohere 兼容项仅适用于请求与响应均兼容 Cohere v2 的服务。';
});
const credentialHelp = computed(() => {
  if (formData.value.adapterCode === 'VOLCENGINE_KNOWLEDGE_RERANK') {
    return '填写 AI_PROVIDER_CREDENTIALS 中 AK/SK 对象的别名，例如 doubao-rerank-aksk。';
  }
  return '填写 Python 服务 AI_PROVIDER_CREDENTIALS 中的凭证别名，不填写真实密钥。';
});
const modelNameHelp = computed(() => {
  if (
    formData.value.providerCode === 'doubao' &&
    formData.value.category === 'rerank'
  ) {
    return '推荐填写 API 标识 doubao-seed-rerank；Doubao-Seed-1.6-Rerank 也会兼容转换。';
  }
  if (
    formData.value.providerCode === 'doubao' &&
    formData.value.category === 'vector'
  ) {
    return formData.value.apiMode === 'ARK_MULTIMODAL_EMBEDDINGS'
      ? '填写方舟推理接入点 ID（ep-...），系统将调用 /embeddings/multimodal。'
      : '填写方舟快速接入示例中的 model 值；若返回 NotFound，请改用已授权的推理接入点。';
  }
  return undefined;
});

const apiHostHelp = computed(() => {
  if (
    formData.value.providerCode === 'doubao' &&
    formData.value.adapterCode === 'VOLCENGINE_KNOWLEDGE_RERANK'
  ) {
    return '填写火山知识库地域根地址，例如 https://api-knowledgebase.mlp.cn-beijing.volces.com；系统会调用 /api/knowledge/service/rerank。';
  }
  if (formData.value.providerCode === 'qianwen') {
    if (
      formData.value.category === 'vector' &&
      formData.value.apiMode === 'OPENAI_EMBEDDINGS'
    ) {
      return '百炼 OpenAI 兼容向量填写 https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1，系统会自动调用 /embeddings。';
    }
    if (formData.value.category === 'rerank') {
      return '百炼重排序填写业务空间根地址，例如 https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com。';
    }
  }
  return '默认带入厂商地址；可按当前模型和协议覆盖，保存后固化到模型发布修订。';
});

const knowledgeCategory = computed(() =>
  ['image', 'rerank', 'vector'].includes(String(formData.value.category || '')),
);
// 表单组件使用数组，接口以 JSON 字符串存储输入模态。
const inputModalities = computed<string[]>({
  get: () => {
    try {
      const value = JSON.parse(formData.value.inputModalities || '[]');
      return Array.isArray(value) ? value : [];
    } catch {
      return [];
    }
  },
  set: (value) => {
    formData.value.inputModalities = JSON.stringify(value);
  },
});
const chatCapabilities = computed<string[]>({
  get: () => {
    try {
      const value = JSON.parse(formData.value.capabilities || '[]');
      return Array.isArray(value) ? value : [];
    } catch {
      return [];
    }
  },
  set: (value) => {
    formData.value.capabilities = JSON.stringify(value);
  },
});
const capabilityOptions = [
  { label: '通用', value: 'GENERAL' },
  { label: '代码', value: 'CODE' },
  { label: '推理', value: 'REASONING' },
  { label: '长上下文', value: 'LONG_CONTEXT' },
  { label: '视觉', value: 'VISION' },
  { label: '工具调用', value: 'TOOL' },
];

const modalityOptions = [
  { label: '文本', value: 'TEXT' },
  { label: '图片', value: 'IMAGE' },
];
const normalizedValue = computed<string | undefined>({
  get: () =>
    formData.value.normalized === undefined
      ? undefined
      : String(formData.value.normalized),
  set: (value) => {
    formData.value.normalized =
      value === undefined ? undefined : value === 'true';
  },
});

/**
 * 监听供应商变化
 */
watch(
  () => formData.value.providerCode,
  (newProviderCode) => {
    // 编辑加载数据时跳过，避免apiHost被误清空
    if (isLoading.value) return;

    if (!newProviderCode) {
      formData.value.apiHost = undefined;
      return;
    }
    if (providersMap.value.has(newProviderCode)) {
      const provider = providersMap.value.get(newProviderCode);
      formData.value.apiHost = provider.apiHost;
    } else if (newProviderCode === 'custom_api') {
      formData.value.apiHost = undefined;
    }
  },
);

/** 供应商或分类变化时带出推荐适配器，模型仍可从已注册候选中覆盖。 */
watch(
  [() => formData.value.category, () => formData.value.providerCode],
  () => {
    if (isLoading.value) return;
    if (formData.value.category !== 'rerank') {
      formData.value.adapterCode = undefined;
      return;
    }
    const available = rerankAdapterOptions.value.filter(
      (option) => !option.disabled,
    );
    if (
      !available.some((option) => option.value === formData.value.adapterCode)
    ) {
      const providerCode = String(
        formData.value.providerCode || '',
      ).toLowerCase();
      formData.value.adapterCode =
        preferredRerankAdapterByProvider[providerCode] ||
        (available.length === 1 ? available[0]?.value : undefined);
    }
  },
);

type AntdFormRules<T> = Partial<Record<keyof T, RuleObject[]>> & {
  [key: string]: RuleObject[];
};
/**
 * 表单校验规则
 */
const formRules = ref<AntdFormRules<ModelForm>>({
  providerCode: [
    { required: true, message: $t('ui.formRules.required'), trigger: 'change' },
  ],
  category: [
    { required: true, message: $t('ui.formRules.required'), trigger: 'change' },
  ],
  apiMode: [
    {
      trigger: 'change',
      validator: async (_rule, value) => {
        if (
          ['chat', 'image', 'vector'].includes(
            String(formData.value.category),
          ) &&
          !value
        ) {
          throw new Error($t('ui.formRules.required'));
        }
      },
    },
  ],
  apiHost: [
    { required: true, message: $t('ui.formRules.required'), trigger: 'blur' },
  ],
  maxOutputTokens: [
    {
      trigger: 'change',
      validator: async (_rule, value) => {
        if (
          formData.value.category === 'chat' &&
          (value === undefined || value === null)
        ) {
          throw new Error($t('ui.formRules.required'));
        }
      },
    },
  ],
  modelName: [
    { required: true, message: $t('ui.formRules.required'), trigger: 'blur' },
  ],
  inputModalities: [
    {
      trigger: 'change',
      validator: async () => {
        if (knowledgeCategory.value && inputModalities.value.length === 0) {
          throw new Error($t('ui.formRules.required'));
        }
      },
    },
  ],
  adapterCode: [
    {
      trigger: 'change',
      validator: async (_rule, value) => {
        if (formData.value.category === 'rerank' && !value) {
          throw new Error($t('ui.formRules.required'));
        }
      },
    },
  ],
});

/**
 * useForm解构出表单方法
 */
const { validate, validateInfos, resetFields } = Form.useForm(
  formData,
  formRules,
);

const [BasicModal, modalApi] = useVbenModal({
  class: 'w-[800px]',
  fullscreenButton: false,
  closeOnClickModal: false,
  onClosed: handleCancel,
  onConfirm: handleConfirm,
  onOpenChange: async (isOpen) => {
    if (!isOpen) {
      return null;
    }
    modalApi.modalLoading(true);

    const { id } = modalApi.getData() as { id?: number | string };
    isUpdate.value = !!id;

    if (isUpdate.value && id) {
      isLoading.value = true;
      const record = await modelInfo(id);
      // 只赋值存在的字段
      const filterRecord = pick(record, Object.keys(defaultValues));
      formData.value = filterRecord;
      await nextTick();
      isLoading.value = false;
    }

    if (!isUpdate.value) {
      applyDefaultApiMode();
    }

    modalApi.modalLoading(false);
  },
});

async function handleConfirm() {
  try {
    modalApi.modalLoading(true);
    await validate();
    // 可能会做数据处理 使用cloneDeep深拷贝
    const data = cloneDeep(formData.value);
    await (isUpdate.value ? modelUpdate(data) : modelAdd(data));
    emit('reload');
    await handleCancel();
  } catch (error) {
    console.error(error);
  } finally {
    modalApi.modalLoading(false);
  }
}

async function handleCancel() {
  modalApi.close();
  formData.value = cloneDeep(defaultValues);
  resetFields();
}

/**
 * 获取分类颜色
 * @param category 分类值或颜色值
 * @param option 完整的选项对象，包含cssClass等信息
 */
function getCategoryColor(category: string, option?: any): string {
  // 优先使用option中的cssClass字段（可能包含自定义颜色）
  if (option?.cssClass) {
    const cssClass = option.cssClass.trim();
    // 如果是十六进制颜色或其他有效的CSS颜色值
    if (cssClass.startsWith('#') || isValidCSSColor(cssClass)) {
      return cssClass;
    }
  }

  // 其次查看category是否是有效的颜色值
  if (category.startsWith('#') || isValidCSSColor(category)) {
    return category;
  }

  // 最后使用预定义的颜色映射
  const colorMap: Record<string, string> = {
    chat: 'blue',
    embedding: 'green',
    image: 'orange',
    audio: 'purple',
    video: 'red',
    code: 'cyan',
    rerank: 'magenta',
  };
  return colorMap[category] || 'default';
}

/**
 * 判断是否为有效的CSS颜色值
 */
function isValidCSSColor(color: string): boolean {
  // 预定义的Ant Design颜色
  const antColors = [
    'red',
    'orange',
    'gold',
    'yellow',
    'lime',
    'green',
    'cyan',
    'blue',
    'geekblue',
    'purple',
    'magenta',
    'volcano',
    'default',
  ];
  if (antColors.includes(color)) {
    return true;
  }

  // 简单检查是否为有效的十六进制颜色或rgb颜色
  const hexRegex = /^#(?:[A-F0-9]{6}|[A-F0-9]{3}|[A-F0-9]{8})$/i;
  const rgbRegex = /^rgba?\(/;

  return hexRegex.test(color) || rgbRegex.test(color);
}
</script>

<template>
  <BasicModal :title="title" class="w-[700px]">
    <Form :label-col="{ span: 24 }" :wrapper-col="{ span: 24 }">
      <Row :gutter="16">
        <Col :span="12">
          <FormItem label="供应商" v-bind="validateInfos.providerCode">
            <Select
              v-model:value="formData.providerCode"
              :placeholder="$t('ui.formRules.required')"
              :options="providerOptions"
            />
          </FormItem>
        </Col>
        <Col :span="12">
          <FormItem label="模型分类" v-bind="validateInfos.category">
            <Select
              v-model:value="formData.category"
              :placeholder="$t('ui.formRules.required')"
              :options="categoryOptions"
              show-search
              option-filter-prop="label"
            >
              <template #option="{ label, value, cssClass }">
                <div class="flex items-center justify-between">
                  <span>{{ label }}</span>
                  <Tag
                    :color="getCategoryColor(value, { cssClass })"
                    class="ml-2"
                  >
                    {{ value }}
                  </Tag>
                </div>
              </template>
              <template #tagRender="{ label, option }">
                <Tag :color="getCategoryColor(option.value, option)">
                  {{ label }}
                </Tag>
              </template>
            </Select>
          </FormItem>
        </Col>
      </Row>

      <Row :gutter="16">
        <Col :span="12">
          <FormItem
            label="模型名称"
            v-bind="validateInfos.modelName"
            :extra="modelNameHelp"
          >
            <Input
              v-model:value="formData.modelName"
              :placeholder="$t('ui.formRules.required')"
            />
          </FormItem>
        </Col>
        <Col :span="12">
          <FormItem label="模型描述" v-bind="validateInfos.modelDescribe">
            <Input
              v-model:value="formData.modelDescribe"
              :placeholder="$t('ui.formRules.required')"
            />
          </FormItem>
        </Col>
      </Row>

      <Row :gutter="16">
        <Col
          v-if="['chat', 'image', 'vector'].includes(String(formData.category))"
          :xs="24"
          :sm="12"
        >
          <FormItem
            label="API 调用模式"
            v-bind="validateInfos.apiMode"
            :extra="apiModeHelp"
          >
            <Select
              v-model:value="formData.apiMode"
              :options="selectableApiModeOptions"
              :placeholder="$t('ui.formRules.required')"
            />
          </FormItem>
        </Col>
        <Col
          :xs="24"
          :sm="
            ['chat', 'image', 'vector'].includes(String(formData.category))
              ? 12
              : 24
          "
        >
          <FormItem
            label="凭证别名"
            v-bind="validateInfos.credentialKey"
            :extra="credentialHelp"
          >
            <Input
              v-model:value="formData.credentialKey"
              :placeholder="$t('ui.formRules.required')"
            />
          </FormItem>
        </Col>
      </Row>

      <template v-if="knowledgeCategory">
        <Row :gutter="16">
          <Col :span="12">
            <FormItem label="输入模态" v-bind="validateInfos.inputModalities">
              <Select
                v-model:value="inputModalities"
                mode="multiple"
                :options="modalityOptions"
              />
            </FormItem>
          </Col>
          <Col v-if="formData.category === 'rerank'" :span="12">
            <FormItem
              label="适配器编码"
              v-bind="validateInfos.adapterCode"
              :extra="rerankAdapterHelp"
            >
              <Select
                v-model:value="formData.adapterCode"
                :options="rerankAdapterOptions"
                :placeholder="$t('ui.formRules.required')"
              />
            </FormItem>
          </Col>
          <Col v-else :span="12">
            <FormItem label="最大输入 Token">
              <InputNumber
                v-model:value="formData.maxInputTokens"
                :min="1"
                class="w-full"
              />
            </FormItem>
          </Col>
        </Row>
        <Row v-if="formData.category === 'vector'" :gutter="16">
          <Col :span="12">
            <FormItem label="模型维度">
              <InputNumber
                v-model:value="formData.modelDimension"
                :min="1"
                class="w-full"
              />
            </FormItem>
          </Col>
          <Col :span="12">
            <FormItem label="向量归一化">
              <Select
                v-model:value="normalizedValue"
                :options="[
                  { label: '是', value: 'true' },
                  { label: '否', value: 'false' },
                ]"
              />
            </FormItem>
          </Col>
        </Row>
        <Row v-if="formData.category === 'rerank'" :gutter="16">
          <Col :span="12">
            <FormItem label="单文档最大 Token">
              <InputNumber
                v-model:value="formData.maxDocumentTokens"
                :min="1"
                class="w-full"
              />
            </FormItem>
          </Col>
        </Row>
      </template>

      <Row :gutter="16">
        <Col v-if="showApiHost" :span="24">
          <FormItem
            label="请求地址"
            v-bind="validateInfos.apiHost"
            :extra="apiHostHelp"
          >
            <Input
              v-model:value="formData.apiHost"
              :placeholder="$t('ui.formRules.required')"
            />
          </FormItem>
        </Col>
      </Row>
      <FormItem label="备注" v-bind="validateInfos.remark">
        <Textarea
          v-model:value="formData.remark"
          :placeholder="$t('ui.formRules.required')"
          :rows="4"
        />
      </FormItem>

      <template v-if="formData.category === 'chat'">
        <div class="context-policy-title">自动路由</div>
        <Row :gutter="16">
          <Col :span="16">
            <FormItem label="能力标签">
              <Select
                v-model:value="chatCapabilities"
                mode="multiple"
                :options="capabilityOptions"
                placeholder="未配置时按通用模型处理"
              />
            </FormItem>
          </Col>
          <Col :span="8">
            <FormItem label="路由优先级">
              <InputNumber
                v-model:value="formData.grayWeight"
                :min="0"
                :max="10000"
                class="w-full"
              />
            </FormItem>
          </Col>
        </Row>
        <Row :gutter="16">
          <Col :span="12">
            <FormItem label="输入成本/百万 Token">
              <InputNumber
                v-model:value="formData.inputCostPerMillion"
                :min="0"
                class="w-full"
              />
            </FormItem>
          </Col>
          <Col :span="12">
            <FormItem label="输出成本/百万 Token">
              <InputNumber
                v-model:value="formData.outputCostPerMillion"
                :min="0"
                class="w-full"
              />
            </FormItem>
          </Col>
        </Row>
        <div class="context-policy-title">上下文策略</div>
        <Row :gutter="16">
          <Col :span="12">
            <FormItem label="最大上下文 Token">
              <InputNumber
                v-model:value="formData.maxContextTokens"
                :min="1"
                class="w-full"
              />
            </FormItem>
          </Col>
          <Col :span="12">
            <FormItem label="最大输入 Token">
              <InputNumber
                v-model:value="formData.maxInputTokens"
                :min="1"
                class="w-full"
              />
            </FormItem>
          </Col>
        </Row>
        <Row :gutter="16">
          <Col :span="12">
            <FormItem
              label="最大输出 Token"
              v-bind="validateInfos.maxOutputTokens"
            >
              <InputNumber
                v-model:value="formData.maxOutputTokens"
                :min="1"
                :placeholder="$t('ui.formRules.required')"
                class="w-full"
              />
            </FormItem>
          </Col>
          <Col :span="12">
            <FormItem label="安全余量 Token">
              <InputNumber
                v-model:value="formData.safetyMarginTokens"
                :min="1"
                class="w-full"
              />
            </FormItem>
          </Col>
        </Row>
        <Row :gutter="16">
          <Col :span="12">
            <FormItem label="最近历史最大轮数">
              <InputNumber
                v-model:value="formData.historyMaxTurns"
                :min="1"
                class="w-full"
              />
            </FormItem>
          </Col>
          <Col :span="12">
            <FormItem label="知识库最大检索片段数">
              <InputNumber
                v-model:value="formData.knowledgeMaxChunks"
                :min="1"
                class="w-full"
              />
            </FormItem>
          </Col>
        </Row>
        <Row :gutter="16">
          <Col :span="12">
            <FormItem label="知识库资料最大 Token">
              <InputNumber
                v-model:value="formData.knowledgeMaxTokens"
                :min="1"
                class="w-full"
              />
            </FormItem>
          </Col>
          <Col :span="12">
            <FormItem label="单次工具结果最大 Token">
              <InputNumber
                v-model:value="formData.toolResultMaxTokens"
                :min="1"
                class="w-full"
              />
            </FormItem>
          </Col>
        </Row>
      </template>
    </Form>
  </BasicModal>
</template>

<style scoped>
:deep(.ant-form-item) {
  padding: 0 8px;
  margin-bottom: 20px;
}

:deep(.ant-form-item-label) {
  padding-bottom: 8px;
  text-align: left !important;
}

:deep(.ant-form-item-label > label) {
  justify-content: flex-start !important;
  font-weight: 500;
  color: rgb(0 0 0 / 85%);
  text-align: left !important;
}

:deep(.ant-form-item-control) {
  text-align: left !important;
}

:deep(.ant-form-item-control-input) {
  text-align: left !important;
}

:deep(.ant-input),
:deep(.ant-select),
:deep(.ant-picker),
:deep(.ant-textarea) {
  text-align: left !important;
}

:deep(.ant-input::placeholder),
:deep(.ant-textarea::placeholder) {
  color: rgb(0 0 0 / 45%);
}

.context-policy-title {
  margin: 4px 8px 12px;
  color: rgb(0 0 0 / 85%);
  font-weight: 600;
}
</style>
