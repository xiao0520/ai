<script setup lang="ts">
import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import { $t } from '@vben/locales';
import { cloneDeep } from '@vben/utils';

import { message } from 'ant-design-vue';

import { useVbenForm } from '#/adapter/form';
import { skillAdd, skillInfo, skillUpdate } from '#/api/agent/skill';
import { defaultFormValueGetter, useBeforeCloseDiff } from '#/utils/popup';

import { drawerSchema } from './data';

const emit = defineEmits<{ reload: [] }>();

const isUpdate = ref(false);
const title = computed(() => {
  return isUpdate.value ? $t('pages.common.edit') : $t('pages.common.add');
});

const [BasicForm, formApi] = useVbenForm({
  commonConfig: {
    formItemClass: 'col-span-2',
    componentProps: {
      class: 'w-full',
    },
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
  async onOpenChange(isOpen) {
    if (!isOpen) {
      return null;
    }
    drawerApi.drawerLoading(true);

    const { id } = drawerApi.getData() as { id?: number | string };
    isUpdate.value = !!id;
    // Skill 编码不允许直接修改，编辑时禁用
    formApi.updateSchema([
      {
        componentProps: { disabled: isUpdate.value },
        fieldName: 'skillName',
      },
    ]);
    if (isUpdate.value && id) {
      const record = await skillInfo(id);
      await formApi.setValues(record);
    }
    await markInitialized();

    drawerApi.drawerLoading(false);
  },
});

/**
 * 校验 JSON 字段格式（Schema / 资源限制）
 */
function validateJsonFields(data: Record<string, any>) {
  const jsonFields: Array<{ field: string; label: string }> = [
    { field: 'parameterSchema', label: '参数 Schema' },
    { field: 'outputSchema', label: '输出 Schema' },
    { field: 'resourceLimits', label: '资源限制' },
  ];
  for (const { field, label } of jsonFields) {
    const value = data[field];
    if (!value) {
      continue;
    }
    try {
      JSON.parse(value);
    } catch {
      message.error(`${label}必须是合法的 JSON`);
      return false;
    }
  }
  return true;
}

async function handleConfirm() {
  try {
    drawerApi.lock(true);
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    const data = cloneDeep(await formApi.getValues());
    if (!validateJsonFields(data)) {
      return;
    }
    await (isUpdate.value ? skillUpdate(data) : skillAdd(data));
    resetInitialized();
    emit('reload');
    drawerApi.close();
  } catch (error) {
    console.error(error);
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
  <BasicDrawer :title="title" class="w-[760px]">
    <BasicForm />
  </BasicDrawer>
</template>
