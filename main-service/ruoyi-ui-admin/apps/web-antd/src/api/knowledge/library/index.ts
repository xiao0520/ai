import type {
  KnowledgeAcl,
  KnowledgeDocument,
  KnowledgeDocumentProcessingForm,
  KnowledgeDocumentVersion,
  KnowledgeIndexVersion,
  KnowledgeLibrary,
  KnowledgeParseJob,
  KnowledgeRetrievalConfig,
} from './model';

import type { PageQuery, PageResult } from '#/api/common';

import { requestClient } from '#/api/request';

const libraryRoot = '/knowledge/libraries';

export function knowledgeLibraryList(params?: PageQuery) {
  return requestClient.get<PageResult<KnowledgeLibrary>>(libraryRoot, {
    params,
  });
}

export function knowledgeLibraryInfo(id: number | string) {
  return requestClient.get<KnowledgeLibrary>(`${libraryRoot}/${id}`);
}

export function knowledgeRetrievalConfig(id: number | string) {
  return requestClient.get<KnowledgeRetrievalConfig>(
    `${libraryRoot}/${id}/retrieval-config`,
  );
}

export function knowledgeRetrievalConfigUpdate(
  id: number | string,
  data: KnowledgeRetrievalConfig,
) {
  return requestClient.putWithMsg<void>(
    `${libraryRoot}/${id}/retrieval-config`,
    data,
  );
}

export function knowledgeLibraryAdd(data: Partial<KnowledgeLibrary>) {
  return requestClient.postWithMsg<void>(libraryRoot, data);
}

export function knowledgeLibraryUpdate(data: Partial<KnowledgeLibrary>) {
  return requestClient.putWithMsg<void>(`${libraryRoot}/${data.id}`, data);
}

export function knowledgeLibraryChangeStatus(
  id: number | string,
  status: string,
) {
  return requestClient.putWithMsg<void>(
    `${libraryRoot}/${id}/status?status=${status}`,
  );
}

export function knowledgeLibraryRemove(ids: Array<number | string>) {
  return requestClient.deleteWithMsg<void>(`${libraryRoot}/${ids.join(',')}`);
}

export function knowledgeDocumentList(
  knowledgeId: number | string,
  params?: PageQuery,
) {
  return requestClient.get<PageResult<KnowledgeDocument>>(
    `${libraryRoot}/${knowledgeId}/documents`,
    { params },
  );
}

export function knowledgeDocumentUpload(
  knowledgeId: number | string,
  data: FormData,
) {
  return requestClient.postWithMsg<KnowledgeDocument>(
    `${libraryRoot}/${knowledgeId}/documents/upload`,
    data,
  );
}

export function knowledgeDocumentVersions(documentId: number | string) {
  return requestClient.get<KnowledgeDocumentVersion[]>(
    `/knowledge/documents/${documentId}/versions`,
  );
}

export function knowledgeDocumentUploadVersion(
  documentId: number | string,
  data: FormData,
) {
  return requestClient.postWithMsg<KnowledgeDocumentVersion>(
    `/knowledge/documents/${documentId}/versions`,
    data,
  );
}

export function knowledgeDocumentReparse(
  documentId: number | string,
  data: KnowledgeDocumentProcessingForm,
) {
  return requestClient.postWithMsg<KnowledgeDocumentVersion>(
    `/knowledge/documents/${documentId}/reparse`,
    data,
  );
}

export function knowledgeDocumentChangeStatus(
  documentId: number | string,
  status: string,
) {
  return requestClient.putWithMsg<void>(
    `/knowledge/documents/${documentId}/status?status=${status}`,
  );
}

export function knowledgeDocumentRemove(documentId: number | string) {
  return requestClient.deleteWithMsg<void>(
    `/knowledge/documents/${documentId}`,
  );
}

export function knowledgeAclList(knowledgeId: number | string) {
  return requestClient.get<KnowledgeAcl[]>('/knowledge/acls', {
    params: { knowledgeId },
  });
}

export function knowledgeAclAdd(data: Partial<KnowledgeAcl>) {
  return requestClient.postWithMsg<void>('/knowledge/acls', data);
}

export function knowledgeAclRemove(id: number | string) {
  return requestClient.deleteWithMsg<void>(`/knowledge/acls/${id}`);
}

export function knowledgeJobList(
  knowledgeId: number | string,
  params?: PageQuery & { jobType?: string },
) {
  return requestClient.get<PageResult<KnowledgeParseJob>>('/knowledge/jobs', {
    params: { knowledgeId, ...params },
  });
}

export function knowledgeJobRetry(id: number | string) {
  return requestClient.postWithMsg<void>(`/knowledge/jobs/${id}/retry`);
}

export function knowledgeJobCancel(id: number | string) {
  return requestClient.postWithMsg<void>(`/knowledge/jobs/${id}/cancel`);
}

export function knowledgeJobRemove(id: number | string) {
  return requestClient.deleteWithMsg<void>(`/knowledge/jobs/${id}`);
}

export function knowledgeIndexVersionList(knowledgeId: number | string) {
  return requestClient.get<KnowledgeIndexVersion[]>(
    '/knowledge/index-versions',
    {
      params: { knowledgeId },
    },
  );
}

export function knowledgeIndexVersionAdd(data: Partial<KnowledgeIndexVersion>) {
  return requestClient.postWithMsg<void>('/knowledge/index-versions', data);
}

export function knowledgeIndexVersionRebuild(id: number | string) {
  return requestClient.postWithMsg<void>(
    `/knowledge/index-versions/${id}/rebuild`,
  );
}

export function knowledgeIndexVersionRemove(id: number | string) {
  return requestClient.deleteWithMsg<void>(`/knowledge/index-versions/${id}`);
}

export function knowledgeIndexVersionActivate(id: number | string) {
  return requestClient.postWithMsg<void>(
    `/knowledge/index-versions/${id}/activate`,
  );
}
