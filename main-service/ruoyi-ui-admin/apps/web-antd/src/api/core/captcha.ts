import { requestClient } from '#/api/request';

/**
 * @param img 图片验证码 需要和base64拼接
 * @param captchaEnabled 是否开启
 * @param uuid 验证码ID
 */
export interface CaptchaResponse {
  captchaEnabled: boolean;
  img: string;
  uuid: string;
}

/**
 * 图片验证码
 * @returns resp
 */
export function captchaImage() {
  return requestClient.get<CaptchaResponse>('/auth/code');
}
