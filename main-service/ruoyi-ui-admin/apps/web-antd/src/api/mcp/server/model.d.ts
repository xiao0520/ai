export interface McpServer {
  id: number | string;
  serverCode: string;
  serverName: string;
  serverType: string;
  transportType: string;
  endpoint?: string;
  runtimeKey?: string;
  protocolVersion: string;
  authType: string;
  credentialKey?: string;
  connectTimeout: number;
  defaultRiskLevel: string;
  status: string;
  runtimeStatus?: string;
  runtimeMessage?: string;
  lastDiscoveredAt?: string;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

export interface McpToolImportCommand {
  manifestJson: string;
}
