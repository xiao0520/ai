<script lang="ts" setup>
import { computed } from 'vue';

import { useAntdDesignTokens } from '@vben/hooks';
import { preferences, usePreferences } from '@vben/preferences';

import { App, ConfigProvider, theme } from 'ant-design-vue';

import { antdLocale } from '#/locales';

defineOptions({ name: 'App' });

const { isDark } = usePreferences();
const { tokens } = useAntdDesignTokens();

const tokenTheme = computed(() => {
  const algorithm = isDark.value
    ? [theme.darkAlgorithm]
    : [theme.defaultAlgorithm];

  // antd 紧凑模式算法
  if (preferences.app.compact) {
    algorithm.push(theme.compactAlgorithm);
  }

  return {
    algorithm,
    token: tokens,
  };
});

</script>

<template>
  <ConfigProvider :locale="antdLocale" :theme="tokenTheme">
    <App>
      <RouterView />
    </App>
  </ConfigProvider>
</template>

<style lang="scss">
body {
  /**
   * 全局启用 抗锯齿字体
   * @see https://developer.mozilla.org/zh-CN/docs/Web/CSS/font-smooth
   */
  @apply antialiased;
}

/* 与公共表格分页保持一致：右对齐、适度留白、清晰的选中态 */
.ant-table-wrapper .ant-table-pagination.ant-pagination {
  min-height: 44px;
  margin: 12px 4px 0;
  align-items: center;
}

.ant-table-wrapper .ant-pagination-total-text {
  color: hsl(var(--muted-foreground));
}

.ant-table-wrapper .ant-pagination-item,
.ant-table-wrapper .ant-pagination-prev .ant-pagination-item-link,
.ant-table-wrapper .ant-pagination-next .ant-pagination-item-link {
  border-radius: 4px;
}
</style>
