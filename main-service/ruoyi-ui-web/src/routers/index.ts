import type { NavigationGuardNext, RouteLocationNormalized } from 'vue-router';
import { useNProgress } from '@vueuse/integrations/useNProgress';
import { createRouter, createWebHistory } from 'vue-router';
import { ROUTER_WHITE_LIST } from '@/config';
import { errorRouter, layoutRouter, staticRouter } from '@/routers/modules/staticRouter';
import { useUserStore } from '@/stores';

const { start, done } = useNProgress(0, {
  showSpinner: false,
  trickleSpeed: 200,
  minimum: 0.3,
  easing: 'ease',
  speed: 500,
});

const router = createRouter({
  history: createWebHistory(),
  routes: [...layoutRouter, ...staticRouter, ...errorRouter],
  strict: false,
  scrollBehavior: () => ({ left: 0, top: 0 }),
});

// 白名单页面直接放行；其余页面在缺少登录态时清理状态并返回聊天首页。
router.beforeEach(
  async (
    to: RouteLocationNormalized,
    _from: RouteLocationNormalized,
    next: NavigationGuardNext,
  ) => {
    const userStore = useUserStore();

    // 所有导航共用同一进度条，后置守卫或错误回调负责结束。
    start();

    // 页面标题优先使用路由配置，未配置时回退到站点标题。
    document.title = (to.meta.title as string) || (import.meta.env.VITE_WEB_TITLE as string);

    // 登录失效由请求层处理，这里只判断当前是否有本地登录态。
    // 白名单中的静态页面允许匿名访问。
    if (ROUTER_WHITE_LIST.includes(to.path))
      return next();

    // 非白名单页面缺少 token 时清理身份并导航回聊天首页。
    if (!userStore.token)
      userStore.logout();

    next();
  },
);

// 路由跳转错误
router.onError((error) => {
  // 结束全屏动画
  done();
  console.warn('路由错误', error.message);
});

// 后置路由
router.afterEach(() => {
  // 结束全屏动画
  done();
});

export default router;
