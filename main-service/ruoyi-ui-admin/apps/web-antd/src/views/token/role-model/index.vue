<script setup lang="ts">
import type { Dayjs } from 'dayjs';
import type { VbenFormProps } from '@vben/common-ui';

import type { VxeGridProps } from '#/adapter/vxe-table';
import type {
  ModelOption,
  RoleModelPermission,
} from '#/api/token/model';

import { computed, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Alert,
  DatePicker,
  Form,
  FormItem,
  Input,
  Modal,
  Select,
  Spin,
  Tag,
  Textarea,
} from 'ant-design-vue';
import dayjs from 'dayjs';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  roleModelInfo,
  roleModelList,
  roleModelSave,
  tokenModelOptions,
} from '#/api/token';

import { columns, querySchema } from './data';

const modalOpen = ref(false);
const modalLoading = ref(false);
const modelLoading = ref(false);
const models = ref<ModelOption[]>([]);
const currentRole = ref<RoleModelPermission>();
const formState = reactive<{
  effectiveTime?: Dayjs;
  expireTime?: Dayjs;
  modelIds: Array<number | string>;
  remark?: string;
}>({ modelIds: [] });

const modelSelectOptions = computed(() =>
  models.value.map((model) => ({
    label: `${model.modelName}（${model.providerCode || '未配置厂商'} / ${model.category || '未分类'}）`,
    value: model.modelId,
  })),
);

const formOptions: VbenFormProps = {
  commonConfig: {
    componentProps: { allowClear: true },
    labelWidth: 80,
  },
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
        roleModelList({
          pageNum: page.currentPage,
          pageSize: page.pageSize,
          ...formValues,
        }),
    },
  },
  rowConfig: { keyField: 'roleId' },
  id: 'ai-token-role-model-index',
};

const [BasicTable, tableApi] = useVbenVxeGrid({ formOptions, gridOptions });

async function openAssign(row: RoleModelPermission) {
  modalOpen.value = true;
  modalLoading.value = true;
  modelLoading.value = true;
  try {
    const [detail, options] = await Promise.all([
      roleModelInfo(row.roleId),
      tokenModelOptions(),
    ]);
    currentRole.value = detail;
    models.value = options;
    formState.modelIds = [...detail.modelIds];
    formState.effectiveTime = detail.effectiveTime
      ? dayjs(detail.effectiveTime)
      : undefined;
    formState.expireTime = detail.expireTime
      ? dayjs(detail.expireTime)
      : undefined;
    formState.remark = detail.remark || '';
  } finally {
    modalLoading.value = false;
    modelLoading.value = false;
  }
}

async function submitAssign() {
  if (!currentRole.value) return;
  if (
    formState.effectiveTime &&
    formState.expireTime &&
    !formState.expireTime.isAfter(formState.effectiveTime)
  ) {
    return;
  }
  modalLoading.value = true;
  try {
    await roleModelSave(currentRole.value.roleId, {
      effectiveTime: formState.effectiveTime?.format('YYYY-MM-DD HH:mm:ss'),
      expireTime: formState.expireTime?.format('YYYY-MM-DD HH:mm:ss'),
      modelIds: formState.modelIds,
      remark: formState.remark,
    });
    modalOpen.value = false;
    await tableApi.query();
  } finally {
    modalLoading.value = false;
  }
}
</script>

<template>
  <Page :auto-content-height="true">
    <BasicTable table-title="角色模型权限">
      <template #status="{ row }">
        <Tag :color="row.roleStatus === '0' ? 'success' : 'default'">
          {{ row.roleStatus === '0' ? '正常' : '停用' }}
        </Tag>
      </template>
      <template #models="{ row }">
        <div
          v-if="row.models?.length"
          class="flex min-h-6 w-full flex-wrap items-center justify-center gap-1.5"
        >
          <Tag
            v-for="model in row.models.slice(0, 4)"
            :key="model.modelId"
            class="!m-0"
            color="blue"
          >
            {{ model.modelName }}
          </Tag>
          <Tag v-if="row.models.length > 4" class="!m-0">
            +{{ row.models.length - 4 }}
          </Tag>
        </div>
        <span
          v-else
          class="inline-flex w-full items-center justify-center text-gray-400"
        >
          未授权模型
        </span>
      </template>
      <template #effectiveTime="{ row }">
        <span :class="row.effectiveTime ? 'text-gray-700' : 'text-gray-400'">
          {{ row.effectiveTime || '立即生效' }}
        </span>
      </template>
      <template #expireTime="{ row }">
        <span :class="row.expireTime ? 'text-gray-700' : 'text-gray-400'">
          {{ row.expireTime || '永久有效' }}
        </span>
      </template>
      <template #action="{ row }">
        <ghost-button
          :disabled="String(row.roleId) === '1'"
          v-access:code="['token:role-model:assign']"
          @click.stop="openAssign(row)"
        >
          分配模型
        </ghost-button>
      </template>
    </BasicTable>

    <Modal
      v-model:open="modalOpen"
      :confirm-loading="modalLoading"
      title="分配角色模型"
      width="680px"
      @ok="submitAssign"
    >
      <Spin :spinning="modalLoading">
        <Alert
          class="mb-4"
          message="员工通过角色自动继承模型权限；拥有多个角色时取模型并集。"
          show-icon
          type="info"
        />
        <Form :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }">
          <FormItem label="角色名称">
            <Input :value="currentRole?.roleName" disabled />
          </FormItem>
          <FormItem label="授权模型" required>
            <Select
              v-model:value="formState.modelIds"
              :loading="modelLoading"
              :options="modelSelectOptions"
              :max-tag-count="4"
              allow-clear
              mode="multiple"
              placeholder="请选择该角色可使用的模型"
              show-search
            />
          </FormItem>
          <FormItem label="生效时间">
            <DatePicker
              v-model:value="formState.effectiveTime"
              class="w-full"
              format="YYYY-MM-DD HH:mm:ss"
              placeholder="为空则立即生效"
              show-time
            />
          </FormItem>
          <FormItem
            :validate-status="
              formState.effectiveTime && formState.expireTime &&
              !formState.expireTime.isAfter(formState.effectiveTime)
                ? 'error'
                : undefined
            "
            :help="
              formState.effectiveTime && formState.expireTime &&
              !formState.expireTime.isAfter(formState.effectiveTime)
                ? '失效时间必须晚于生效时间'
                : undefined
            "
            label="失效时间"
          >
            <DatePicker
              v-model:value="formState.expireTime"
              class="w-full"
              format="YYYY-MM-DD HH:mm:ss"
              placeholder="为空则永久有效"
              show-time
            />
          </FormItem>
          <FormItem label="备注">
            <Textarea
              v-model:value="formState.remark"
              :maxlength="500"
              :rows="3"
              show-count
            />
          </FormItem>
        </Form>
      </Spin>
    </Modal>
  </Page>
</template>
