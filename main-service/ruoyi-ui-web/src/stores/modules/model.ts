import type { ChatModelSelectionMode } from '@/api/chat/types';
import type { MyModelOptionVO } from '@/api/model/types';
import { defineStore } from 'pinia';
import { getMyAuthorizedModels } from '@/api';
import { useUserStore } from './user';

// 模型管理
export const useModelStore = defineStore('model', () => {
  // 默认使用智能选择；选中具体模型后切换为 FIXED。
  const selectionMode = ref<ChatModelSelectionMode>('AUTO');
  const currentModelInfo = ref<MyModelOptionVO>();

  const selectAuto = () => {
    selectionMode.value = 'AUTO';
    currentModelInfo.value = undefined;
  };

  const setCurrentModelInfo = (modelInfo?: MyModelOptionVO) => {
    currentModelInfo.value = modelInfo;
    selectionMode.value = modelInfo ? 'FIXED' : 'AUTO';
  };

  // 当前登录用户按角色授权可选的模型列表。
  const modelList = ref<MyModelOptionVO[]>([]);

  /** 刷新授权模型；FIXED 选择失效时回到 AUTO，禁止沿用未授权模型。 */
  const requestModelList = async () => {
    const userStore = useUserStore();
    if (!userStore.token) {
      modelList.value = [];
      selectAuto();
      return;
    }

    try {
      const res = await getMyAuthorizedModels();
      const authorizedModels: MyModelOptionVO[] = res.data ?? [];
      modelList.value = authorizedModels;

      const selectedId = currentModelInfo.value?.id;
      if (selectionMode.value === 'FIXED'
        && (!selectedId || !authorizedModels.some(item => item.id === selectedId))) {
        selectAuto();
      }
    }
    catch (error) {
      modelList.value = [];
      selectAuto();
      console.error('requestModelList错误', error);
    }
  };

  return {
    selectionMode,
    currentModelInfo,
    selectAuto,
    setCurrentModelInfo,
    modelList,
    requestModelList,
  };
});
