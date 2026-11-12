export type ContractType = 'PURCHASE' | 'SALES' | 'SERVICE' | 'OTHER';
export type PartyPosition = 'PARTY_A' | 'PARTY_B' | 'NEUTRAL';
export type RiskSeverity = 'HIGH' | 'MEDIUM' | 'LOW';
export type ContractOperation = 'REVIEW' | 'EXTRACT';

export interface ContractReviewCreateDto {
  file: File;
  agentId: number | string;
  contractType: ContractType;
  partyPosition: PartyPosition;
  operation: ContractOperation;
}

export interface ContractInformation {
  title?: string;
  partyA?: string;
  partyB?: string;
  otherParties: string[];
  amount?: string;
  currency?: string;
  effectiveDate?: string;
  startDate?: string;
  endDate?: string;
  term?: string;
  paymentTerms?: string;
  deliveryAndAcceptance?: string;
  breachLiability?: string;
  terminationTerms?: string;
  disputeResolution?: string;
}

export interface ContractLocation {
  pageNumber?: number;
  clauseNumber?: string;
  blockIndex: number;
  startOffset: number;
  endOffset: number;
}

export interface KnowledgeEvidence {
  evidenceId: string;
  sourceTitle: string;
  quote: string;
}

export interface ContractFinding {
  findingId: string;
  category: string;
  severity: RiskSeverity;
  title: string;
  description: string;
  quote: string;
  location: ContractLocation;
  suggestion: string;
  basis: string;
  evidences: KnowledgeEvidence[];
}

export interface ContractReviewResult {
  schemaVersion: '1.0';
  taskId: string;
  information: ContractInformation;
  findings: ContractFinding[];
  summary: { high: number; medium: number; low: number };
  warnings: string[];
  engineVersion: 'contract-review-v1';
}

export interface ContractReviewDetail {
  id: string;
  fileName: string;
  contractType: ContractType;
  partyPosition: PartyPosition;
  operation: ContractOperation;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  result?: ContractReviewResult;
  errorCode?: string;
  errorMessage?: string;
  createTime: string;
  updateTime: string;
}
