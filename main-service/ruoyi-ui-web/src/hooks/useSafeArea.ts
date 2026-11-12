import type { Ref } from 'vue';

type SafeAreaDirection = 'left' | 'right' | 'top' | 'bottom';

interface SafeAreaOptions {
  direction: SafeAreaDirection; // 必选方向
  size: number; // 安全区域尺寸（像素）
  onChange?: (isInArea: boolean, e: MouseEvent) => void; // 状态变化回调
  enabled?: Ref<boolean>; // 可动态暂停监听。
}

/** 监听鼠标是否进入视口指定边缘，仅在区域内外状态变化时调用 onChange。 */
export function useSafeArea(options: SafeAreaOptions): { isInSafeArea: Ref<boolean> } {
  const { direction, size, onChange, enabled = ref(true) } = options;
  const isInSafeArea = ref(false);
  let isThrottling = false;

  // 使用视口尺寸计算边缘区域，窗口缩放后仍按最新边界判断。
  const checkInArea = (e: MouseEvent) => {
    const { clientX, clientY } = e;
    const w = window.innerWidth;
    const h = window.innerHeight;
    return direction === 'left'
      ? clientX <= size
      : direction === 'right'
        ? clientX >= w - size
        : direction === 'top'
          ? clientY <= size
          : direction === 'bottom'
            ? clientY >= h - size
            : false;
  };

  // 每帧最多处理一次鼠标移动，避免高频事件反复更新响应式状态。
  const handleMouseMove = (e: MouseEvent) => {
    if (!enabled.value || isThrottling)
      return;

    requestAnimationFrame(() => {
      const current = checkInArea(e);
      if (current !== isInSafeArea.value) {
        isInSafeArea.value = current;
        onChange?.(current, e);
      }
      isThrottling = false;
    });
    isThrottling = true;
  };

  // 停用时移除监听并清空悬停状态，避免侧栏维持展开。
  const toggleListener = (enable: boolean) => {
    if (enable) {
      window.addEventListener('mousemove', handleMouseMove);
    }
    else {
      window.removeEventListener('mousemove', handleMouseMove);
      isInSafeArea.value = false; // 关闭时重置状态
    }
  };

  // 立即应用初始 enabled 值，后续开关变化时同步监听器。
  watch(
    enabled,
    (newVal) => {
      toggleListener(newVal);
    },
    { immediate: true },
  );

  // 组件卸载时强制解绑
  onUnmounted(() => {
    toggleListener(false);
  });

  return { isInSafeArea };
}
