import type {
  CaptchaResponse,
  LoginDTO,
  LoginResponse,
} from './types';
import { get, post } from '@/utils/request';

export const login = (data: LoginDTO) => post<LoginResponse>('/auth/login', data).json();

/** 获取与管理端相同的图片验证码。 */
export function captchaImage() {
  return get<CaptchaResponse>('/auth/code').json();
}
