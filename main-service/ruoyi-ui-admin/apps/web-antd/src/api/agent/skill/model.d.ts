export interface SkillVO {
  id: number;
  /** 稳定唯一编码，如 document-summary */
  skillName: string;
  /** 页面展示名称 */
  displayName?: string;
  /** 能力说明、适用场景和边界 */
  description?: string;
  /** 运行时类型：PYTHON / HTTP / MCP（第一阶段只保存） */
  runtime: string;
  /** Python 模块名、HTTP 路径或后续执行标识 */
  entrypoint: string;
  /** Skill 制品 OSS 文件 ID */
  artifactOssId?: string | number;
  /** 制品摘要，用于后续完整性校验 */
  digest?: string;
  /** JSON Schema，约束输入参数 */
  parameterSchema?: string;
  /** JSON Schema，约束输出结构 */
  outputSchema?: string;
  /** JSON：超时、最大文件、最大并发等 */
  resourceLimits?: string;
  /** Skill 内可使用的 MCP 工具白名单（第一阶段仅保存） */
  allowedToolIds?: number[];
  /** 状态：0 启用、1 停用 */
  status: string;
  /** 被 Agent 绑定次数 */
  agentCount?: number;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

/**
 * Skill 绑定的 Agent 信息（删除前展示当前绑定情况）
 */
export interface SkillBoundAgent {
  agentId: number;
  agentCode?: string;
  agentName?: string;
  /** 该 Agent 下的绑定状态：0 启用、1 停用 */
  status?: string;
  /** 仅覆盖该 Agent 下的默认参数 */
  configJson?: string;
}

export interface SkillOption {
  /** 绑定时写入 agent_skill.skill_name 的稳定编码 */
  name: string;
  description?: string;
}
