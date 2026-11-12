import type { RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    name: 'KnowledgeLibraryDetail',
    path: '/knowledge/library/detail/:id',
    component: () => import('#/views/knowledge/library/detail.vue'),
    meta: {
      activePath: '/knowledge/library',
      hideInMenu: true,
      title: '知识库详情',
    },
  },
];

export default routes;
