# Java + Python 混合架构

> 现行实现核对：2026-09-28。本文描述已落地的系统边界与运行路径；具体实现细节见 [RAG 开发设计](RAG开发设计.md)、[MCP 架构](MCP架构与运行时设计.md)、[AI 执行服务开发约束](Python执行服务开发指南.md)。部署和真实环境验收状态见 [部署运维速查](部署运维速查.md)与[项目开发进度](项目开发进度.md)。

## 系统边界

Java 是业务控制面，负责用户/角色/部门权限、Agent 和模型配置、知识库 ACL、会话消息、Token 预算与结算、索引版本和受控资源接口。Python 是 AI 执行面，负责模型调用、流式生成、文档解析、多模态/Embedding、Qdrant 检索与入库、Rerank 和授权 MCP 工具调用。浏览器只访问 Java；Python 不读取业务 MySQL，也不持有用户登录态或 OSS 全局密钥。

当前不保留 Java 旧模型执行链或自动回退。知识库后台任务使用 Kafka；普通聊天、RAG 对话和模型路由使用带内部签名的 HTTP/SSE。Skill 管理数据在 Java，Python 通用 Skill 执行尚未实现。通用 LangGraph checkpoint/人工审批 Agent 与 Java 业务 Tool Gateway 仍属后续设计。

## 同步聊天链路

```text
浏览器 POST /chat/send
  → Java 校验会话、角色、Agent、模型、知识范围和预算
  → FIXED 直接选已授权模型；AUTO 在 Java 候选中由 Python 确定性路由并由 Java 复核
  → Java 冻结模型 Profile、历史上下文、可选 RAG scope 和可选 MCP 工具清单
  → 已签名的 Python /internal/ai/v1/chat/stream 或 /chat/rag/stream
  → Python 模型生成或授权 RAG 检索/引用/工具循环
  → SSE delta、usage、citation、completed/failed
  → Java 结算、持久化消息/引用、转发终态
```

Java 的 `HttpPythonAiExecutionClient` 是跨服务调用适配层。Python 的 FastAPI 接口只接收签名的强类型请求。Python 不自行扩大 Java 候选模型、知识范围或工具清单。无 MCP 授权工具时使用原生成分支；有工具时只允许低风险只读工具。中间工具消息不作为最终回答流出。工具分支的多轮模型用量由 Python 累计、Java 按实际结算。

RAG 只在聊天请求明确选择知识库时由 Java 创建检索范围快照；普通聊天默认使用模型和已授权 MCP 工具。Python 执行 dense_text、可选 dense_vision 和可选 BM25 sparse 召回、RRF、Java 候选复核、可选 Rerank、上下文选择和流式回答。上下文扩展后的候选会再次由 Java 复核，最终引用只能是该允许集合的子集并复用其短期票据；回访时重新校验会话与 ACL。无检索证据时不伪造引用。知识库检索/引用与 MCP 工具结果是不同来源，不允许工具文本冒充知识库证据。

## 异步知识库链路

```text
Java 上传/替换/重建 → 数据库事务 + Outbox → Kafka command
→ Python Consumer → Java 单用途票据读取源文件
→ 解析/视觉理解/切片/Embedding/BM25 → Qdrant generation 写入与回读
→ 上传不可变清单 → Kafka result ARTIFACTS_READY
→ Java 验收、发布索引成员/版本、结算用量
→ 旧 generation 按保留期清理，对账任务补偿
```

Kafka 承载知识库命令、结果、取消和 DLQ，不承载聊天 Token 流或 MCP 调用。Java 管任务状态、租约、幂等、索引发布和 ACL；Python 管实际解析与向量执行。失败、重试、取消、清理和对账必须绑定精确 job、attempt 与 generation。详情以 [RAG v1 机器契约](contracts/rag-v1/RAG机器契约说明.md)和 [RAG 开发设计](RAG开发设计.md)为准。

当前支持 TXT、Markdown、PDF、DOCX、CSV、XLSX、PPTX；旧版 DOC/XLS/PPT 不支持。多模态模式仅对解析得到的图片调用视觉与图片 Embedding，相同内容按哈希去重。Qdrant collection 使用 `kb_<knowledgeId>_<indexVersionName>`，文本命名向量 `dense_text`、可选视觉 `dense_vision` 和 `sparse`。模型、切片和索引业务参数由 Java 管理端/快照下发，不在 Python 环境文件重复配置。

## 权限、密钥和契约

用户及资源权限只由 Java 裁定。Java→Python 的内部 HTTP 使用独立 Service Token、时间戳、Nonce 与 HMAC；Python→Java 受控接口使用同一签名规则并配合单用途票据。Kafka 使用 Broker 身份认证和消息业务幂等，不使用 HTTP HMAC 签名。实际凭证留在 Python 的受控环境/密钥别名映射，Java 只下发别名；明文密钥不进入数据库普通业务表、契约样例、模型上下文或 SSE。

机器契约统一放在 [contracts/rag-v1](contracts/rag-v1/RAG机器契约说明.md)，由 Python Pydantic 模型生成 JSON Schema、OpenAPI 和 Java DTO。修改跨服务字段后必须运行生成器漂移检查与两端契约测试，不能只更新一侧实现。普通聊天、RAG、MCP 和入库任务的稳定错误码在接口层统一映射，不把上游异常直接展示给用户。

## 数据所有权

| 资源 | 权威写入方 |
| --- | --- |
| 用户、角色、ACL、Agent、模型/知识库/MCP 配置、会话、消息、账单 | Java |
| Kafka 入库任务状态、Outbox、索引版本和成员 | Java |
| Qdrant 向量 generation、解析中间产物 | Python 执行，Java 控制发布/删除意图 |
| OSS 原文件和清单归属 | Java；Python 通过短期单用途票据读写 |
| MCP Server 连接与工具调用 | Python 按 Java 单次请求快照执行 |

## 部署与后续能力

部署需分别配置 Java、Python、MySQL、Redis、Kafka、对象存储、Qdrant 和外部模型 API；具体环境变量、迁移与排障以 [部署运维速查](部署运维速查.md) 为准。代码和离线测试已覆盖主要链路，但真实模型、数据与全部基础设施组合的端到端验收仍需在目标环境执行，不应以单元测试代替。

写操作 Tool Gateway、MCP STDIO/OAuth/审批、Skill 执行、通用持久化图 Agent、跨服务熔断和完整运行审计表尚未实现。新增能力时沿用 Java 控制面和 Python 执行面边界，并先更新对应契约和验收标准。
