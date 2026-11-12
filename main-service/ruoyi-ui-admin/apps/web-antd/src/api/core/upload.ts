import type { AxiosRequestConfig } from '@vben/request';

import { requestClient } from '#/api/request';

/**
 * Axios上传进度事件
 */
export type AxiosProgressEvent = AxiosRequestConfig['onUploadProgress'];

/**
 * 默认上传结果
 */
export interface UploadResult {
  url: string;
  fileName: string;
  ossId: string;
}

/**
 * 通过单文件上传接口
 * @param file 上传的文件
 * @param options 一些配置项
 * @param options.onUploadProgress 上传进度事件
 * @param options.signal 上传取消信号
 * @param options.otherData 其他请求参数 后端拓展可能会用到
 * @returns 上传结果
 */
export function uploadApi(
  file: Blob | File,
  options?: {
    onUploadProgress?: AxiosProgressEvent;
    otherData?: Record<string, any>;
    signal?: AbortSignal;
  },
) {
  const { onUploadProgress, signal, otherData = {} } = options ?? {};
  return requestClient.upload<UploadResult>(
    '/resource/oss/upload',
    { file, ...otherData },
    { onUploadProgress, signal, timeout: 60_000 },
  );
}

/**
 * 上传模型厂商图标
 */
export function providerIconUploadApi(
  file: Blob | File,
  options?: {
    onUploadProgress?: AxiosProgressEvent;
    otherData?: Record<string, any>;
    signal?: AbortSignal;
  },
) {
  const { onUploadProgress, signal, otherData = {} } = options ?? {};
  return requestClient.upload<UploadResult>(
    '/resource/oss/upload/provider-icon',
    { file, ...otherData },
    { onUploadProgress, signal, timeout: 60_000 },
  );
}

/**
 * 上传 Skill 制品；后端根据 ai.oss.skill-artifact.config-key 选择 OSS 配置。
 */
export function skillArtifactUploadApi(
  file: Blob | File,
  options?: {
    onUploadProgress?: AxiosProgressEvent;
    otherData?: Record<string, any>;
    signal?: AbortSignal;
  },
) {
  const { onUploadProgress, signal, otherData = {} } = options ?? {};
  return requestClient.upload<UploadResult>(
    '/agent/skill/upload-artifact',
    { file, ...otherData },
    { onUploadProgress, signal, timeout: 60_000 },
  );
}

/**
 * 上传api type
 */
export type UploadApi = typeof uploadApi;
