/** 当前登录用户可使用的聊天模型。后端已按角色授权和有效期过滤。 */
export interface MyModelOptionVO {
  id?: number;
  modelName?: string;
  modelDescribe?: string;
  remark?: string;
}
