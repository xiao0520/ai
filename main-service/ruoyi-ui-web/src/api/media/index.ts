import type {
  ImageGenerationDTO,
  MediaGenerationResponse,
  SpeechGenerationDTO,
  VideoGenerationDTO,
} from './types';
import { get, post } from '@/utils/request';

export function generateImage(data: ImageGenerationDTO) {
  return post<MediaGenerationResponse>('/media/image', data).json();
}

export function generateVideo(data: VideoGenerationDTO) {
  return post<MediaGenerationResponse>('/media/video', data).json();
}

export function generateSpeech(data: SpeechGenerationDTO) {
  return post<MediaGenerationResponse>('/media/speech', data).json();
}

export function getPrediction(params: { model: string; predictionId: string }) {
  return get<MediaGenerationResponse>('/media/prediction', params).json();
}
