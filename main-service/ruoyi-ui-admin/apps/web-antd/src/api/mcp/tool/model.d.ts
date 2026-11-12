export interface McpTool {
  id: number | string;
  serverId: number | string;
  serverCode?: string;
  serverName?: string;
  toolName: string;
  qualifiedName: string;
  displayName?: string;
  description?: string;
  inputSchema: string;
  outputSchema?: string;
  annotationsJson?: string;
  metaJson?: string;
  schemaDigest?: string;
  sourceType: string;
  discoveryStatus: string;
  adminEnabled: string;
  generalChatEnabled: string;
  riskLevel: string;
  requireConfirmation: string;
  timeoutSeconds: number;
  visibleResultFields?: string;
  lastDiscoveredAt?: string;
  createTime?: string;
  updateTime?: string;
}

export interface McpToolOption {
  id: number | string;
  label: string;
  serverCode: string;
  serverName: string;
  toolName: string;
  qualifiedName: string;
  displayName?: string;
  riskLevel: string;
}
