import type { HookFetchPlugin } from 'hook-fetch';
import { ElMessage } from 'element-plus';
import hookFetch from 'hook-fetch';
import { sseTextDecoderPlugin } from 'hook-fetch/plugins';
import router from '@/routers';
import { useUserStore } from '@/stores';

interface BaseResponse {
  code: number;
  data: never;
  msg: string;
  rows: never;
}

export const request = hookFetch.create<BaseResponse, 'data' | 'rows'>({
  baseURL: import.meta.env.VITE_API_URL,
  headers: {
    'Content-Type': 'application/json',
  },
  plugins: [sseTextDecoderPlugin({ json: true, prefix: 'data:' })],
});

/** 统一附加身份信息，并将业务码 401/403 转为登录或权限页面的状态变化。 */
function jwtPlugin(): HookFetchPlugin<BaseResponse> {
  const userStore = useUserStore();
  return {
    name: 'jwt',
    beforeRequest: async (config) => {
      config.headers = new Headers(config.headers);
      if (config.data instanceof FormData) {
        config.headers.delete('Content-Type');
      }
      config.headers.set('authorization', `Bearer ${userStore.token}`);
      config.headers.set('ClientID', import.meta.env.VITE_CLIENT_ID);
      return config;
    },
    afterResponse: async (response) => {
      // console.log(response);
      if (response.result?.code === 200) {
        return response;
      }
      // 处理403逻辑
      if (response.result?.code === 403) {
        // 跳转到403页面（确保路由已配置）
        router.replace({
          name: '403',
        });
        ElMessage.error(response.result?.msg);
        return Promise.reject(response);
      }
      // 同一批请求可能同时过期；store 负责合并重复弹框和跳转。
      if (response.result?.code === 401) {
        userStore.handleAuthExpired(
          router.currentRoute.value.fullPath,
          '登录状态已失效，请重新登录',
        );
        return Promise.reject(response);
      }
      ElMessage.error(response.result?.msg);
      return Promise.reject(response);
    },
  };
}

request.use(jwtPlugin());

export const post = request.post;

export const get = request.get;

export const put = request.put;

export const del = request.delete;

export default request;
