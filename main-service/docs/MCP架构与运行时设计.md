# MCP 架构与运行时设计

> 现行实现核对：2026-09-30。本文是 MCP 的唯一设计说明；实施与验证记录见 [MCP 接入与验收](MCP接入与验收.md)。管理端、Java 授权与 Python 执行均已接入普通聊天和 RAG。统一适配服务已完成模拟 ERP 验证；真实 ERP 与付费模型的人工联调仍需在实际环境验收。

## 目标与边界

Java 负责 MCP Server、工具目录、角色授权、Agent 绑定、模型与会话、预算和审计；Python 负责按单次请求连接 MCP Server、向 LangChain 暴露授权工具并执行调用。浏览器只访问 Java。知识库异步任务仍走 Kafka；MCP 聊天工具不走 Kafka。现阶段不建立第二套凭证库、工具权限表、聊天通道或独立发布版本。

`ai-service` 是 **MCP Client**；新增 `integration-mcp-service` 是独立 MCP Server，目前只有模拟 ERP 只读工具。真实业务接口、用户委托 OAuth、写操作审批、STDIO 运行沙箱等均未实现，不应写成可用功能。

## 当前调用链

```text
管理员配置 Server/导入工具 → 角色授权 + 普通聊天白名单或 Agent 绑定
用户聊天 → Java 校验用户、Agent、模型与预算 → 计算本次只读工具清单
→ 已签名的普通聊天或 RAG 请求 → Python 按凭证别名连接 Streamable HTTP
→ tools/list 与授权快照核对 → LangChain Agent 按需调用 tools/call
→ 最终回答/累计用量/失败码 → Java 结算与保存会话
```

普通聊天使用 `/internal/ai/v1/chat/stream`；RAG 使用 `/internal/ai/v1/chat/rag/stream`。工具清单为空时沿用无工具生成分支，不建立 MCP 连接。权限在**每次聊天开始时**重新计算；本次执行使用请求快照，角色或 Agent 变更从下一次请求生效。没有“Python 定时拉取 Java 全量配置”的运行路径，也没有逐次调用 Java 重新鉴权接口。

## 管理数据与权限

现行表是 `mcp_server_info`、`mcp_tool_info`、`mcp_role_tool_permission` 和 `agent_mcp_tool`；`mcp_tool_info.general_chat_enabled` 默认关闭，存量库需执行 [普通聊天工具迁移](script/sql/mcp-general-chat-tools-v1.sql)；以 [ruoyi-ai.sql](script/sql/ruoyi-ai.sql)及实体/Mapper 为准。旧市场表、`agent_info.mcp_tool_ids` JSON 和规划中的 `mcp_call_log` 不能作为当前数据来源。

现行管理接口由 `McpServerController`、`McpToolController`、`McpRoleToolController` 提供：Server CRUD/启停、Manifest 导入、工具目录/策略/启停、角色授权。Manifest 导入是管理员提供工具定义，**不等于** Java 主动连接远端并自动发现。Python 执行时再调用实时 `tools/list` 复核工具。

Java 的 `McpChatToolGrantServiceImpl` 只在模型声明 `TOOL` 能力时生成工具清单。未选 Agent 时，候选是用户有效角色授权与工具「允许普通聊天」白名单的交集；选了 Agent 时，仍取角色授权与 Agent 绑定的交集，普通聊天开关不限制 Agent。两种路径都要求 Server 启用、工具启用且发现状态为 `ACTIVE`、风险 `LOW`、无需确认、管理员导入的注解声明 `readOnlyHint=true` 且非破坏性。每次最多下发 20 个工具；无授权或不满足条件时下发空清单。超级管理员的角色授权范围依现有规则包含所有可执行工具，但无 Agent 时仍需普通聊天白名单。只读标记是服务端声明和管理审核依据，系统无法凭注解证明实际没有副作用。

FIXED/AUTO 模型选择仍由原聊天链处理；不能通过工具授权扩大模型权限。RAG 的知识库 ACL、索引成员和候选复核同样不因 MCP 放宽。

## Java → Python 契约与凭证

普通聊天 `PythonChatStreamRequest` 与 RAG `RagChatStreamRequest` 的可选 `tools` 字段使用同一工具授权结构。条目包含 Server 身份、无查询串的 Endpoint、认证类型、凭证别名、连接超时、工具身份、名称、输入 Schema 和调用超时。缺省为空兼容旧请求。RAG 机器契约位于 [contracts/rag-v1](contracts/rag-v1/RAG机器契约说明.md)。

现行可执行传输只有 `STREAMABLE_HTTP`，认证只有 `NONE` 或 `BEARER`。管理端可保存其他类型不代表聊天运行时支持；不支持的配置不会进入授权清单。Bearer 的 `credentialKey` 只存别名；实际密钥从 Python 的 `AI_PROVIDER_CREDENTIALS` 解析。Java、前端、工具描述和 SSE 不接收明文密钥。Server 地址不允许内嵌用户信息、查询串或 Fragment。

## Python 工具执行

`ai-service/src/ai_service/mcp/authorized_tools.py` 使用已锁定的 LangChain `MCPAdapter`、FastMCP Client 和 Streamable HTTP。连接后核对工具名、输入 Schema、`readOnlyHint` 与破坏性标记，只暴露本次 Java 授权的工具。调用前以 JSON Schema 验证参数；每个请求最多调用 3 次，参数和文本结果各限制 16 KiB，执行连接和单次调用超时。上游异常、URL 和认证头不进入浏览器错误消息；工具返回按不可信内容处理。

`ai-service/src/ai_service/agents/mcp_chat.py` 用 LangChain `create_agent` 完成工具循环。普通聊天和 RAG 共用这条执行能力；只将最终答案送入正文，不输出中间工具消息。模型各轮用量累计并受 Java 下发的总预算约束。Java 工具分支最多预占四轮模型额度；成功按实际用量结算，工具失败时对已发生的模型用量进行失败结算。

RAG 先完成既有授权检索和候选选择，再在生成阶段使用工具。无检索证据时允许工具辅助回答，状态为 `NO_EVIDENCE`，不伪造知识库引用。即使检索出了候选，只要 MCP 工具确实成功执行且最终回答没有使用知识库引用，也按 `NO_EVIDENCE` 完成，引用列表为空。回答带引用时只接受上下文扩展后第二次 Java 复核的允许集合，Python 校验最终引用为其子集并复用该票据；未知引用一律拒绝，不再执行第三次完成复核。工具结果中的引用文字不获得知识库引用资格。

## 公司内部业务接口接入

选定一个独立的 `integration-mcp-service`，在同一个 Streamable HTTP MCP Server 中逐项封装 ERP 等多个系统已审核的 HTTP 接口。每个工具对应固定业务动作、固定下游地址及参数/响应映射；共享 HTTP 超时、认证和错误处理，但不提供由模型指定 URL、HTTP 方法或请求头的万能工具。其他系统若已有符合当前运行约束的 MCP Server，也可直接登记，无需强制经过适配服务。

调用链是：用户提问 → Java 沿用现有角色、Agent 和普通聊天白名单生成工具授权快照 → `ai-service` 通过现有 LangChain MCP Client 连接适配服务 → 适配服务校验参数、调用固定业务 HTTP 接口并裁剪返回字段 → Agent 组织答案。管理端 Manifest 导入只登记工具定义；适配服务必须真实实现同名工具，且运行时 `tools/list` 的输入 Schema 和只读注解必须与导入记录一致。

### 首版实施边界

2026-09-30 尚无 ERP 的 OpenAPI、认证及字段说明。首版仅实现独立服务和**模拟 ERP 订单状态查询**，用固定的本地 HTTP 模拟接口验证 MCP `tools/list`、`tools/call` 及现有客户端兼容性。示例接口和字段不是 ERP 真实契约，不作为生产数据工具导入。拿到真实 ERP 接口文档后，按同一模式新增明确命名的只读工具，再做真实环境联调；库存、采购、审批等接口尚未实现。当前聊天运行时只允许低风险、无需确认且 `readOnlyHint=true` 的工具，写操作与用户委托授权均需单独设计。

适配服务的入站 MCP Bearer 令牌与其调用下游系统所用凭证分别配置；前者复用现有 Python `AI_PROVIDER_CREDENTIALS` 别名连接机制，后者只存在适配服务的部署配置中。不能把下游密钥放进 Manifest、模型提示词或浏览器。现有 Java→Python 授权快照不携带可供 ERP 验证的用户委托身份，所以服务账号试点仅可查询**明确允许服务级访问**的数据；按员工或客户隔离的数据必须先补真实用户身份传递和下游数据权限。部署时限制内网访问并保留源系统自身审计。

Agent 只负责从本轮授权工具中选择动作并组织回答；适配服务负责固定接口映射、校验、超时、错误映射和最小字段返回；业务系统负责数据真实性及最终数据权限。当前每轮最多下发 20 个工具、实际调用最多 3 次、单次结果最多 16 KiB。新增工具按“接口文档与数据分级 → 参数和输出契约 → 模拟接口测试 → 真实测试环境联调 → Manifest 导入和角色/Agent 授权 → 普通聊天/RAG 验收”推进，不为每个 HTTP 接口新建一个 Server 或 Java 页面。

## 错误与运维边界

| 情况 | 当前处理 |
| --- | --- |
| 工具/角色/Agent/Schema/只读标记失效 | 不下发工具或返回 `TOOL_REJECTED` |
| Bearer 别名缺失 | `TOOL_CREDENTIAL_MISSING` |
| 连接或工具调用超时 | `TOOL_TIMEOUT` |
| 模型预算不足 | `BUDGET_EXCEEDED` |
| 无授权工具 | 原有无工具聊天路径 |

管理员应先确认 Server 地址和认证、导入的 Schema 与只读注解、工具启停、角色授权、普通聊天开关或 Agent 绑定以及模型 `TOOL` 能力，再检查 Python 密钥别名和服务连通性。错误码的配置与验证命令集中维护在 [MCP 接入与验收](MCP接入与验收.md)。

## 后续扩展（尚未实现）

STDIO、API_KEY 自定义请求头、OAuth/用户委托、写工具确认、逐次调用动态撤权、自动发现入库、运行状态上报、专门的 MCP 调用日志表、Server 级限流/熔断及网络出口治理，均需独立设计和验证后再启用。不要把这些旧方案条目写进当前运行手册或验收结论。
