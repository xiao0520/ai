<script setup lang="ts">
import type { AgentVO } from '@/api/agent/types';
import { useRouter } from 'vue-router';
import { useAgentStore } from '@/stores/modules/agent';
import { useUserStore } from '@/stores/modules/user';

const router = useRouter();
const agentStore = useAgentStore();
const userStore = useUserStore();
const loading = ref(false);

async function loadApplications() {
  loading.value = true;
  try {
    await agentStore.requestAgentList();
  }
  finally {
    loading.value = false;
  }
}

onMounted(loadApplications);

// 先记录智能体选择，聊天页再从 store 读取当前应用模式。
async function selectAgent(agent: AgentVO) {
  if (!userStore.token) {
    ElMessage.warning('请先登录');
    return;
  }
  agentStore.setCurrentAgentInfo(agent);
  await router.push({ name: agent.agentCode === 'contract-review' ? 'contractReview' : 'chat' });
}
</script>

<template>
  <div class="app-market">
    <div class="market-header">
      <h2 class="market-title">
        智能体
      </h2>
      <p class="market-subtitle">
        选择智能体，进入对应工作页即可使用
      </p>
    </div>

    <div v-loading="loading" class="market-grid">
      <el-empty v-if="!loading && agentStore.agentList.length === 0" description="暂无可用智能体" />
      <div
        v-for="agent in agentStore.agentList"
        :key="agent.id"
        class="agent-card"
        @click="selectAgent(agent)"
      >
        <div class="agent-card-icon">
          <el-icon :size="28">
            <Cpu />
          </el-icon>
        </div>
        <div class="agent-card-body">
          <div class="agent-card-title">
            {{ agent.agentDescribe || agent.agentName }}
          </div>
          <div class="agent-card-desc">
            {{ agent.agentShow || '智能体应用' }}
          </div>
        </div>
        <div class="agent-card-footer">
          <el-tag size="small" effect="plain">
            智能体
          </el-tag>
          <el-button type="primary" size="small" round>
            开始使用
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.app-market {
  height: 100%;
  padding: 16px 24px;
  overflow-y: auto;
  background-color: var(--el-bg-color, #fff);
  border-radius: 12px;
}

.market-header {
  margin-bottom: 20px;

  .market-title {
    margin: 0 0 4px;
    font-size: 22px;
    font-weight: 600;
  }

  .market-subtitle {
    margin: 0;
    color: #909399;
    font-size: 13px;
  }
}

.market-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
  min-height: 200px;
}

.agent-card {
  display: flex;
  flex-direction: column;
  padding: 16px;
  cursor: pointer;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 12px;
  transition: all 0.25s ease;

  &:hover {
    border-color: #409eff;
    box-shadow: 0 4px 16px rgb(64 158 255 / 12%);
    transform: translateY(-2px);
  }
}

.agent-card-icon {
  display: flex;
  width: 48px;
  height: 48px;
  margin-bottom: 12px;
  color: #fff;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 10px;
  align-items: center;
  justify-content: center;
}

.agent-card-body {
  flex: 1;
  margin-bottom: 12px;
}

.agent-card-title {
  margin-bottom: 6px;
  color: #303133;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
}

.agent-card-desc {
  display: -webkit-box;
  min-height: 39px;
  overflow: hidden;
  color: #909399;
  font-size: 13px;
  line-height: 1.5;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.agent-card-footer {
  display: flex;
  padding-top: 10px;
  border-top: 1px solid #f0f0f0;
  align-items: center;
  justify-content: space-between;
}
</style>
