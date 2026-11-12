<!-- 账号密码登录表单 -->
<script lang="ts" setup>
import type { FormInstance, FormRules } from 'element-plus';
import type { CaptchaResponse, LoginDTO } from '@/api/auth/types';
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { captchaImage, login } from '@/api';
import { useUserStore } from '@/stores';
import { useSessionStore } from '@/stores/modules/session';

const userStore = useUserStore();
const sessionStore = useSessionStore();

const formRef = ref<FormInstance>();
const isSubmitting = ref(false);
const REMEMBER_ACCOUNT_KEY = `REMEMBER_ME_USERNAME_${location.hostname}`;
const rememberedUsername = localStorage.getItem(REMEMBER_ACCOUNT_KEY) || '';
const rememberAccount = ref(!!rememberedUsername);

const formModel = reactive<LoginDTO>({
  username: rememberedUsername,
  password: '',
  code: '',
  clientId: import.meta.env.VITE_CLIENT_ID,
  grantType: 'password',
  uuid: '',
});

const rules = reactive<FormRules<LoginDTO>>({
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入登录密码', trigger: 'blur' }],
  code: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
});

const captchaInfo = ref<CaptchaResponse>({ captchaEnabled: false });
const captchaLoading = ref(false);

/** 每次获取验证码都同步更新 uuid，并清空旧输入，避免提交过期验证码。 */
async function loadCaptcha() {
  try {
    captchaLoading.value = true;
    const response = await captchaImage();
    const captcha = (response as { data?: CaptchaResponse }).data ?? response;
    captchaInfo.value = captcha as CaptchaResponse;
    formModel.code = '';
    formModel.uuid = captcha.uuid || '';
  }
  finally {
    captchaLoading.value = false;
  }
}

onMounted(loadCaptcha);

const router = useRouter();
/** 登录成功后恢复会话与原目标页；失败时刷新可能已消费的验证码。 */
async function handleSubmit() {
  if (isSubmitting.value)
    return;

  try {
    isSubmitting.value = true;
    await formRef.value?.validate();
    if (captchaInfo.value.captchaEnabled && !formModel.uuid) {
      throw new Error('验证码已失效，请刷新后重试');
    }
    if (rememberAccount.value) {
      localStorage.setItem(REMEMBER_ACCOUNT_KEY, formModel.username);
    }
    else {
      localStorage.removeItem(REMEMBER_ACCOUNT_KEY);
    }
    const res = await login(formModel);
    const loginData = res?.data ?? res;
    const token = loginData?.access_token || loginData?.token;
    if (!token) {
      // 请求层已提示后端业务异常，避免将验证码等登录失败误报为令牌缺失。
      if (captchaInfo.value.captchaEnabled) {
        await loadCaptcha();
      }
      return;
    }
    userStore.setToken(token);
    userStore.setUserInfo({ username: formModel.username });
    userStore.resetAuthExpiredHandling();
    ElMessage.success('登录成功');
    userStore.closeLoginDialog();
    // 登录后主动请求会话列表，供侧栏立即展示。
    await sessionStore.requestSessionList(1, true);
    const redirectPath = userStore.consumeLoginRedirectPath();
    router.replace(redirectPath || '/');
  }
  catch (error) {
    console.error('请求错误:', error);
    ElMessage.error(error instanceof Error ? error.message : '登录失败，请稍后重试');
    if (captchaInfo.value.captchaEnabled) {
      await loadCaptcha();
    }
  }
  finally {
    isSubmitting.value = false;
  }
}
</script>

<template>
  <div class="custom-form">
    <el-form
      ref="formRef"
      :model="formModel"
      :rules="rules"
      class="login-form"
      @submit.prevent="handleSubmit"
    >
      <el-form-item prop="username">
        <el-input v-model="formModel.username" autocomplete="username" name="username" placeholder="请输入账号">
          <template #prefix>
            <el-icon>
              <User />
            </el-icon>
          </template>
        </el-input>
      </el-form-item>
      <el-form-item prop="password">
        <el-input
          v-model="formModel.password"
          placeholder="请输入登录密码"
          type="password"
          show-password
          autocomplete="current-password"
          name="password"
        >
          <template #prefix>
            <el-icon>
              <Lock />
            </el-icon>
          </template>
        </el-input>
      </el-form-item>
      <el-form-item v-if="captchaInfo.captchaEnabled" prop="code">
        <el-input v-model="formModel.code" placeholder="请输入验证码" @keyup.enter="handleSubmit">
          <template #append>
            <button
              class="captcha-button"
              type="button"
              :disabled="captchaLoading"
              title="点击刷新验证码"
              @click="loadCaptcha"
            >
              <img v-if="captchaInfo.img" :src="`data:image/png;base64,${captchaInfo.img}`" alt="验证码" class="captcha-image">
              <span v-else>加载中</span>
            </button>
          </template>
        </el-input>
      </el-form-item>
      <el-form-item class="remember-account-item">
        <el-checkbox v-model="rememberAccount">
          记住账号
        </el-checkbox>
      </el-form-item>
      <el-form-item>
        <el-button
          class="submit-btn"
          type="primary"
          native-type="submit"
          :loading="isSubmitting"
          :disabled="isSubmitting"
        >
          立即登录
        </el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<style scoped lang="scss">
.custom-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.login-form {
  width: 100%;
}

.submit-btn {
  width: 100%;
  height: 40px;
  font-size: 14px;
  font-weight: 600;
  background: linear-gradient(135deg, #2563eb 0%, #1d4ed8 100%);
  border: none;
  border-radius: 10px;
  box-shadow: 0 10px 20px rgb(37 99 235 / 18%);
}

:deep(.el-input__wrapper) {
  min-height: 44px;
  border-radius: 10px;
  box-shadow: 0 0 0 1px rgb(148 163 184 / 18%);
}

:deep(.el-input__inner) {
  font-size: 13px;
}

:deep(.el-input__inner:-webkit-autofill),
:deep(.el-input__inner:-webkit-autofill:hover),
:deep(.el-input__inner:-webkit-autofill:focus),
:deep(.el-input__inner:-webkit-autofill:active) {
  -webkit-text-fill-color: #1f2937;
  -webkit-box-shadow: 0 0 0 1000px #fff inset;
  box-shadow: 0 0 0 1000px #fff inset;
  caret-color: #1f2937;
  transition: background-color 9999s ease-out 0s;
}

:deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px rgb(37 99 235 / 48%);
}

:deep(.el-form-item) {
  margin-bottom: 20px;
}

:deep(.el-form-item:last-child) {
  margin-bottom: 0;
}

:deep(.remember-account-item) {
  margin-top: -6px;
  margin-bottom: 14px;
}

:deep(.remember-account-item .el-checkbox__label) {
  color: #64748b;
  font-size: 13px;
}

:deep(.el-form-item__error) {
  padding-top: 5px;
  font-size: 12px;
  line-height: 1.25;
}

.captcha-button {
  display: flex;
  align-items: center;
  min-width: 88px;
  height: 30px;
  padding: 0;
  border: 0;
  background: transparent;
  color: #64748b;
  cursor: pointer;
}

.captcha-button:disabled {
  cursor: wait;
}

.captcha-image {
  width: 88px;
  height: 30px;
  object-fit: contain;
}
</style>
