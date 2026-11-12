<!-- 切换智能体 -->
<script setup lang="ts">
import type { AgentVO } from '@/api/agent/types';
import Popover from '@/components/Popover/index.vue';
import SvgIcon from '@/components/SvgIcon/index.vue';
import { useAgentStore } from '@/stores/modules/agent';
import { useModelStore } from '@/stores/modules/model';

const agentStore = useAgentStore();
const modelStore = useModelStore();

onMounted(async () => {
  await agentStore.requestAgentList();
});

const currentAgentName = computed(
  () => agentStore.currentAgentInfo && agentStore.currentAgentInfo.agentDescribe
    ? agentStore.currentAgentInfo.agentDescribe
    : agentStore.currentAgentInfo?.agentName,
);

const currentLabel = computed(() => currentAgentName.value);

const popoverList = computed(() => agentStore.agentList);

/* 弹出面板 开始 */
const popoverStyle = ref({
  width: '200px',
  padding: '4px',
  height: 'fit-content',
  background: 'var(--el-bg-color, #fff)',
  border: '1px solid var(--el-border-color-light)',
  borderRadius: '8px',
  boxShadow: '0 2px 12px 0 rgba(0, 0, 0, 0.1)',
});
const popoverRef = ref();

// 显示
async function showPopover() {
  await agentStore.requestAgentList();
}

// 智能体负责自己的模型路由，进入应用模式时清除之前的固定模型选择。
function handleClick(item: AgentVO) {
  agentStore.setCurrentAgentInfo(item);
  modelStore.selectAuto();
  popoverRef.value?.hide?.();
}

// 退出应用模式后刷新当前角色可用模型，再回到智能选择。
async function handleUseModel() {
  agentStore.clearCurrentAgentInfo();
  await modelStore.requestModelList();
  modelStore.selectAuto();
  popoverRef.value?.hide?.();
}
</script>

<template>
  <div class="agent-select">
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
          class="agent-select-box select-none flex items-center gap-4px p-10px rounded-10px cursor-pointer font-size-12px"
        >
          <div class="agent-select-box-icon">
            <SvgIcon name="models" size="12" />
          </div>
          <div class="agent-select-box-text font-size-12px">
            {{ currentLabel }}
          </div>
        </div>
      </template>

      <div class="popover-content-box">
        <div
          class="popover-content-box-items w-full rounded-8px select-none transition-all transition-duration-300 flex items-center hover:cursor-pointer hover:bg-[rgba(0,0,0,.04)]"
        >
          <div
            class="popover-content-box-item p-4px font-size-12px text-overflow line-height-16px"
            @click="handleUseModel"
          >
            <div>切换到模型</div>
            <div class="agent-sub font-size-11px opacity-60">
              使用智能选择或指定模型对话
            </div>
          </div>
        </div>

        <div
          v-for="item in popoverList"
          :key="item.id"
          class="popover-content-box-items w-full rounded-8px select-none transition-all transition-duration-300 flex items-center hover:cursor-pointer hover:bg-[rgba(0,0,0,.04)]"
        >
          <div
            class="popover-content-box-item p-4px font-size-12px text-overflow line-height-16px"
            :class="{ 'is-select': item.agentName === agentStore.currentAgentInfo?.agentName }"
            @click="handleClick(item)"
          >
            <div>{{ item.agentDescribe || item.agentName }}</div>
            <div v-if="item.modelName" class="agent-sub font-size-11px opacity-60">
              {{ item.modelName }}
            </div>
          </div>
        </div>
      </div>
    </Popover>
  </div>
</template>

<style scoped lang="scss">
.agent-select-box {
  background-color: #fff;
  border: 1px solid rgb(0 0 0 / 10%);
  color: rgb(0 0 0 / 85%);
  font-weight: 500;
  transition: all 0.2s ease;

  &:hover {
    background-color: rgb(0 0 0 / 4%);
    border-color: rgb(0 0 0 / 15%);
  }

  background: var(--el-color-primary-light-9, rgb(235.9 245.3 255));
  border-color: var(--el-color-primary, #409eff);
  color: var(--el-color-primary, #409eff);
  font-weight: 600;
}

.popover-content-box-item.is-select {
  font-weight: 700;
  color: var(--el-color-primary, #409eff);
}

.popover-content-box {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 240px;
  overflow: hidden auto;

  &::-webkit-scrollbar {
    width: 4px;
  }

  &::-webkit-scrollbar-track {
    background: #f5f5f5;
  }

  &::-webkit-scrollbar-thumb {
    background: #cccccc;
    border-radius: 4px;
  }
}
</style>
