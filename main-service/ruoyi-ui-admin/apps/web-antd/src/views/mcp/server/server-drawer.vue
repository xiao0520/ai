<script setup lang="ts">
import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import { $t } from '@vben/locales';
import { cloneDeep } from '@vben/utils';

import { useVbenForm } from '#/adapter/form';
import {
  mcpServerAdd,
  mcpServerInfo,
  mcpServerUpdate,
} from '#/api/mcp/server';
import { defaultFormValueGetter, useBeforeCloseDiff } from '#/utils/popup';

import { drawerSchema } from './data';

const emit = defineEmits<{ reload: [] }>();
const isUpdate = ref(false);
const title = computed(() =>
  isUpdate.value ? $t('pages.common.edit') : $t('pages.common.add'),
);

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
      const { id } = drawerApi.getData() as { id?: number | string };
      isUpdate.value = !!id;
      if (id) await formApi.setValues(await mcpServerInfo(id));
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
    await (isUpdate.value ? mcpServerUpdate(data) : mcpServerAdd(data));
    resetInitialized();
    emit('reload');
    drawerApi.close();
  } finally {
    drawerApi.lock(false);
  }
}

async function handleClosed() {
  await formApi.resetForm();
  resetInitialized();
}
</script>

<template>
  <BasicDrawer :title="title" class="w-[720px]">
    <BasicForm />
  </BasicDrawer>
</template>
