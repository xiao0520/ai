<script setup lang="ts">
import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import { cloneDeep } from '@vben/utils';

import { message } from 'ant-design-vue';

import { useVbenForm } from '#/adapter/form';
import { mcpToolInfo, mcpToolPolicyUpdate } from '#/api/mcp/tool';
import { defaultFormValueGetter, useBeforeCloseDiff } from '#/utils/popup';

import { drawerSchema } from './data';

const emit = defineEmits<{ reload: [] }>();
const toolName = ref('');
const title = computed(() => `工具策略 - ${toolName.value || ''}`);

const [BasicForm, formApi] = useVbenForm({
  commonConfig: {
    formItemClass: 'col-span-1',
    componentProps: { class: 'w-full' },
  },
  layout: 'vertical',
  schema: drawerSchema(),
  showDefaultActions: false,
  wrapperClass: 'grid-cols-2 gap-x-4',
});

const { onBeforeClose, markInitialized, resetInitialized } = useBeforeCloseDiff(
  {
    initializedGetter: defaultFormValueGetter(formApi),
    currentGetter: defaultFormValueGetter(formApi),
  },
);

const [BasicDrawer, drawerApi] = useVbenDrawer({
  onBeforeClose,
  onClosed: handleClosed,
  onConfirm: handleConfirm,
  async onOpenChange(open) {
    if (!open) return;
    drawerApi.drawerLoading(true);
    try {
      const { id } = drawerApi.getData() as { id: number | string };
      const record = await mcpToolInfo(id);
      toolName.value = record.displayName || record.toolName;
      await formApi.setValues(record);
      await markInitialized();
    } finally {
      drawerApi.drawerLoading(false);
    }
  },
});

async function handleConfirm() {
  drawerApi.lock(true);
  try {
    const { valid } = await formApi.validate();
    if (!valid) return;
    const data = cloneDeep(await formApi.getValues());
    // 模型可见字段按 JSON 数组保存，拒绝对象或非法 JSON 以免策略失效。
    if (data.visibleResultFields) {
      try {
        const fields = JSON.parse(data.visibleResultFields);
        if (!Array.isArray(fields)) throw new Error();
      } catch {
        message.error('模型可见字段必须是合法的 JSON 数组');
        return;
      }
    }
    await mcpToolPolicyUpdate(data);
    resetInitialized();
    emit('reload');
    drawerApi.close();
  } finally {
    drawerApi.lock(false);
  }
}

async function handleClosed() {
  await formApi.resetForm();
  toolName.value = '';
  resetInitialized();
}
</script>

<template>
  <BasicDrawer :title="title" class="w-[760px]">
    <BasicForm />
  </BasicDrawer>
</template>
