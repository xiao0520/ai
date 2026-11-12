<!-- 公共聊天输入框组件 -->
<script setup lang="ts">
import type { FilesCardProps } from 'vue-element-plus-x/types/FilesCard';
import { nextTick, ref, watch } from 'vue';
import { Sender } from 'vue-element-plus-x';
import AgentSelect from '@/components/AgentSelect/index.vue';
import FilesSelect from '@/components/FilesSelect/index.vue';
import ModelSelect from '@/components/ModelSelect/index.vue';
import { useAgentStore } from '@/stores/modules/agent';
import { useFilesStore } from '@/stores/modules/files';
import { useUserStore } from '@/stores/modules/user';

const props = defineProps<{
  modelValue?: string;
  loading?: boolean;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: string];
  'submit': [content: string];
  'cancel': [];
}>();

const filesStore = useFilesStore();
const userStore = useUserStore();
const agentStore = useAgentStore();
const isLoggedIn = computed(() => !!userStore.token);
const isApplicationMode = computed(() => !!agentStore.currentAgentInfo?.id);

const senderValue = computed({
  get: () => props.modelValue || '',
  set: val => emit('update:modelValue', val),
});

const senderRef = ref<InstanceType<typeof Sender> | null>(null);

function handleSubmit() {
  if (!isLoggedIn.value) {
    userStore.ensureLogin('/chat', '登录后即可开始对话');
    return;
  }

  emit('submit', senderValue.value);
}

function handleCancel() {
  emit('cancel');
}

function handleDeleteCard(_item: FilesCardProps, index: number) {
  filesStore.deleteFileByIndex(index);
}

// 暴露方法供父组件调用
function openHeader() {
  nextTick(() => {
    senderRef.value?.openHeader();
  });
}

function closeHeader() {
  nextTick(() => {
    senderRef.value?.closeHeader();
  });
}

// 附件卡片放在 Sender 顶部区域；等待组件更新后再切换顶部区域。
watch(
  () => filesStore.filesList.length,
  (val) => {
    if (val > 0) {
      openHeader();
    }
    else {
      closeHeader();
    }
  },
);

defineExpose({
  openHeader,
  closeHeader,
});
</script>

<template>
  <Sender
    ref="senderRef"
    v-model="senderValue"
    class="chat-sender"
    :auto-size="{
      maxRows: 6,
      minRows: 1,
    }"
    variant="updown"
    allow-speech
    :loading="loading"
    @submit="handleSubmit"
    @cancel="handleCancel"
  >
    <template #header>
      <div class="sender-header p-12px pt-6px pb-0px">
        <Attachments
          :items="filesStore.filesList"
          :hide-upload="true"
          @delete-card="handleDeleteCard"
        >
          <template #prev-button="{ show, onScrollLeft }">
            <div
              v-if="show"
              class="prev-next-btn left-8px flex-center w-22px h-22px rounded-8px border-1px border-solid border-[rgba(0,0,0,0.08)] c-[rgba(0,0,0,.4)] hover:bg-#f3f4f6 bg-#fff font-size-10px"
              @click="onScrollLeft"
            >
              <el-icon>
                <ArrowLeftBold />
              </el-icon>
            </div>
          </template>
          <template #next-button="{ show, onScrollRight }">
            <div
              v-if="show"
              class="prev-next-btn right-8px flex-center w-22px h-22px rounded-8px border-1px border-solid border-[rgba(0,0,0,0.08)] c-[rgba(0,0,0,.4)] hover:bg-#f3f4f6 bg-#fff font-size-10px"
              @click="onScrollRight"
            >
              <el-icon>
                <ArrowRightBold />
              </el-icon>
            </div>
          </template>
        </Attachments>
      </div>
    </template>
    <template #prefix>
      <div class="sender-prefix-container">
        <FilesSelect />

        <div class="sender-mode-select">
          <AgentSelect v-if="isApplicationMode" />
          <ModelSelect v-else />
        </div>
      </div>
    </template>
  </Sender>
</template>

<style scoped lang="scss">
.chat-sender {
  width: 100%;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 18px;
  box-shadow: 0 4px 18px rgb(0 0 0 / 6%);
  &:focus-within {
    border-color: var(--el-border-color);
    box-shadow: 0 6px 22px rgb(0 0 0 / 8%);
  }
  :deep(.el-sender-content) {
    gap: 8px;
    padding: 12px;
  }
}
:deep(.el-sender-prefix) {
  flex: 1;
  width: 100%;
  min-width: 0;
}
.sender-prefix-container {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  min-width: 0;
}
.sender-mode-select {
  display: flex;
  align-items: center;
  min-width: 0;
  margin-left: auto;
  :deep(.model-select-box),
  :deep(.agent-select-box) {
    max-width: min(260px, 42vw);
    padding: 6px 8px;
    font-weight: 400;
    color: var(--el-text-color-primary);
    background: transparent;
    border-color: transparent;
    &:hover {
      background: var(--el-fill-color-light);
      border-color: transparent;
    }
  }
  :deep(.model-select-box-text),
  :deep(.agent-select-box-text) {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
</style>
