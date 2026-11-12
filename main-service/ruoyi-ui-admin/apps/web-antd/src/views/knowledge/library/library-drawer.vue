<script setup lang="ts">
import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import { $t } from '@vben/locales';
import { cloneDeep } from '@vben/utils';

import { message } from 'ant-design-vue';

import { useVbenForm } from '#/adapter/form';
import {
  knowledgeLibraryAdd,
  knowledgeLibraryInfo,
  knowledgeLibraryUpdate,
} from '#/api/knowledge/library';
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

// 保存初始表单快照，关闭抽屉时才能识别未保存的修改。
const { onBeforeClose, markInitialized, resetInitialized } = useBeforeCloseDiff({
  initializedGetter: defaultFormValueGetter(formApi),
  currentGetter: defaultFormValueGetter(formApi),
});

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
      // 知识库编码是稳定标识，已有知识库不允许编辑。
      formApi.updateSchema([
        { componentProps: { disabled: isUpdate.value }, fieldName: 'libraryCode' },
      ]);
      if (id) {
        await formApi.setValues(await knowledgeLibraryInfo(id));
      } else {
        await formApi.setValues({
          accessScope: 'ACL_ONLY',
          splitterConfig: '{"chunkSize":800,"overlap":100}',
          status: '0',
        });
      }
      await markInitialized();
    } finally {
      drawerApi.drawerLoading(false);
    }
  },
});

function isJson(value?: string) {
  if (!value) return true;
  try {
    JSON.parse(value);
    return true;
  } catch {
    return false;
  }
}

async function handleConfirm() {
  drawerApi.lock(true);
  try {
    const { valid } = await formApi.validate();
    if (!valid) return;
    const data = cloneDeep(await formApi.getValues());
    if (!isJson(data.splitterConfig)) {
      message.error('切分策略必须是合法 JSON');
      return;
    }
    await (isUpdate.value ? knowledgeLibraryUpdate(data) : knowledgeLibraryAdd(data));
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
  <BasicDrawer :title="title" class="w-[760px]">
    <BasicForm />
  </BasicDrawer>
</template>
