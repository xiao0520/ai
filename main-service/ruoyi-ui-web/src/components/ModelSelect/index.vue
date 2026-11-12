<!-- 切换模型 -->
<script setup lang="ts">
import type { MyModelOptionVO } from '@/api/model/types';
import { Check } from '@element-plus/icons-vue';
import Popover from '@/components/Popover/index.vue';
import SvgIcon from '@/components/SvgIcon/index.vue';
import { useAgentStore } from '@/stores/modules/agent';
import { useModelStore } from '@/stores/modules/model';
import { useUserStore } from '@/stores/modules/user';

const modelStore = useModelStore();
const agentStore = useAgentStore();
const userStore = useUserStore();
const isLoggedIn = computed(() => !!userStore.token);

// 页面首次打开可能尚未登录；Token 变化后重新拉取，避免沿用其他账号的模型列表。
watch(
  () => userStore.token,
  () => modelStore.requestModelList(),
  { immediate: true },
);

const currentModelName = computed(
  () => {
    if (!isLoggedIn.value)
      return '登录后选择模型';

    if (modelStore.modelList.length === 0)
      return '暂无可用模型';

    return modelStore.selectionMode === 'AUTO'
      ? '默认'
      : modelStore.currentModelInfo?.modelName || '默认';
  },
);
const popoverList = computed(() => modelStore.modelList);

/* 弹出面板 开始 */
const popoverStyle = ref({
  width: '240px',
  maxWidth: 'calc(100vw - 24px)',
  padding: '4px',
  height: 'fit-content',
  background: 'var(--el-bg-color, #fff)',
  border: '1px solid var(--el-border-color-light)',
  borderRadius: '8px',
  boxShadow: '0 2px 12px 0 rgba(0, 0, 0, 0.1)',
});
const popoverRef = ref<InstanceType<typeof Popover>>();

// 显示
async function showPopover() {
  if (!isLoggedIn.value) {
    userStore.ensureLogin('/chat', '登录后可选择专属模型');
    popoverRef.value?.hide?.();
    return;
  }

  // 获取最新的模型列表
  await modelStore.requestModelList();
}

// 选择 AUTO 时由 Java 限定候选，再由 LangChain 在候选内路由。
function handleAutoClick() {
  agentStore.clearCurrentAgentInfo();
  modelStore.selectAuto();
  popoverRef.value?.hide?.();
}

// 点击具体模型进入 FIXED。
function handleClick(item: MyModelOptionVO) {
  agentStore.clearCurrentAgentInfo();
  modelStore.setCurrentModelInfo(item);
  popoverRef.value?.hide?.();
}
</script>

<template>
  <div class="model-select">
    <Popover
      ref="popoverRef"
      placement="top-start"
      :offset="[4, 0]"
      popover-class="popover-content"
      :popover-style="popoverStyle"
      trigger="clickTarget"
      @show="showPopover"
    >
      <!-- 触发元素插槽 -->
      <template #trigger>
        <div
          class="model-select-box select-none flex items-center gap-4px p-10px rounded-10px cursor-pointer font-size-12px"
        >
          <div class="model-select-box-icon">
            <SvgIcon name="models" size="12" />
          </div>
          <div class="model-select-box-text font-size-12px">
            {{ currentModelName }}
          </div>
        </div>
      </template>

      <div class="model-menu" role="group" aria-label="选择模型">
        <div class="model-menu-title">
          选择模型
        </div>
        <button
          type="button"
          class="model-menu-option"
          :aria-pressed="modelStore.selectionMode === 'AUTO'"
          @click="handleAutoClick"
        >
          <span class="model-menu-label">
            <span>默认</span>
            <span class="model-menu-description">推荐模型集</span>
          </span>
          <Check v-if="modelStore.selectionMode === 'AUTO'" class="model-menu-check" aria-hidden="true" />
        </button>
        <Popover
          v-for="item in popoverList"
          :key="item.id"
          trigger-class="model-menu-trigger"
          popover-class="rounded-tooltip"
          placement="right"
          trigger="hover"
          :offset="[12, 0]"
        >
          <template #trigger>
            <button
              type="button"
              class="model-menu-option"
              :aria-pressed="modelStore.selectionMode === 'FIXED' && item.id === modelStore.currentModelInfo?.id"
              @click="handleClick(item)"
            >
              <span class="model-menu-name" :title="item.modelName">{{ item.modelName }}</span>
              <Check
                v-if="modelStore.selectionMode === 'FIXED' && item.id === modelStore.currentModelInfo?.id"
                class="model-menu-check"
                aria-hidden="true"
              />
            </button>
          </template>
          <div v-if="item.remark" class="model-menu-tooltip">
            {{ item.remark }}
          </div>
        </Popover>
        <div v-if="popoverList.length === 0" class="model-menu-empty">
          暂无可用模型
        </div>
      </div>
    </Popover>
  </div>
</template>

<style scoped lang="scss">
.model-select-box {
  font-weight: 600;
  color: rgb(0 0 0 / 85%);
  color: var(--el-color-primary, #409eff);

  // 选中状态（模型始终选中，显示蓝色）
  background: var(--el-color-primary-light-9, rgb(235.9 245.3 255));
  background-color: #ffffff;
  border: 1px solid rgb(0 0 0 / 10%);
  border-color: var(--el-color-primary, #409eff);
  transition: all 0.2s ease;
  &:hover {
    background-color: rgb(0 0 0 / 4%);
    border-color: rgb(0 0 0 / 15%);
  }
}
.model-menu {
  display: flex;
  flex-direction: column;
  max-height: min(320px, calc(100dvh - 32px));
  overflow: hidden auto;
  font-size: 12px;
  color: var(--el-text-color-primary);
  :deep(.model-menu-trigger) {
    width: 100%;
  }
  &::-webkit-scrollbar {
    width: 4px;
  }
  &::-webkit-scrollbar-thumb {
    background: var(--el-border-color);
    border-radius: 4px;
  }
}
.model-menu-title,
.model-menu-empty {
  padding: 6px 8px;
  line-height: 18px;
  color: var(--el-text-color-secondary);
}
.model-menu-option {
  display: flex;
  flex-shrink: 0;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  min-height: 30px;
  padding: 6px 8px;
  font: inherit;
  line-height: 18px;
  color: inherit;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 6px;
  &:hover {
    background: var(--el-fill-color-light);
  }
  &:focus-visible {
    outline: 2px solid var(--el-color-primary);
    outline-offset: -2px;
  }
}
.model-menu-label {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.model-menu-description {
  color: var(--el-text-color-secondary);
}
.model-menu-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.model-menu-check {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
  color: var(--el-text-color-regular);
}
.model-menu-tooltip {
  max-width: 200px;
  padding: 8px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--el-bg-color);
  overflow-wrap: anywhere;
  background: var(--el-text-color-primary);
  border-radius: 8px;
}
</style>
