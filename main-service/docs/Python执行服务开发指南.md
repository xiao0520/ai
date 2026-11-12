# Python AI 执行服务开发约束与现状

> 2026-09-28 核对。本文合并原执行路线图与 LangChain 开发说明，描述当前代码的开发边界；完成度和环境验收以 [项目开发进度](项目开发进度.md) 为准。旧路线图中“尚未建设聊天/RAG/MCP”“未来才增加 SSE/LangChain”等阶段描述已失效。

## 分工与运行链路

Java 是控制面：用户/角色/Agent 权限、模型及知识库配置、会话消息、Token 预占与结算、资源 ACL 和审计。Python 是执行面：模型选择范围内的路由、模型调用、RAG 检索/生成、多模态处理、知识库 Kafka Worker 和已授权 MCP 工具调用。Python 不直接访问业务 MySQL，不获取用户登录态，不在本地持有 OSS 全局密钥。

普通聊天通过 Java 签名请求调用 Python `/internal/ai/v1/chat/stream`；RAG 聊天调用 `/internal/ai/v1/chat/rag/stream`。Java 固化本次模型/工具/知识范围，Python 返回流式正文、用量和终态；知识库后台任务则通过 Java Outbox/Kafka → Python Consumer → Kafka 结果回报。受控源文件和清单使用单用途票据接口。机器格式以 [RAG v1 契约](contracts/rag-v1/RAG机器契约说明.md) 为准。

## 当前已落地的能力

| 能力 | 当前代码 |
| --- | --- |
| 普通聊天和模型路由 | FastAPI 内部接口、FIXED/AUTO 授权候选路由、LangChain 模型适配、SSE |
| RAG 入库 | TXT、Markdown、PDF、DOCX、CSV、XLSX、PPTX 解析；图片抽取/视觉描述；切片、Embedding、BM25、Qdrant generation、清单回报 |
| RAG 查询与回答 | Java 授权范围、混合召回/RRF、按策略 Rerank、上下文选择、流式生成、双端引用校验 |
| MCP | Java 单次工具授权清单；Python LangChain MCPAdapter + create_agent；普通聊天与 RAG 共用只读工具循环 |
| Skill 执行 | Java 管理端有元数据与 Agent 绑定；Python 通用 Skill 运行时未实现 |

“已落地”表示代码和自动化测试完成，不代表真实模型、实际业务库、Qdrant、OSS、Kafka 和部署环境的所有组合已通过端到端验收。

## LangChain 使用约束

优先使用现有 LangChain 消息、模型、Prompt、Runnable/LCEL、Retriever、Embeddings、Qdrant 和 Agent 抽象；服务边界之外的调用由项目 Provider/Repository 封装。模型非流式调用使用异步 `ainvoke`，流式链使用 `astream` 或现有 Provider 流接口。不得重新建立与现有 Retriever、Embedding、Prompt 或工具循环并行的第二套通用框架。供应商存在非标准协议时按 `providerCode + apiMode` 选择已注册适配器，不按供应商名称猜测兼容性。

当前 MCP 使用 `langchain[mcp]` 提供的 `MCPAdapter` 与 `create_agent`；不要按旧路线图再单独引入一套 `langchain-mcp-adapters` 客户端。LangGraph 已在 `ai-service` 中声明为直接依赖，并通过最小 `StateGraph` 异步执行测试验证 Python 3.14 兼容性；这不表示项目已建设具体业务图、通用持久化 Agent、checkpoint 或人工审批。业务图应在请求、状态和输出契约明确后按需实现。

仅当标准框架无法表达经过验证的协议或安全边界时才局部实现适配，并记录原因、范围、回接主链方式与测试。旧 `langchain.chat_models`、`apredict`、`acall` 等接口不要引入新代码。

## 契约、配置与验证

跨 Java/Python 的字段先更新 Python 契约，再运行 [契约生成器](contracts/rag-v1/RAG机器契约说明.md)同步 JSON Schema、OpenAPI、Java DTO 和共享样例，并运行两端契约测试。模型、向量、视觉、切片和权限业务参数由 Java 管理端/执行快照下发；Python 环境变量保存部署地址、内部密钥与凭证别名映射，不重复录入业务配置。密钥不进入契约样例、业务表或日志。

常规 Python 检查：`pytest -q`、`ruff check src tests`、`mypy src`、`uv lock --check`。涉及契约时再运行 `main-service/docs/contracts/rag-v1/generate.py --check` 和 Java AI 核心模块测试。MCP 的本地 Server 及验收步骤见 [MCP 接入与验收](MCP接入与验收.md)。

后续仍需真实供应商与部署环境联调；Skill 执行、通用图编排和写操作 MCP 不属于现行能力。状态变化应先修改代码/契约并通过验证，再更新 [项目开发进度](项目开发进度.md) 和对应设计文档。
