import type { MyModelOptionVO } from './types';
import { get } from '@/utils/request';

/** 获取当前用户角色被授予且仍可用的聊天模型。 */
export function getMyAuthorizedModels() {
  return get<MyModelOptionVO[]>('/model/my-options').json();
}
