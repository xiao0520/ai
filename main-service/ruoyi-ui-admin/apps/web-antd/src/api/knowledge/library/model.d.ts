export interface KnowledgeRetrievalConfig {
  denseEnabled: boolean;
  sparseEnabled: boolean;
  recallCount: number;
  rrfK: number;
  rerankEnabled: boolean;
  rerankModelId?: null | number | string;
  rerankInputCount: number;
  rerankOutputCount: number;
  relevanceThreshold?: null | number;
}

export interface KnowledgeLibrary {
  id: number | string;
  libraryCode?: string;
  userId?: number | string;
  name: string;
  accessScope?: 'ACL_ONLY' | 'ALL_INTERNAL' | string;
  description?: string;
  splitterConfig?: string;
  currentIndexVersion?: string;
  permissionRevision?: number;
  status?: '0' | '1' | string;
  documentCount?: number;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

export interface KnowledgeDocument {
  id: number | string;
  knowledgeId: number | string;
  documentCode?: string;
  name: string;
  sourceType?: string;
  status?: string;
  currentVersionNo?: number;
  currentVersionId?: number | string;
  createTime?: string;
  updateTime?: string;
}

export interface KnowledgeDocumentVersion {
  id: number | string;
  documentId: number | string;
  knowledgeId: number | string;
  versionNo: number;
  ossId?: number | string;
  originalName?: string;
  fileSuffix?: string;
  mimeType?: string;
  fileSize?: number;
  parseStatus?: string;
  targetIndexVersion?: string;
  configurationRevision?: number;
  processingMode?: 'MULTIMODAL' | 'TEXT' | string;
  textEmbeddingProfileId?: number | string;
  textEmbeddingProfileRevision?: number;
  multimodalEmbeddingProfileId?: number | string;
  multimodalEmbeddingProfileRevision?: number;
  visionProfileId?: number | string;
  visionProfileRevision?: number;
  rerankMode?: 'NONE' | 'PROFILE' | string;
  textRerankProfileId?: number | string;
  textRerankProfileRevision?: number;
  multimodalRerankProfileId?: number | string;
  multimodalRerankProfileRevision?: number;
  parseErrorCode?: string;
  parseErrorMessage?: string;
  parsedAt?: string;
  createTime?: string;
}

export interface KnowledgeAcl {
  id: number | string;
  knowledgeId: number | string;
  subjectType: 'DEPT' | 'ROLE' | string;
  subjectId: string;
  permission: 'MANAGE' | 'READ' | 'WRITE' | string;
  includeChildren?: string;
  status?: string;
  effectiveTime?: string;
  expireTime?: string;
  createTime?: string;
}

export interface KnowledgeParseJob {
  id: number | string;
  requestId?: string;
  jobType?: string;
  knowledgeId: number | string;
  documentId?: number | string;
  documentVersionId?: number | string;
  targetIndexVersion?: string;
  status?: string;
  progress?: number;
  attemptCount?: number;
  maxAttempts?: number;
  errorCode?: string;
  errorMessage?: string;
  startedAt?: string;
  finishedAt?: string;
  createTime?: string;
}

export interface KnowledgeIndexVersion {
  id: number | string;
  knowledgeId: number | string;
  indexVersion: string;
  embeddingProfileId?: number | string;
  embeddingProfileRevision?: number;
  multimodalEmbeddingProfileId?: number | string;
  multimodalEmbeddingProfileRevision?: number;
  splitterConfig?: string;
  status?: string;
  documentCount?: number;
  fragmentCount?: number;
  activatedAt?: string;
  createTime?: string;
}

export interface KnowledgeDocumentProcessingForm {
  targetIndexVersion: string;
  processingMode: 'MULTIMODAL' | 'TEXT';
  textEmbeddingProfileId: number | string;
  multimodalEmbeddingProfileId?: number | string;
  visionProfileId?: number | string;
  rerankMode: 'NONE' | 'PROFILE';
  textRerankProfileId?: number | string;
  multimodalRerankProfileId?: number | string;
}
