import type { ContractReviewCreateDto, ContractReviewDetail } from './types';
import { get, post } from '@/utils/request';

export function createContractReview(data: ContractReviewCreateDto) {
  const form = new FormData();
  form.append('file', data.file);
  form.append('agentId', String(data.agentId));
  form.append('contractType', data.contractType);
  form.append('partyPosition', data.partyPosition);
  form.append('operation', data.operation);
  return post('/contract/reviews', form);
}

export async function getContractReview(taskId: string): Promise<ContractReviewDetail> {
  const response = await get<ContractReviewDetail>(`/contract/reviews/${taskId}`).json();
  return response.data;
}
