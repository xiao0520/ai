<script setup lang="ts">
import type { Role } from '#/api/system/user/model';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import { findUserInfo, userAuthRoleUpdate } from '#/api/system/user';
import { defaultFormValueGetter, useBeforeCloseDiff } from '#/utils/popup';

const emit = defineEmits<{ reload: [] }>();

const userId = ref<number | string>();
const userName = ref('');
const title = computed(() => userName.value ? `分配角色 - ${userName.value}` : '分配角色');

const [BasicForm, formApi] = useVbenForm({
  commonConfig: {
    labelWidth: 80,
    componentProps: { class: 'w-full' },
  },
  schema: [
    {
      component: 'Select',
      componentProps: {
        mode: 'multiple',
        optionFilterProp: 'label',
        placeholder: '请选择该员工拥有的角色',
      },
      fieldName: 'roleIds',
      help: '仅修改本 AI 管理系统中的角色授权，不会修改员工、部门等基础资料。',
      label: '角色',
      rules: 'selectRequired',
    },
  ],
  showDefaultActions: false,
});

const { markInitialized, onBeforeClose, resetInitialized } = useBeforeCloseDiff({
  currentGetter: defaultFormValueGetter(formApi),
  initializedGetter: defaultFormValueGetter(formApi),
});

const [BasicModal, modalApi] = useVbenModal({
  onBeforeClose,
  onClosed: handleClosed,
  onConfirm: handleConfirm,
  onOpenChange: async (isOpen) => {
    if (!isOpen) return;
    modalApi.modalLoading(true);
    try {
      const { id } = modalApi.getData() as { id: number | string };
      const { roleIds = [], roles, user } = await findUserInfo(id);
      userId.value = id;
      userName.value = user ? `${user.userName}（${user.nickName}）` : '';
      await formApi.updateSchema([
        {
          componentProps: {
            options: roles.map((role: Role) => ({
              label: `${role.roleName}（${role.roleKey}）`,
              value: role.roleId,
            })),
          },
          fieldName: 'roleIds',
        },
      ]);
      await formApi.setFieldValue('roleIds', roleIds);
      await markInitialized();
    } finally {
      modalApi.modalLoading(false);
    }
  },
});

async function handleConfirm() {
  if (!userId.value) return;
  try {
    modalApi.lock(true);
    const { roleIds = [] } = await formApi.getValues<{ roleIds?: Array<number | string> }>();
    await userAuthRoleUpdate(userId.value, roleIds.map(Number));
    resetInitialized();
    emit('reload');
    modalApi.close();
  } finally {
    modalApi.lock(false);
  }
}

async function handleClosed() {
  userId.value = undefined;
  userName.value = '';
  await formApi.resetForm();
  resetInitialized();
}
</script>

<template>
  <BasicModal :title="title" class="w-[560px]">
    <BasicForm />
  </BasicModal>
</template>
