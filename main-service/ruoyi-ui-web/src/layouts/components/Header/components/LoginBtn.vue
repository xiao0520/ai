<!-- LoginBtn 登录按钮 -->
<script setup lang="ts">
import LoginDialog from '@/components/LoginDialog/index.vue';
import { useUserStore } from '@/stores';

const userStore = useUserStore();
const isLoginDialogVisible = computed(() => userStore.isLoginDialogVisible);

// 点击登录按钮时调用Store方法打开弹框
function handleClickLogin() {
  userStore.openLoginDialog();
}
</script>

<template>
  <div class="login-btn-wrapper">
    <button
      class="login-btn"
      type="button"
      @click="handleClickLogin"
    >
      <span class="login-icon" aria-hidden="true">
        <el-icon><User /></el-icon>
      </span>
      <span class="login-copy">
        <span class="login-title">登录</span>
        <span class="login-desc">登录后同步数据</span>
      </span>
      <el-icon class="login-arrow" aria-hidden="true"><ArrowRight /></el-icon>
    </button>

    <!-- 登录弹框 -->
    <LoginDialog v-model:visible="isLoginDialogVisible" />
  </div>
</template>

<style scoped lang="scss">
.login-btn-wrapper {
  width: 100%;
}

.login-btn {
  display: flex;
  gap: 10px;
  align-items: center;
  width: 100%;
  padding: 8px 10px;
  color: rgb(15 23 42 / 88%);
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 10px;
  transition: background-color 0.2s ease, transform 0.2s ease;

  &:hover {
    background: rgb(37 99 235 / 7%);
  }

  &:focus-visible {
    outline: 2px solid rgb(37 99 235 / 46%);
    outline-offset: 2px;
  }
}

.login-icon {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  color: #2563eb;
  background: #eff6ff;
  border: 1px solid rgb(37 99 235 / 12%);
  border-radius: 50%;
}

.login-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  line-height: 1.25;
}

.login-title {
  font-size: 14px;
  font-weight: 600;
}

.login-desc {
  margin-top: 3px;
  overflow: hidden;
  color: #94a3b8;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.login-arrow {
  flex: 0 0 auto;
  color: #94a3b8;
  font-size: 15px;
}
</style>
