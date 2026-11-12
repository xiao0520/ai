<script lang="ts" setup>
import { computed } from 'vue';

import { AuthPageLayout } from '@vben/layouts';
import { preferences, updatePreferences } from '@vben/preferences';

import { $t } from '#/locales';

const appName = 'MJN AI';
const currentYear = new Date().getFullYear();
const logo = computed(() => preferences.logo.source);

if (preferences.app.authPageLayout !== 'panel-center') {
  updatePreferences({
    app: {
      authPageLayout: 'panel-center',
    },
  });
}
</script>

<template>
  <AuthPageLayout
    :app-name="appName"
    :logo="logo"
    :page-description="$t('authentication.pageDesc')"
    :page-title="$t('authentication.pageTitle')"
    :toolbar-list="['color', 'language', 'theme']"
  >
    <!-- 自定义工具栏 -->
    <!-- <template #toolbar></template> -->
    <template #copyright> Copyright © {{ currentYear }} MJN </template>
  </AuthPageLayout>
</template>
