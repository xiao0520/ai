# MJN AI RAG 开发设计与实施规范

> 项目统一进度见 [项目开发进度](项目开发进度.md)。

> 2026-09-16 Kafka 实施更新：知识库任务已经采用 Java Outbox/Kafka 投递、Python 消费及 Kafka 回报，原 HTTP 领取/续租/完成方案不再作为实施路线。统一内部签名的双端组件与 Java Redis nonce 防重放已完成，现已用于 Worker 读取源文件、清单上传、候选复核等受控 HTTP 接口，不用于 Kafka 消息认证。完成情况、验证证据和剩余工作统一维护在 [项目开发进度](项目开发进度.md)；部署启用步骤见 [部署运维速查](部署运维速查.md)。


> 版本：2.0（收敛为显式知识库、知识库级检索策略与两次候选复核）
> 现行实现：普通聊天默认不检索知识库；用户明确选择知识库后才创建 RAG scope。检索和重排参数统一保存在 `knowledge_info.retrieval_config`，旧文档级 Rerank 字段只保留历史数据可读性，不再进入运行路由。
> 日期：2026-09-18
> 工作目录：D:/project/mjn-ai  
> 文档状态：机器契约、Kafka 调度、受控源文件与清单接口、文本及图片解析切片、动态 Embeddings、OCR/视觉理解、中文 BM25、Qdrant 多路召回、应用层 RRF、两次 Java 候选复核、知识库级可选 Rerank、上下文与引用闭环均已完成代码和离线测试；真实外部服务组合验收仍待目标环境执行。
> 验收安排：2026-09-17 决定先完成剩余 RAG 功能代码；真实模型、Qdrant、MySQL、OSS、Kafka、目标 Linux、质量和故障的统一验收延后到全部计划功能完成后进行。开发期间继续执行契约、静态检查、单元测试和离线集成测试。
> 适用范围：Java 后端与两个前端位于 main-service，Python 执行服务位于 ai-service。  
> 本文交付范围：开发设计文档，不代表已安装依赖、执行数据库迁移或部署远程服务。

## 1. 目标、范围与设计决定

建设受权限保护、支持版本更新、回答可追溯的企业文档问答系统。主流程确定为：

**显式选择知识库 → 授权范围确定 → 元数据过滤 → Dense 及可选 Sparse 召回 → RRF 融合 → 候选有效性复核 → 可选重排 → 上下文扩展与再次复核 → 回答及引用。** 查询改写不属于 v1 运行链路，后续有评测依据时再独立增加。

### 1.1 关键决定

| 事项 | 本次开发目标 |
|---|---|
| Java/Python 分工 | Java 管权限、业务状态、发布与记账；Python 管解析、索引、检索及模型执行 |
| Python RAG 框架 | 明确采用 LangChain：Document、Embeddings、Retriever、Prompt 与 Runnable/LCEL 编排 |
| 工具 Agent | 当前只读 MCP 使用 LangChain create_agent；通用持久化 LangGraph Agent、审批与恢复仍按需设计 |
| 向量存储 | 采用 Qdrant，使用命名稠密/稀疏向量；部署和 SDK 版本在实施开始时锁定 |
| Embedding 选型 | 模型统一在 Java 模型管理发布；管理员上传、替换或重新解析文档时选择该文档版本的处理模式及兼容的文本/多模态向量模型，任务固化精确修订 |
| 模型来源 | 解析/OCR、Embedding、Rerank、Chat 均来自 Java 模型管理；Embedding、OCR/视觉随文档处理快照固化，Rerank 由知识库查询配置在请求开始时冻结当前发布修订，不写死在 Python 或应用配置中 |
| 后台任务 | Java 任务表与 Outbox + Kafka command/result/control/DLQ；Python Worker 消费命令并回报进度和终态 |
| 消息队列 | Kafka 只承载知识库后台任务；普通聊天、RAG 问答和 Agent 流式事件继续走内部 HTTP/SSE |
| 检索主链路 | 稠密语义检索 + 中文关键词/BM25 稀疏检索 + RRF + Rerank |
| 问题改写 | AUTO：仅在指代、省略或明显表达问题时触发，保留原问题 |
| 问题扩展 | 默认关闭；通过评测后按知识库检索配置开启，限制扩展数量 |
| 无证据行为 | 明确说明依据不足或请求澄清，不生成无依据的公司规定 |
| 发布策略 | 新内容完整构建后才发布；旧版本在切换前持续可检索 |
| 权限模型 | 复用单租户角色/部门/Agent 范围；AI 契约不新增 tenantId |
| 工作流 | 本版 RAG 用 LangChain Runnable/LCEL 组织确定性流程；后续复杂 Agent 执行交给 LangGraph |

### 1.2 LangChain 优先实施约束

Python 的模型调用、文档表示、向量化、检索、重排、Prompt 和 RAG 流程编排默认以 LangChain 1.x 的模块化组件实现。新增能力开始开发前，应先检查当前锁定版本是否已有可复用的 `Document`、`Embeddings`、`BaseRetriever`、`VectorStore`、`ChatModel`、Prompt、Output Parser 或 Runnable/LCEL 接口；能够通过实现接口、继承扩展或 Runnable 适配完成时，不得另建一套平行的链路、组件协议或流程编排。

项目领域模型和 LangChain 对象通过明确的适配层转换。`ParsedDocument`、稳定 chunk ID、来源坐标、Java 授权快照和机器契约继续承担跨服务及审计职责，但进入 AI/RAG 执行阶段后必须尽早适配为 LangChain 组件，并由同一条 Runnable/LCEL 主链参与后续处理。自定义算法可以作为 LangChain 接口实现或 Runnable 节点存在，不能因为需要自定义算法就绕开整个框架。

只有下列情况允许局部不使用 LangChain：

1. Java 控制面职责，包括鉴权、ACL、任务状态、Kafka/Outbox、租约、索引发布、预算结算和审计。
2. LangChain 当前接口无法表达的供应商专有协议或输入模态，例如非标准图片向量接口；该能力仍需通过项目 Provider 或 Runnable 适配回主链。
3. Qdrant collection 创建、generation 写入核验、精确删除、清理和孤儿对账等低层生命周期操作。
4. 为稳定 chunk ID、精确 Token 边界、来源定位和可重建性服务的确定性解析及切片算法；其结果仍需转换为 `Document`，切片器优先实现或包装成 LangChain 可组合组件。
5. 已验证的框架实现无法满足异步取消、流式透传、性能、安全或审计要求，且通过受控适配仍不能解决。

每个例外必须在对应设计或实现说明中记录不适用原因、影响范围、考察过的 LangChain 接口、回接主链的方式和验证用例。代码评审需要检查是否出现重复的 Retriever、Embedding、Prompt、VectorStore 或 Pipeline 抽象；没有上述证据的自研平行实现不得合入。后续 LangChain 版本升级时应复核例外，框架已具备合适能力后优先收敛回标准接口。

Qdrant 支持稠密与稀疏向量的混合查询及 RRF 融合；这里据此确定存储能力，具体 Python 调用参数仍需按锁定版本核验。[官方混合查询文档](https://qdrant.tech/documentation/search/hybrid-queries/)

### 1.3 交付范围

第一版支持 TXT、Markdown、PDF、DOCX、CSV、XLSX、PPTX 的文本、表格与图片入库及问答，采用 FastAPI + LangChain + Qdrant，并由 Java 模型管理选择文本、多模态向量及视觉/OCR 模型。交付完整混合检索、重排、引用和权限闭环。开发中可以先用单路检索验证链路，但单路验证不代表第一版完成。

当前多模态入库已支持从扫描 PDF、DOCX、Markdown、XLSX 和 PPTX 提取图像，经视觉模型生成 OCR/描述文本并写入视觉向量；XLSX 图片保留工作表名和可用的单元格锚点。视觉/OCR 只在 `MULTIMODAL` 模式且解析结果包含图片时执行，正文、表格和普通文本块不会调用视觉模型。同一文档内完全相同的图片按 SHA-256 内容哈希只调用一次视觉和多模态 Embedding，再将结果展开到每个图片来源位置，既保留引用定位，也避免重复页眉、页脚和模板图消耗额度。CSV 作为结构化文本表格解析；XLSX 公式只保留文本，不执行公式、宏或外链，Excel 图表暂不渲染成图片。后续增强包括复杂版面与表格专项识别、图表语义提取、问题扩展和真实业务接口路由；合成样例通过不代表复杂业务资料质量已经验收。

实时订单、余额、库存和聚合统计由 Java 受控业务接口提供；文档 RAG 不承担实时数据库查询。普通聊天继续使用既有链路；用户显式选择知识库问答时，检索失败不得静默变成普通聊天。

### 1.4 与旧文档的关系

- 本文细化 [知识库 V2 设计](知识库架构与管理接口设计.md)与[Java/Python 混合架构](Java与Python混合架构.md)中的 RAG 部分。
- 混合架构与知识库文档中的旧 HTTP 领取/续租/完成流程已被 Kafka 任务通道替代；相关 DTO、OpenAPI 路径、生成类型和测试样例已经删除。
- 资源权限统一由 Java 裁定，沿用知识库文档中“user_id 仅用于审计，不作为访问授权依据”的规则；Java 单资源校验及列表范围均取消创建者自动放行。管理、文档读取、RAG 检索与引用访问复用 Java 的资源授权规则，Python/LangChain 不另建授权体系。
- 本文中标为“新增”的路径、字段和配置是开发目标，不是当前可调用能力。
- 本文按现行入库、查询、RAG 与只读 MCP 链路同步；后续目标（Skill、业务 Tool Gateway、审批、通用 Agent 图）不作为当前可调用能力。
- 1.1 版按用户确认的架构，将 LangChain 从隐含的模型适配依赖明确提升为 Python RAG 编排框架。框架对象可存在于 Python RAG/Provider 适配实现中，仍不跨入 Java、FastAPI 请求 DTO 或业务状态机；本轮不修改项目 Skill。

## 2. 已核实的本地代码基线

知识库基线依据 2026-09-14 本地源码核实；2026-09-15 复核 Python 依赖和聊天 Provider。不代表远程部署状态。

| 能力 | 已有基础 | 缺口 |
|---|---|---|
| 知识库与 ACL | knowledge_info、knowledge_acl 及管理服务 | 请求级检索范围、内容状态与权限变更复核 |
| 文档及版本 | knowledge_document、knowledge_document_version、OSS 上传 | 新版本发布闭环、分片目录、索引成员记录 |
| 解析任务 | knowledge_parse_job、执行批次、Outbox、Kafka 状态机、完整任务快照、受控源文件、正式文本/图片入库、产物验收、入库预算、失败批次清理与 REBUILD 父子任务 | 真实环境联调与查询链路 |
| 索引版本 | knowledge_index_version、索引成员、统计、READY 推进、激活事务、旧版归档、成员文档发布及 Qdrant/OSS 对账补偿 | 真实数据库与外部服务验收 |
| Python | FastAPI、Provider、普通聊天、Kafka Consumer、受控下载、文本/图片解析切片、动态 Embeddings、OCR/视觉理解、中文 BM25、Qdrant 文本/视觉入库和清单上传 | 检索和重排 |
| LangChain | ChatOpenAI 已用于普通聊天；SparseEmbeddings 接口用于中文 BM25，langchain-qdrant 依赖已锁定 | RAG Retriever/Runnable、混合检索和引用链 |
| 内部聊天契约 | InternalChatStreamRequest、delta/usage/completed/failed/keepalive | RAG 模式、权限快照、引用、分阶段用量 |
| Java 聊天预算 | 按历史和问题估算与预留 | RAG 上下文及额外模型调用预算 |
| Python 运行版本 | pyproject.toml 要求 >=3.14,<3.15；Windows/Python 3.14 已验证首期解析与 tokenizer 依赖 | 目标 Linux 环境及真实向量依赖兼容性验证 |

主要代码位置：

- [Python 聊天契约](../../ai-service/src/ai_service/contracts/chat.py)
- [Python 聊天执行](../../ai-service/src/ai_service/domain/chat_execution.py)
- [Python 依赖](../../ai-service/pyproject.toml)
- [现有 LangChain 聊天 Provider](../../ai-service/src/ai_service/providers/langchain_openai.py)
- [Java 解析任务](../ruoyi-modules/ruoyi-ai-core/src/main/java/org/ruoyi/service/knowledge/impl/KnowledgeParseJobServiceImpl.java)
- [Java 文档版本](../ruoyi-modules/ruoyi-ai-core/src/main/java/org/ruoyi/service/knowledge/impl/KnowledgeDocumentVersionServiceImpl.java)
- [Java 文档删除](../ruoyi-modules/ruoyi-ai-core/src/main/java/org/ruoyi/service/knowledge/impl/KnowledgeDocumentServiceImpl.java)
- [Java 索引版本](../ruoyi-modules/ruoyi-ai-core/src/main/java/org/ruoyi/service/knowledge/impl/KnowledgeIndexVersionServiceImpl.java)
- [Java ACL](../ruoyi-modules/ruoyi-ai-core/src/main/java/org/ruoyi/service/knowledge/impl/KnowledgeAclServiceImpl.java)
- [Java 聊天执行](../ruoyi-modules/ruoyi-ai-core/src/main/java/org/ruoyi/service/chat/impl/ChatExecutionServiceImpl.java)

## 3. 架构与职责边界

~~~mermaid
flowchart TD
    U[管理端上传文件] --> J[Java：鉴权、OSS、文档版本、任务]
    J --> K[Java Outbox：投递 Kafka command]
    K --> W[Python Consumer：消费任务并回报 Kafka result]
    W --> P[LangChain 入库适配：Document、切片、Embeddings]
    P --> Q[(Qdrant)]
    P --> O[(OSS 解析产物与清单)]
    W --> R[Java：验收结果、发布版本]

    A[用户端提问] --> B[Java：会话、ACL、索引快照、预算]
    B --> API[FastAPI：认证与契约校验]
    API --> C[LangChain：问题理解与 Retriever 检索]
    Q --> C
    C --> V[Java：复核候选有效性]
    V --> D[LangChain：重排 Runnable、Prompt、模型生成]
    D --> E[Java：SSE 转发、引用保存、结算]
    E --> F[用户端回答与原文预览]
~~~

| 组件 | 负责 | 边界 |
|---|---|---|
| Java | 身份、ACL、Agent 候选范围、模型发布、业务状态、任务租约、引用访问与计费 | 不执行 Embedding、向量检索和 AI 解析 |
| FastAPI | 内部认证、参数验证、领域用例调用、SSE 协议转换 | 不在路由直接组链，不向浏览器暴露 Python |
| LangChain RAG | 通过 Document/Embeddings/Retriever/Prompt/Runnable 组织入库与检索生成 | 不接管 ACL、业务表、租约、账户预算或会话持久化 |
| Python Worker | 文件处理、向量写入、构建清单、清理技术产物 | 不直接修改任务、文档和权限业务表 |
| MySQL | 元数据、索引成员、任务、引用和用量 | 不保存向量，不恢复旧 knowledge_fragment 作为并行片段真相源 |
| Qdrant | 向量、检索片段、技术元数据 | 不作为权限与在线版本的权威来源 |
| OSS | 原文件、标准解析内容、片段清单 | Python 仅获任务绑定的短期读写票据 |
| Redis | 内部 nonce、短期快照与技术缓存（按现有部署使用） | 缓存失效不能放宽权限 |

Worker 与 API 共用 ai-service 工程，采用独立进程及并发资源限制。文档解析不得阻塞聊天服务事件循环。LangChain 是本版明确采用的 RAG 框架；既有 Provider 协议作为业务边界继续保留，通过适配接入链路。

### 3.1 LangChain 组件分层

~~~mermaid
flowchart TD
    J[Java：用户、权限、Token、会话、配置] --> F[FastAPI]
    F --> U[Python 用例入口]
    U --> LC[LangChain Runnable / LCEL]
    LC --> CHAT[ChatModel / Provider Runnable]
    CHAT --> SUP[已发布的多模型供应商配置]
    LC --> EMB[DoubaoVisionEmbeddings]
    EMB --> ARK[方舟官方 SDK：doubao-embedding-vision]
    LC --> RET[AuthorizedHybridRetriever]
    RET --> VS[QdrantVectorStore / 受控 Qdrant 适配]
    VS --> Q[Qdrant：Dense + BM25 Sparse]
    RET --> RRF[RRF 融合]
    RRF --> ACL[Java 候选有效性复核]
    ACL --> RR[Rerank Runnable]
    RR --> CTX[Context Builder + ChatPromptTemplate]
    CTX --> CHAT
    LG[当前 MCP 工具 Agent；后续通用 LangGraph] -.复用.-> LC
~~~

这是 Python 内部组件关系，文档版本与权限的权威来源仍是 Java。LangGraph 后续作为 Agent 编排层调用 LangChain 组件，不表示本版 LangChain RAG 必须依赖 LangGraph 的 Agent 循环。

### 3.2 各组件如何落地

| 项目组件 | LangChain 对应能力 | 本项目实现要求 |
|---|---|---|
| 解析产物 | langchain_core.documents.Document | page_content 承载正文，metadata 保留稳定来源；进入框架前后校验类型 |
| 结构切片 | langchain-text-splitters + 自定义结构保护 | 先保留章节/表格，再按 Token 限长；不能让通用 splitter 丢失定位 |
| ProfileBoundEmbeddings | langchain_core.embeddings.Embeddings | 按 Java 下发的 providerCode + apiMode 动态路由已发布模型；文档与查询均走异步接口，豆包和 OpenAI 兼容协议共用边界 |
| ChineseBm25SparseEmbeddings | langchain_qdrant.SparseEmbeddings | 适配固定版本的中文词项编码与 BM25，不默认使用未验证的示例分词 |
| QdrantVectorStore | langchain-qdrant | 统一向量命名、payload 映射、collection 和允许批次过滤 |
| AuthorizedHybridRetriever | BaseRetriever 适配 + 强类型检索用例 | 绑定请求级授权快照；保留各路排名、来源和融合诊断 |
| Rerank Runnable | Runnable 包装现有/新增 Rerank Provider | 只处理已复核候选，记录独立用量，保持来源映射 |
| Prompt | ChatPromptTemplate | 系统规则、历史、问题与证据分开组织，模板有 revision |
| RAG 编排 | RunnableSequence、RunnableParallel 或等价 LCEL 组合 | 强类型输入输出，明确分支、取消、超时和并发上限 |
| 生成 | 现有 ChatOpenAI Provider / 原生 Provider Runnable | 保留既有 Responses 等能力，不把不兼容接口强塞进 ChatOpenAI |
| 输出 | 项目事件适配器 | 将框架内部结果转换为既有 SSE 与新增 citation 契约 |

当前已落地来源校验转换、请求级 Embeddings、视觉向量 Runnable，以及解析、切片、向量和 BM25 的编排；RAG 已通过 LangChainRagPipeline 完成授权检索、上下文选择、流式回答及引用闭环。普通聊天无工具时继续消费 Provider 流；有工具时使用 LangChain Agent 并仅流出最终回答。

`AuthorizedHybridRetriever` 已基于 `BaseRetriever` 实现：逐请求绑定 Java `scopeSnapshotId`，分页读取成员清单，按知识库、索引和 generation 配对过滤，执行文本 Dense、可选视觉 Dense 与可选 BM25 Sparse 查询。`ReciprocalRankFusion` 按稳定来源去重，使用知识库级 `rrfK` 计算各路倒数名次之和，并保留原始分数、原始名次、RRF 分数和 RRF 名次。`AuthorizedRetrievalChain` 在 RRF 后和上下文扩展后各调用一次 Java `validate-candidates`，只把当前仍允许的完整来源元组交给后续阶段；响应若扩权、重复、过期或跨范围立即失败。启用 Rerank 时，`ProfileGroupedReranker` 只使用所属知识库冻结的精确 Profile 修订；调用失败、超时、认证失败或凭证缺失时降级为已授权候选的 RRF 顺序。最终引用只能是第二次允许集合的子集，并复用该复核票据，不再发起第三次 COMPLETION 复核。

LangChain 的 Embeddings 接口区分问题向量与文档列表向量，适合在这里接入自定义豆包适配器。[官方 Embeddings 接口](https://reference.langchain.com/python/langchain-core/embeddings/embeddings/Embeddings)

### 3.3 框架对象与业务契约

Python 领域用例依赖项目 RagPipeline / RetrievalService 协议，实现由 LangChainRagPipeline 提供。FastAPI 只接收项目 Pydantic 请求和输出，不暴露 RunnableConfig、Document、AIMessage 或 SDK 客户端。

RAG 框架实现内部允许使用 LangChain 对象。Document.metadata 是框架要求的映射容器，转换前后必须经 ChunkMetadata、RetrievalCandidate 等具名模型校验，不能把整个稳定跨服务协议变成任意字典。

客户端和无请求状态组件在 composition root/lifespan 创建并注入；带 scopeSnapshot、filter、预算和 Profile 的链/检索器视图按请求绑定。禁止修改共享 Retriever.search_kwargs 或共享 Embeddings.model 来切换用户范围和模型。

### 3.4 ChatModel 与原有 Provider 的衔接

现有 LangChainOpenAiChatProvider 已创建 ChatOpenAI 并调用 astream，优先复用其模型配置、错误映射和客户端生命周期。

RAG 用例先通过检索链得到证据，再经 Prompt 适配生成消息，最后进入现有 ChatStreamProvider。兼容模型仍由 ChatOpenAI 执行；需官方原生能力的 Provider 由自定义可流式 Runnable 适配，不增加第二条并行生成调用。

不能把当前 stream 方法包成普通一次性 RunnableLambda 后假定仍逐 Token 输出；生成适配器必须实现并验证流式透传。不得用 StrOutputParser 过早丢弃 usage、finish 信息、供应商必要元数据或引用校验结果。

### 3.5 后续 LangGraph 扩展

出现多轮工具选择、检索后补查或复杂分支时，引入 LangGraph 调度已有 LangChain ChatModel、Tool 和 RagPipeline。查询类 RAG 作为可复用节点或工具，不重新建设知识库检索体系。

LangGraph checkpoint 仅保存执行恢复状态，不成为 Java 会话、余额、任务、权限的新来源；恢复执行前重新授权。业务工具经 Java Tool Gateway，MCP 按既有架构接入。aiflow、warm-flow 与 LangGraph 不同时成为同一业务对象的主控引擎，后续接入遵守单业务对象单引擎主控边界。

第一版不建立 LangGraph Agent，也不启用独立会话 Memory 接管 Java 的历史消息。框架 tracing/callback 必须显式配置并脱敏，不能默认把文档正文或用户问题发送到外部观测服务。

## 4. 核心数据概念

### 4.1 四种版本必须区分

| 标识 | 含义 | 变化条件 |
|---|---|---|
| documentVersionId | 原始文件的一次上传版本 | 替换文件 |
| indexVersion | 一套完整且兼容的检索索引配置 | 改 Embedding、维度、解析/切片/关键词编码规则 |
| generationId | 一次任务执行产生的独立技术产物集合 | Java 重试时创建新的执行批次 |
| retrievalProfileRevision | 在线检索/重排/上下文策略版本 | 调整 TopK、RRF、重排、改写等在线策略 |

同一 documentVersionId 可以在不同 indexVersion 中具有不同切片。重排模型和 TopK 的改变通常不要求重新向量化；Embedding、切片、稀疏编码和分词规则改变则要求新索引。

### 4.2 文档状态规则

复用当前文档状态集合，明确其面向当前已发布内容的含义：

- 首次上传尚无在线版本：逻辑文档 PENDING，文件版本 parseStatus=PENDING。
- 存在已发布版本时上传新版：逻辑文档仍保持 READY，新版自身 PENDING/PROCESSING。
- 新版失败：只将新版解析状态标为 FAILED，旧版仍可检索。
- DISABLED/DELETED 是管理状态；后台解析完成不得覆盖这些状态。
- 最新上传版本、最新解析成功版本、当前索引引用版本分别展示，不混用。
- 新版成功时通过 Java 条件更新发布；并发上传以单调 publicationRevision 和 expectedPublicationRevision 防止旧任务覆盖更新的发布意图。已有在线版持续服务。

当前 uploadVersion 将逻辑文档改为 PENDING 的行为必须按此规则调整。

### 4.3 索引成员是发布依据

新增 knowledge_index_document，记录“指定索引中，一个逻辑文档采用哪个文件版本和哪一批产物”。唯一约束为 (knowledge_id,index_version,document_id)。

检索时以 ACTIVE 索引及成员映射为准，不能只读取 knowledge_document.current_version_id。后者作为当前在线索引的页面投影，由 Java 在发布事务中维护；索引回滚时一起更新，不能让前端显示新版却实际检索旧版。

新增 knowledge_artifact_generation 保留产物批次清单和生命周期，防止被替换的产物丢失清理及引用追踪依据。该表不保存向量或所有片段正文。

## 5. 文档入库与解析

### 5.1 完整流程

1. Java 校验用户 WRITE 权限、知识库状态、文件大小与真实类型；上传 OSS 后保存文件哈希及文档版本。
2. 首次入库没有 ACTIVE 索引时，先创建明确配置的 BUILDING 版本；任务必须绑定目标版本，NO_ACTIVE_INDEX 仅能作为旧数据迁移标记，不能执行。
3. Java 在同一数据库事务内创建任务、记录发布意图及不可变配置快照引用。
4. Java 创建执行批次并写入 Outbox，事务提交后投递 Kafka command；命令绑定 jobId、requestId、attemptId、generationId、执行所有者、文档引用及目标版本。
5. Python Consumer 消费命令，在执行前通过统一内部签名 受控接口取得短期文件票据和完整执行快照，下载并校验大小与文件哈希。
6. 先解析为项目统一 `ParsedDocument/ParsedBlock`，按结构保护和快照 Token 规则切片并生成来源信息与内容哈希；进入检索编排前再适配为 LangChain Document，避免框架对象承担机器契约职责。
7. 经 DoubaoVisionEmbeddings 调用 Embedding，经 ChineseBm25SparseEmbeddings 编码关键词，通过 Qdrant 适配层按固定 pointId 批量写入；本轮若已生成向量，不再调用 add_documents 重复向量化。
8. 等待写入可查询，核对清单、向量维度、有效片段数、稠密/稀疏完备性及查询探针。
9. 上传不可变清单并通过 Kafka result 回报结果；Java 校验当前执行批次、执行所有者、序列、目标版本和发布意图。
10. Java 提交产物元数据与索引成员，推进任务和版本状态；全部满足要求后发布。
11. 后台延迟清理未发布、失败或被替换的批次。

解析结果为空、只有页眉页脚或质量低于文件类型门槛时必须失败或进入人工处理，不可返回成功。

### 5.2 文件处理规则

| 类型 | 处理规则 | 引用位置 |
|---|---|---|
| TXT/Markdown | 识别段落、标题、代码块和 Markdown 表格；不执行文档内脚本 | 标题路径与 Unicode 字符区间 |
| DOCX | 保留标题层次、段落、表格表头和内嵌图片 | `DOCX_BLOCK`、章节与表格范围；图片按媒体序号定位，不虚构稳定页码 |
| CSV/XLSX | 逐工作表、逐行解析；首个非空行作为后续行上下文；XLSX 保留公式文本但不执行公式、宏或外链；多模态模式提取 XLSX 内嵌栅格图片 | 工作表名及从 1 开始的行列范围；图片增加工作表名和可用的锚点行列 |
| PPTX | 按幻灯片提取标题、文本形状、表格和图片 | 幻灯片号、形状序号及可选表格行号 |
| 文本 PDF | 保留页界和页面文本 | PDF 物理页号 |
| 扫描 PDF/文档图片 | 多模态模式下受控提取、统一转 PNG，再调用绑定的视觉/OCR Profile | 页号、幻灯片号、媒体序号或字符区间 |

当前实现覆盖 `.txt/.md/.markdown/.pdf/.docx/.csv/.xlsx/.pptx`，统一输出结构块和判别式来源位置；旧版 `.doc/.xls/.ppt` 明确返回 `DOCUMENT_UNSUPPORTED`。CSV 兼容 UTF-8/GB18030；XLSX 和 PPTX 均设置工作表、行数、幻灯片、块数、图片及字符数上限。复杂 PDF 阅读顺序、重复页眉页脚、高保真 Excel 图表渲染和宏不属于当前实现，需用脱敏真实资料继续验收。

原文作为不可信资料，内部“忽略规则”“调用工具”“泄露密钥”等文字仅作为内容处理。解析时不执行宏、不访问文档嵌入链接。压缩展开、页数、内存、CPU、时间和输出体积必须有限制。

### 5.3 切片初始策略

- 采用标题/段落结构优先、Token 限长兜底。
- 初始目标 chunkSizeTokens=600、overlapTokens=80；允许按资料评测调整。
- Token 单位与 tokenizerRevision 明确；旧 splitterConfig.chunkSize 不直接假定为 tokens，迁移时显式转换。
- 标题和必要背景写入检索文本，正文与元数据分别保留。
- 表格不从单元格中间随意截断；代码块和条款尽量保持完整。
- 建立 parentChunkId、previousChunkId、nextChunkId，扩展上下文仍遵守同一授权及版本。
- 同一文档内部重复页眉去重；跨文档内容相同可以复用技术计算，但不能合并掉来源和权限。

当前实现冻结 `parserRevision=python-parser-v1`、`splitterRevision=recursive-token-v1` 和 `tokenizerRevision=cl100k-base-v1`。切片器保持结构块边界，只有超长结构块才按 Token 继续拆分；chunk ID 不包含 attempt/generation，point ID 则包含 generation 以隔离批次。`cl100k-base-v1` 只表示当前本地、可复现的切片编码规则，不代表豆包官方 tokenizer；接入真实 Embedding 时仍须验证模型输入上限。

每个 Chunk 至少包含 knowledgeId、indexVersion、documentId、documentVersionId、generationId、chunkId、contentHash、text、titlePath、locator、parserRevision、splitterRevision、tokenizerRevision。

### 5.4 管理端按文档版本选择处理模型

本节已经落地到管理端、数据库字段、机器契约和任务快照；真实模型及生产数据质量仍待统一验收。

- 管理员在 Java 管理端上传、替换或重新解析文档时，为该文档版本选择 `TEXT` 或 `MULTIMODAL` 处理模式，并从模型管理中选择已发布且与目标索引及文档模态兼容的 Embedding、OCR/视觉模型；页面显示模型名称、供应商、能力和修订，不要求人工填写裸 ID。Rerank 在知识库检索策略中单独配置。
- 纯文本解析仍由确定性解析器完成，不用大模型改写原文；选择的文本 Embedding 模型在切片后生成向量。包含图片、扫描页或视觉表格的文档可以额外绑定 OCR/视觉理解模型和多模态 Embedding 模型。
- Java 将选择结果保存到 `knowledge_document_version`，首次执行时生成不可变 `DocumentProcessingSnapshot`，固化处理模式、文本/多模态 Embedding、可选视觉/OCR、输入能力、维度、命名向量与精确发布修订；Kafka 重试复用快照。已配置知识库在查询时使用当前检索策略和 Rerank 发布修订；旧文档级 Rerank 字段只用于未保存策略的存量库兼容，入库 Worker 不调用 Rerank。
- 目标索引版本预先声明允许的命名向量结构和模型兼容约束。上传页面只能选择与该索引兼容的已发布模型，禁止把任意模型输出写入同一个向量名称。
- 更换影响向量空间的模型、维度、距离或输入语义时必须创建新索引版本并重建；只重试传输或执行失败不得偷偷切换模型。
- Rerank 不参与文档解析或向量写入。管理员只在知识库检索策略中选择是否启用以及具体模型；Java 在查询请求中冻结当前发布修订，同时下发候选上限、阈值、RRF 和上下文预算。修改这些查询参数不创建文档版本，也不重建向量索引。

## 6. Qdrant 与混合检索索引

### 6.1 Collection 布局

本版按 knowledgeId + indexVersion 建独立 collection，简化完整索引构建、维度隔离与删除。名称固定为 `kb_<knowledgeId>_<indexVersionName>`，末段取数据库 `knowledge_index_version.index_version` 的业务名称；该名称只允许字母、数字、下划线和连字符，最长 64 个字符。机器契约中的 `IndexSnapshot.indexVersion` 继续使用索引版本记录的数字主键字符串，供权限、成员和任务关联。collection 名称由服务端生成，浏览器及文档内容不得决定。

每个 collection 至少包含 `dense_text` 和 `sparse`，启用多模态处理时增加 `dense_vision` 等独立命名向量。每个命名向量分别冻结模型修订、维度、距离和输入语义；同一片段只写入其处理模式要求的向量，不能把不同模型输出混入同一个向量名称。QdrantVectorStore 的向量名称与 collection 创建参数必须一致；正文使用 page_content、业务元数据统一嵌套在 metadata。过滤及 payload 索引字段因此使用 metadata.generationId、metadata.documentId 等实际路径，不能写入嵌套对象却按顶层字段过滤。查询分别召回文本、多模态和稀疏候选，再按可审计的 RRF 策略融合；同模型名、相同维度不代表不同 revision 的向量可兼容。Qdrant 命名向量允许同一点保存不同尺寸和类型的独立向量空间，具体 collection schema 以锁定服务版本验证。[Qdrant Named Vectors 官方文档](https://qdrant.tech/documentation/manage-data/vectors/)

该布局面向第一版可控数量的企业知识库。上线前用目标知识库数、保留版本数、文档和片段规模压测；达到容量边界后另行设计共享 collection/分区迁移，不以取消隔离解决性能问题。

### 6.2 点标识与元数据

chunkId 根据文件版本、规则版本、结构位置及内容哈希确定，内容相同但位置不同不得相互覆盖。

pointId 使用固定命名空间 UUIDv5，由 knowledgeId、indexVersion、generationId、chunkId 生成。Qdrant 支持 UUID 点标识，具体写入语义按锁定版本验证。[官方 Points 文档](https://qdrant.tech/documentation/manage-data/points/)

同一 attempt 内重试使用相同 pointId；不同 attempt 使用不同 generationId。旧 Worker 即使在租约失效后完成外部写入，也只能写自己的未发布批次，不能覆盖新批次。

为实际过滤字段建立 payload 索引，至少包含 generationId、documentId、documentVersionId；保留 knowledgeId、indexVersion 供一致性验证。文档正文和位置元数据用于片段预览；访问仍必须经过 Java。

### 6.3 中文关键词链路

采用确定性的中文分词与 BM25 稀疏编码，并与 dense 向量一同存入 Qdrant：

- 保留产品型号、制度编号、英文缩写、日期与数字单位。
- 明确分词词典版本、词项编码、停用词及 BM25 实现版本，写入索引快照。
- 编码器必须支持中文测试集；不能把普通空格分词直接当作中文方案。
- BM25 的统计语料范围、增量更新行为、稀疏维度映射必须固定并测试；不能每个 Worker 临时生成自己的词表。
- 若采用 Qdrant 提供的 IDF 能力，按锁定服务版本验证统计与更新规则；未通过前不得宣称完整 BM25 已实现。
- 模型及关键词编码器升级产生新 indexVersion，禁止在 ACTIVE 版本原地改变编码语义。

### 6.4 Embedding 模型管理与首个豆包适配

向量库固定使用 Qdrant，向量模型与供应商不写死。纯文本和非纯文本可以在 Java 模型管理中配置为不同的已发布 Vector Profile，并由文档版本处理快照选择。机器契约携带通用 `providerCode + apiMode`，Python 先按精确组合选择适配器，再按协议通配注册处理兼容供应商。当前实现 `doubao + ARK_MULTIMODAL_EMBEDDINGS` 的文本/图片输入，以及任意供应商声明 `OPENAI_EMBEDDINGS` 时使用 OpenAI 兼容 `/v1/embeddings`。未知协议明确失败，不根据供应商名称猜测。

官方模型说明确认该系列可以处理文本及视觉内容。是否让同一个多模态模型同时承担文本和图片向量，或分别使用文本/视觉模型，由模型评测和索引版本配置决定；任何方案都必须固定各自命名向量空间，不能在任务运行时临时替换。[字节 Seed 官方模型介绍](https://research.doubao.com/en/blog/built-on-seed1-6-flash-seed-1-6-embedding-launched)

| 项目 | 本版规则 |
|---|---|
| 模型配置 | 文本与多模态分别引用模型管理中的已发布 Vector Profile；首个多模态适配为 doubao-embedding-vision，modelId/Endpoint ID、providerCode、credentialKey 均来自 Java 快照 |
| 模型版本 | 不将用户给出的系列名自行替换成某个日期版本；M0 记录账号实际开通版本与响应中的模型标识 |
| API/SDK | Python Provider 使用方舟官方多模态向量化能力；不直接套用聊天 Provider 或通用文本 Embeddings 请求结构 |
| 维度 | 联调初值优先评估 1024；所选账号/版本确实支持且实测返回一致后才建立 collection，维度固定在索引快照 |
| 距离 | 初始评估 Cosine，归一化行为按 SDK/服务实际语义验证并冻结；禁止客户端额外处理而不记配置版本 |
| 输入粒度 | 每个片段独立产生向量；批量请求是否支持独立样本、是否将多个输入融合成单向量必须以该版本契约验证 |
| 输入角色 | 问题与文档使用匹配的检索编码策略；如采用不同 instructions，记录 query/document 模板及 revision |
| 返回校验 | 数量、维度、有限浮点值、空向量/零向量、用量字段逐项校验，错误结果不入库 |
| 请求并发 | Python 有界并发、限流与退避；批量 upsert Qdrant 不等于 Embedding API 支持相同批量语义 |

官方介绍中某具体版本支持 1024/2048 维，不代表所有无日期别名、Endpoint 或计费产品均有完全相同的参数。标准方舟服务与其他产品套餐的 base URL、密钥和模型命名不得混用，实施时记录实际服务类型。[官方模型说明](https://research.doubao.com/en/blog/built-on-seed1-6-flash-seed-1-6-embedding-launched)

当前已实现通用 EmbeddingProviderFactory、ArkMultimodalEmbeddingProvider 和 OpenAiCompatibleEmbeddingProvider，并接入正式入库 Worker。方舟适配器使用官方异步 arkruntime 0.6.0，每个文本片段或图片独立调用；OpenAI 兼容适配器使用异步 SDK 批量调用 `/v1/embeddings` 并恢复响应顺序。两者均校验数量、顺序、维度、有限数值和用量，关闭 SDK 自动重试，由 Java/Kafka 状态机控制任务重试。当前只完成离线请求映射测试，真实 Endpoint、维度和计费字段仍需联调。官方接口入口为[多模态向量化 API](https://www.volcengine.com/docs/82379/1523520)，SDK 行为以[方舟 Runtime Python SDK](https://github.com/volcengine/ark-runtime-python)为核验来源。

本版保留独立中文 BM25 作为第二路召回。若后续选定豆包版本可返回原生 sparse 向量，需另行评测；模型 sparse 输出不能未经验证就称为 BM25，也不能直接替换现有 sparse 命名向量。替换编码器须新建索引版本。

doubao-embedding-vision 的输出只用于检索，不提供可直接引用的 OCR 正文、最终问答或独立 Reranker 能力。多模态模式必须从模型管理绑定独立视觉/OCR Profile，当前支持以 `RESPONSES` 或 `CHAT_COMPLETIONS` 协议生成可信受控提示下的 OCR/图片描述文本；回答仍使用现有 Chat Profile。Rerank Provider/模型由知识库检索配置在查询时冻结当前发布修订；未保存策略的旧知识库按 Dense-only 默认配置执行。关闭重排时不需要 Rerank 模型，不能依赖 Python 默认模型。

当前图片入库从受控源文件提取图片，统一重新编码为去除附加元数据的 PNG，记录媒体摘要及 Markdown 字符区间、DOCX 媒体序号、PPTX 幻灯片号/图片序号、XLSX 工作表名与可用的单元格锚点，或 PDF 页码/图片序号；OCR/描述作为结构化 IMAGE 块参与文本切片，同时原图生成 `dense_vision`。临时图片只存在于任务工作区，长期来源仍是 Java 管理的原文档及稳定定位。最终证据仅有图片时，应由具备视觉输入能力的回答模型读取已授权图片或采用已保存的可信 OCR/描述；不能把图片向量当作文字证据交给纯文本模型。

### 6.5 LangChain Qdrant 检索适配

采用 langchain-qdrant 的 QdrantVectorStore 作为向量存储集成；服务端 collection 创建、批次核验、按 generation 清理等操作通过同一 infra 适配层中的 qdrant-client 完成。LangChain 集成支持 Dense、Sparse、Hybrid 模式，仍需由项目提供实际 Embeddings 和 SparseEmbeddings 实现。[官方 Qdrant 集成](https://docs.langchain.com/oss/python/integrations/vectorstores/qdrant)

生产主链路由 AuthorizedHybridRetriever 封装：按每个知识库和查询获取 Dense/BM25 的独立候选，再按第 9.4 节做一次明确的应用层 RRF。这样管理端可以显示各路命中与排名，不能先让 HYBRID 隐式融合，再把结果误当两路独立排名重复做 RRF。

QdrantVectorStore 的 HYBRID 模式可用于受控对比测试，只有其权重、候选深度、过滤和 RRF 语义与生产策略验证一致时才能替换底层执行。生产和检索测试仍共用同一项目检索服务，不能各实现一套排名规则。

BaseRetriever 接口只接收 query 的使用方式，由工厂提前绑定不可变授权快照；其适配输出可为 Document。需要各路分数和阶段统计时，调用同一强类型 RetrievalService 返回 RetrievalResult，不把分数或授权票据藏在全局状态里。

框架异步方法不自动等于底层原生异步：M0 检查锁定版 QdrantVectorStore 是否在线程池执行同步方法。同步路径使用有界执行器；需要原生网络取消时，Retriever 内部通过受控 AsyncQdrantClient 适配，仍保持 LangChain Retriever/Runnable 编排。两条路径共享 metadata、过滤、pointId 和测试，不重复向量化问题。

## 7. Kafka 任务、幂等与取消

机器可读 Topic、方向、消息 Schema、分区键与确认语义以 `main-service/docs/contracts/rag-v1/kafka.json` 为准，Java/Python DTO 由同一生成器维护。

| Topic | 方向 | 用途 |
|---|---|---|
| `knowledge.ingestion.command.v1` | Java → Python | INGESTION / CLEANUP / RECONCILE 调度，使用 jobId 作为分区键；REBUILD 只作为 Java 父任务拆分 INGESTION 子任务 |
| `knowledge.job.result.v1` | Python → Java | STARTED / PROGRESS / FAILED / CANCELLED / ARTIFACTS_READY |
| `knowledge.job.control.v1` | Java → Python | CANCEL；每个 Worker 独立消费组接收广播 |
| `knowledge.dlq.v1` | 双端 → 运维 | 保存原 Topic、分区、offset、摘要与失败原因，不复制正文或凭证 |

### 7.1 状态与执行批次

~~~text
PENDING ─→ QUEUED ─→ RUNNING ─→ SUCCEEDED
                         │
                         ├─可重试故障→ RETRY_WAIT ─→ 新执行批次 ─→ QUEUED
                         └─不可重试或耗尽次数→ FAILED

PENDING / RETRY_WAIT / RUNNING → CANCELLED
~~~

Java 在提交、人工重试或租约超时重试时创建新的执行批次，递增 attemptCount，生成 attemptId、generationId、requestId 和执行所有者，并在同一短事务写任务状态与 Outbox。Broker 确认接收后任务进入 QUEUED；Python 回报 STARTED 后进入 RUNNING。FAILED/CANCELLED 的人工重试必须创建新批次；已删除资源不可重试。

Outbox Publisher 在事务外发送 Kafka 消息，多实例通过 claimToken/claimUntil 竞争待发送记录。网络失败只重发同一 Outbox，不增加 attemptCount。发送超过上限进入 DEAD 并使任务失败；恢复执行必须由受控重试创建新批次。

Kafka 采用至少一次投递。command 以 knowledgeJobId 为分区键，Worker 以 jobId、attemptId 和 generationId 判定幂等；重复消息不得重复发布索引、重复结算或让旧批次覆盖新批次。

### 7.2 租约、回报与重试

- Java 保存执行租约和最后接受序列；Python 通过 Kafka result 上报 STARTED、PROGRESS、ARTIFACTS_READY、FAILED 或 CANCELLED，刷新进度和租约。
- 回报绑定 jobId、requestId、attemptId、generationId、executorId、sequence 和 resultHash；租约判断采用 Java 服务端时间。
- 同批次相同 sequence 和相同摘要视为重复成功；相同 sequence 不同内容拒绝；旧序列、旧 generation、错误执行所有者或已终结任务不得更新状态。
- Java 租约扫描负责把超时任务转入 RETRY_WAIT 并创建新执行批次；正式执行器必须保证稳定 chunk upsert、外部调用可恢复和已发生用量可追踪。
- 取消由 Java 终结任务并通过 control topic 广播；Python 收到取消或发现批次失效后停止提交新结果，尽力取消模型请求，并按实际发生量回报费用。
- Python 只有在终态 result 已被 Kafka 确认后才提交 command offset；Java 只有在任务状态事务提交后才确认 result，重复投递不得产生重复业务副作用。

### 7.3 重试与清理

仅对网络故障、临时限流等可恢复错误自动重试。认证失败、格式不支持、向量维度不匹配和文件哈希不一致不无限重试。

CLEANUP 任务只删除 Java 指定的单个 generation。失败、取消、租约超时和投递失败会触发精确清理；被新版本替换的 generation 在保留期届满后再清理。Java 在下发及接收结果时复核索引成员、活动任务和保留规则，普通入库 Worker 不得自行清空 collection。

RECONCILE 只让 Python 盘点 Qdrant generation，不在 Python 端直接删除。Java 收到盘点结果后读取最新保留集合，确认孤儿后逐个创建 CLEANUP，避免旧快照误删并发写入；已知但缺失的向量批次进入补偿状态。Qdrant 完成后，Java 以 VECTOR_CLEANED → CLEANED 两阶段幂等删除 OSS 清单，不给 Python OSS 全局密钥。

## 8. 发布、重建与回滚

### 8.1 单文档发布

~~~text
V1 + generation-A 在线
    ↓ 上传 V2
V2 + generation-B 构建，A 继续服务
    ↓ 校验 B 完整且任务/发布意图有效
Java 事务更新索引成员 A → B
    ↓ 新请求只能获得 B 的允许批次
A 延迟清理
~~~

Qdrant 批次写入和 MySQL 发布不做跨系统事务。Java 只在可查询产物已验证后更新指针。写入成功而 Java 提交失败的产物保持未发布，允许幂等补交或过期清理。

向量中即使存在未发布批次，查询也必须带 Java 生成的 generation 允许集合。不能依靠异步写入的 READY payload 独自决定内容发布。

### 8.2 全量重建

1. 创建 BUILDING 索引，冻结模型、解析、切片、稀疏配置。
2. 固定本次构建的目标文档版本清单和 manifestRevision。
3. 为每个目标文件版本创建子任务，REBUILD 为父级聚合任务；父任务不与子任务重复执行同一向量化工作。
4. 目标成员全部成功、无遗漏且通过检索探针后，Java 推进为 READY。
5. 激活前重新核对库状态、文档状态及构建期间的内容变化。
6. 本版采用“变更后补齐再激活”：构建期间新上传/替换的有效发布意图进入待补齐清单；未追平不得标记完整。
7. Java 锁定知识库并原子更新 ACTIVE 指针、成员对应的页面投影，旧索引归档。

当前代码已实现 REBUILD 父任务、目标 IndexSnapshot 冻结、当前发布文档枚举、INGESTION 子任务下发和父任务状态聚合，并在管理端索引版本面板提供重建入口。真实数据库、Kafka、Qdrant 和模型服务中的完整重建与激活仍留到统一验收阶段。

已有 ACTIVE 版本不可通过普通 save 接口改模型、规则、维度或任意伪造 READY。READY 由任务结果聚合与完整性核验产生；人工页面不接受 fragmentCount/status 等字段绕过验收。

### 8.3 回滚与历史引用

回滚以历史索引仍有完整产物为前提，经 MANAGE 权限及完整性校验后，原子切换索引。回滚不得恢复已删除或停用文档的访问资格。

正在进行的请求固定索引/成员快照；普通版本切换不改变它的证据。旧批次至少保留至相关快照最大寿命和配置宽限期结束。权限撤回、停用和删除仍按实时有效性校验生效。

历史回答保存精确版本和位置，不把旧引用悄悄指向最新文件。文件已依法定/配置保留策略清理时，保留引用元数据并显示“原文已不可用”；不伪造可访问链接。

## 9. 在线问答流程

### 9.1 处理步骤

~~~mermaid
flowchart TD
    A[问题与必要历史] --> B[Java：会话授权、范围与预算]
    B --> C[Python：保留原问题并构建检索请求]
    C --> E[安全条件与业务条件组合]
    E --> F[Dense 与可选 BM25 检索]
    F --> G[RRF 融合、去重]
    G --> H[Java 批量复核候选]
    H --> I[Rerank]
    I --> J[筛选证据、补充邻接上下文]
    J --> J2[Java 再次复核扩展后候选]
    J2 --> K{证据是否充分}
    K -->|否| L[澄清或无依据答复]
    K -->|是| M[LLM 回答]
    M --> N[校验引用、Java 保存与结算]
~~~

### 9.2 问题理解

保留 originalQuery。多轮问题只携带必要且仍有权访问的会话历史；v1 不调用查询改写模型，避免增加一套未验证的模型配置、Token 费用和失败路径。

聊天的 FIXED/AUTO 模型选择与是否启用 RAG 由 Java 请求和权限决定；显式知识库范围优先。当前只读 MCP 工具可辅助回答，但未授权或未配置的实时业务工具不能假装已查询。

改写保护型号、编号、人名、日期、金额、比较关系和否定词。无法可靠判断范围时询问澄清。扩展最多 2 条，连同原问题总计最多 3 条；配置可以进一步收紧。

### 9.3 过滤条件

安全条件只能由 Java 确定：

Java 在统一资源授权基础上计算可检索内容范围：先校验 READ 权限，再校验库/文档启用状态、发布版本和索引可用性。创建者不因 user_id 获得额外权限；平台根账号保留管理停用资源的能力，但该管理特权不得跳过 RAG 内容有效性校验，任何身份均不得检索停用、删除或未发布内容。Python/LangChain 仅执行 Java 下发的范围及候选复核结果，不根据创建人、角色或部门自行授予访问资格。此处是 RAG 实施要求，当前 Java 管理权限入口不能单独作为检索放行依据。

~~~text
有效知识库范围
= 当前用户 READ 权限
∩ Agent 候选范围（使用 Agent 时）
∩ 用户显式选择范围（选择时）
∩ 已启用知识库
∩ 可用 ACTIVE 索引
~~~

每一路查询、扩展查询、重试、父片段和相邻片段读取必须继承同一安全范围。空范围直接返回无可用知识，不执行无过滤检索。

用户明确指定的日期、文档等约束不得自动移除。模型推断的业务筛选单独标记 inferred；允许在零结果时有界放宽，但不得放宽安全条件和用户明确条件。

### 9.4 召回与融合

初始每个查询、每个知识库 denseTopK=30、sparseTopK=30。它们是实验起点，不承诺固定效果。

同一原问题的各路候选按排名做 RRF 融合。采用明确的 rank 从 1 起、k=60 的应用层配置，不能假定等于 Qdrant 服务默认值；若使用服务原生 RRF，必须验证参数及排名约定等价。权重首期相等，通过评测后再调整。

多查询融合时限制单个扩展问题贡献，避免同义改写重复累积淹没原问题。先按稳定片段身份去重，再限制进入重排的全局候选数，初始最多 50。

当前应用层实现使用 Java 检索快照中的 `rrfK`，以完整来源元组作为稳定去重键；融合结果写入 `rrfScore`、`rrfMatchedRoutes` 和 `rrfRank`，进入重排的数量由 `rerankTopK` 限制。

### 9.5 重排和上下文

Rerank 模型不得写死。模型、供应商、适配器、凭证别名、输入模态和输入上限在 Java 模型管理中发布，由 `knowledge_info.retrieval_config` 保存模型引用；Java 在每次查询开始时冻结当前发布修订并放入 `KnowledgeScope.retrievalSettings`。文档处理快照中的旧 `rerankPolicy` 和索引表中的旧 Rerank 字段仅用于历史数据兼容，不再参与查询路由。

单独切换 Rerank Profile 不改变已存向量，不需要重新解析文档、创建文档版本或重建 Qdrant 索引；新请求冻结新的发布修订，已开始的请求继续使用原执行快照。未启用 Rerank 时直接使用 RRF 顺序。

重排输入使用原问题或经确认的独立问题，不以每个扩展问句分别作为最终答案意图。候选经 Java 复核后，按 `documentVersionId + rerankProfileRevision + inputMode` 分组发送相应 Rerank Provider。同一请求命中不同文档绑定时，各组独立重排，随后用 RRF 或另一种明确的基于名次的融合规则合并；不同 Rerank 模型的原始分数刻度不可直接比较。融合过程保留分组、模型修订、组内名次和最终名次，便于审计和评测。

当前 `ProfileGroupedReranker` 已按上述规则实现为 LangChain Runnable：Provider 由 `providerCode + adapterCode` 动态解析，当前注册 `COHERE_RERANK_V2` 通用文本协议和 `qianwen + DASHSCOPE_TEXT_RERANK` 阿里云百炼文本排序协议；新增供应商协议不需要改动分组、路由或跨组融合逻辑。多模态 Rerank Profile 可以参与分组路由，但仍需注册与其图片输入契约匹配的 Provider，禁止把 OCR 文本冒充原图输入。

初始 contextTopK=8、每文档最多 3 个独立片段；这些限制可配置，跨章节完整回答的需求通过评测调整。相邻片段、父片段、表头补齐计入同一 Token 预算。

重排分数不是正确率，RRF 分数也不是“证据充分概率”。不同模型的阈值分别标定。证据判定覆盖相关性、是否包含问题要求的事实、版本一致性、相互冲突及引用定位。

若资料存在互相冲突的规定，展示对应版本/生效信息并解释冲突，不自行选择未获依据的结论。

### 9.6 生成与引用

上下文模板明确区分系统规则、用户问题和不可信资料。模型只可引用本次提供的 citationId；内部工具、权限和配置不接受文档指令修改。

生成前分配引用编号；生成后检查引用存在、版本匹配和来源支持情况。单纯编号存在不能证明语义有依据，需结合评测与证据规则。

最终输出：
- 有依据：回答 + 对应事实引用。
- 部分依据：明确已知部分和缺失内容，不补齐未知事实。
- 无依据：固定无证据答复或澄清问题。
- 系统故障：明确检索/模型暂不可用，不伪装成“知识库没有答案”。

流式 delta 为暂存内容；completed 才代表本轮最终结果。已发送的文字无法撤回，严格引用校验不通过时不得把该草稿保存为成功答案，前端显示失败/未完成状态。

### 9.7 LangChain Runnable 链的执行约定

第一版由 LangChainRagPipeline 装配如下概念链，名称是项目组件而非声称存在同名官方 API：

~~~text
ValidatedRagRequest
  → QueryPlan（保留原问题；v1 不调用查询改写模型）
  → Retrieval Runnable（绑定授权快照；各库各路有界并发）
  → Fusion Runnable（RRF、去重、保留各路排名）
  → CandidateValidation Runnable（Java 有效性复核）
  → Rerank Runnable
  → ContextSelection Runnable（补齐、预算与证据判定）
  → AnswerPlan（允许证据、citation 映射或无依据/澄清结果）
  → ChatPromptTemplate（需要生成时）
  → Provider Streaming Runnable
  → 项目 SSE 事件适配器
~~~

并行仅用于独立召回；候选验证必须先于重排和生成。当前 `AuthorizedRetrievalChain` 已实现召回、RRF、Java 候选复核和分组 Rerank，并由内部检索测试与 `LangChainRagPipeline` 复用；`ContextSelector` 在重排后按来源 generation 补齐父/邻接片段、去重并执行上下文 Token/数量预算，扩展结果会再次提交 Java 复核。空命中仍提交空候选集合并取得绑定空集合的票据，不能跳过权限边界或退化为全库。每一阶段携带强类型请求上下文、deadline 和预算；RunnableConfig 只用于执行配置与脱敏关联信息，不能作为权限的唯一来源。

检索测试执行同一链到 AnswerPlan/证据选择处并返回诊断，不额外发起第二遍检索。调用 ainvoke/astream 时明确最大并发；CPU 解析与同步依赖进入受限 Worker/执行器。

不得对整条生成链使用无条件 with_retry；阶段重试按第 7、13 节的幂等和计费规则执行。框架 callback 的重复事件不等于新的业务用量，结算以具名 callId 和 Provider 返回为准。

生成前阶段用非流式结果即可，期间 keepalive 由项目 SSE 层持续发送。生成阶段必须透传真实异步流；模型返回的最终回答和实际引用再次校验后，由项目唯一产生 completed/failed。

## 10. 权限快照与撤回语义

### 10.1 请求级范围

Java 为已鉴权请求创建短期 scopeSnapshotId，绑定 requestId、userId、Agent 范围、库/indexVersion、permissionRevision、成员 manifestRevision、允许 generationId 及过期时间。快照是技术缓存，不建立通用 ai_run 业务表。

Python 凭内部签名读取快照清单，不自行提供任意 userId 扩大范围。允许清单可分页传输，但必须完整、版本一致；超出请求容量限制时要求缩小范围或按受控分批查询，不截断后放宽过滤。

Qdrant 查询必须包含相应允许 generationId 集合；未发布批次因此不能进入候选。多个库的条件按配对的库/索引/批次组合，不能拆为互相独立的 IN 条件造成交叉匹配。

### 10.2 候选复核

Python 将候选标识批量提交 Java。Java 根据快照和当前权限/文档状态复核，返回允许候选及短期 validationTicket。复核包括当前角色、部门、ACL 有效期、Agent 范围及库/文档停用删除状态，不能只比 permissionRevision。

普通索引切换允许已固定快照继续读取原版本；权限撤回立即使后续复核失败。Python 缓存失效或 Java 不可用时拒绝继续使用内容。

默认不缓存最终答案。技术缓存键包含模型/规则 revision、范围指纹、索引和 generation；命中缓存仍需权限校验，不跨用户直接复用受限内容。

### 10.3 在途请求与历史消息

- 在发送候选正文给外部模型前校验有效性；长时间等待导致票据过期时重新校验。
- Java 收到权限撤回/文档停用事件后取消关联活动请求，并在完成保存和引用访问时再次校验。
- 本版不承诺撤回已经发往模型或用户的字节；已发送内容无法技术回收。
- 本轮被撤权时不继续输出受限引用，不保存为成功答案；已产生模型费用按实际用量结算。
- 带 RAG 来源的历史消息在用于后续模型上下文前复核来源权限。无法复核时排除该消息的受限正文。
- 用户主动保存、复制或手动再次输入的文本不属于系统可撤回范围。

## 11. Java/Python 契约与接口

本节除既有普通聊天路径外，均为本版新增能力。机器契约、Kafka 完整任务快照、统一签名、受控源文件读取、WRITE_MANIFEST 清单上传、文本/图片解析切片、动态 Embedding、视觉/OCR、中文 BM25、Qdrant 写入、Java ARTIFACTS_READY 验收、generation 清理/对账、REBUILD、模型预算、Java 授权范围/成员分页/候选复核、Python AuthorizedHybridRetriever、RRF、分组 Rerank、父/邻接片段补齐、上下文预算、RAG 流式回答、引用保存/回访鉴权和查询/生成结算均已落地并通过离线测试。按产品决定，不新增 Java 管理端检索测试页面或通用片段查询接口。

数据库 Long 标识在新增 RAG 跨服务字段中统一使用十进制字符串，避免前端精度问题；已有 modelProfile.id 等字段保留现有类型，不能顺带更改普通聊天协议。时间统一为带时区的 ISO 8601，截止时间由 Java 计算。

### 11.1 公开接口

| 方法 | 路径 | 归属/权限 | 语义 |
|---|---|---|---|
| POST | /chat/send | 既有 Java 入口；登录用户、模型、会话与知识库 READ 权限 | `knowledgeId` 为空时保持普通聊天；非空时创建短期授权范围并进入 RAG 链 |
| GET | /chat/my/messages | Java；本人会话权限 | 历史消息同时返回已保存的最终引用 |
| GET | /chat/my/messages/{messageId}/citations/{citationId} | Java；本人会话 + 当前来源权限 | 回访引用时重新校验会话归属、知识库 ACL、当前索引成员和 generation 后返回引用信息 |

继续复用既有知识库、上传、版本、任务取消/重试和索引激活接口，不另建平行上传体系。按产品决定，不提供 Java 管理端检索测试页面或通用文档片段查询接口；内部检索测试只用于生产同链诊断与自动化验证。

`ChatSendBo` 增加可选 `knowledgeId`。缺省时保持普通聊天兼容；提供后端已授权知识库 ID 时，Java 生成不可伪造的 `RagExecutionScope`。前端不能提交 scopeSnapshotId、任意过滤表达式、collection、模型修订或凭证。

### 11.2 Java 调用 Python

| 方法 | 路径 | 语义 |
|---|---|---|
| POST | /internal/ai/v1/chat/stream | 既有普通聊天流式路径，协议与行为保持兼容 |
| POST | /internal/ai/v1/chat/rag/stream | RAG 流式回答；接收完整授权范围与模型快照，返回 citation/delta/usage/keepalive/completed/failed |
| POST | /internal/ai/v1/knowledge/retrieval-tests | 内部同步检索诊断，复用生产召回、复核与重排链，不调用回答模型 |

RAG 使用独立的严格 `RagChatStreamRequest`。完整 `RagExecutionScope` 显式携带 `retrievalSnapshot`、每个知识库的 `retrievalSettings`、`indexSnapshots`、上下文预算和 `expiresAt`。契约不再包含文档级 `rerankRoutes` 或未实现的查询改写快照；Java 必须冻结可执行模型修订，Python 不查询业务 MySQL 补齐配置。

Embedding/Rerank 定义独立具名模型：包含 id、revision、providerCode、modelName、credentialKey、能力及输入限制；Embedding 增加 dimensions、distance、normalization 和 tokenizerRevision。不得把聊天专用 CHAT_COMPLETIONS/RESPONSES 枚举直接套到所有能力。

### 11.3 Kafka 任务契约与 Python 调用 Java

知识库任务调度不提供 claim/heartbeat/complete/failed HTTP 轮询接口，旧接口 DTO 也不再保留。当前任务通道如下：

| 类型 | 契约 | 语义 |
|---|---|---|
| Kafka command | knowledge.ingestion.command.v1 | Java Outbox 向 Python 下发 INGESTION/CLEANUP/RECONCILE 执行批次；REBUILD 由 Java 父任务扇出 INGESTION 子任务 |
| Kafka result | knowledge.job.result.v1 | Python 回报 STARTED/PROGRESS/ARTIFACTS_READY/FAILED/CANCELLED |
| Kafka control | knowledge.job.control.v1 | Java 广播取消；Worker 启动时恢复未过期取消 |
| Kafka DLQ | knowledge.dlq.v1 | 超过重试上限、格式错误或不可处理消息进入运维补偿 |
| HTTP artifact | POST /internal/knowledge/jobs/{id}/artifact-tickets；GET /internal/knowledge/jobs/{id}/artifacts/{objectId}；PUT /internal/knowledge/jobs/{id}/manifests/{generationId} | READ_SOURCE 与 WRITE_MANIFEST 已实现；票据绑定当前 attempt、generation、worker、对象和租约，清单按原始字节哈希幂等保存 |
| HTTP scope | /internal/knowledge/scopes/{snapshotId}/manifest-pages | 已实现；分页获取绑定快照且当前仍有效的索引成员 |
| HTTP validation | /internal/knowledge/scopes/{snapshotId}/validate-candidates | 已实现并接入 RRF 与 Rerank 之间；空候选签发绑定空集合的票据，响应不得扩大原候选集合 |

Kafka command 绑定 jobId、jobType、requestId、attemptId、generationId、任务判别体、完整 `IndexSnapshot`、文档处理快照和执行预算。Java 从精确发布修订生成快照并在首次 attempt 持久化，重试复用同一 JSON；INGESTION 携带文档引用，CLEANUP/RECONCILE 携带冻结维护范围。Python 不得查询业务 MySQL。

Kafka result 至少绑定 jobId、requestId、attemptId、generationId、executorId、sequence、status、progress、errorCode、resultHash 和产物摘要。Java 按当前执行批次、所有者、序列、租约、目标版本和发布意图验收；完整清单仍以 ArtifactManifest 定义，不能因为 Worker 声称 ARTIFACTS_READY 就直接切换 ACTIVE。

上述 HTTP 接口不接受浏览器用户 Token，并使用统一内部签名；服务身份必须与任务或快照绑定。统一内部签名 保护受控资料读取与验收请求，不替代 Kafka Broker 的网络、账号和 Topic ACL。Python 不调用“任意 userId 解析权限”接口。

### 11.4 内部认证

Java 与 Python 的所有内部 HTTP 调用采用同一套签名：固定协议标识、HTTP 方法、规范路径、timestamp、nonce 和原始 body SHA-256 按顺序连接后执行 HMAC-SHA256。普通聊天已经切换到该规则，Python 不再接受历史签名格式。

X-AI-Signature-Version 固定为 2，用于标识当前唯一线上字节格式并阻止降级；它不表示系统同时运行两套签名。缺少版本头或任一签名组件必须拒绝。发送方和接收方使用共享测试向量验证字节完全一致。

GET 原文访问属于浏览器到 Java 的正常用户授权；服务间敏感操作使用统一签名的 POST，不把快照或租约凭证放到 URL 查询串。

多实例使用共享 nonce 防重放存储及 TTL，不能继续仅靠每个进程的内存字典。两方向服务凭证分离、TLS/内网限制、请求大小与频率限制一并实施。

### 11.5 SSE 与终态

| 事件 | 本版语义 |
|---|---|
| keepalive | 检索、重排或模型等待期间保持连接，不计入聊天正文 |
| citation | 新增；声明已授权候选的引用映射，不表示模型最终一定使用 |
| delta | 既有增量文本 |
| usage | 保留既有 Chat Token 语义，不混入稀疏检索次数 |
| completed | 至多一次；增加 answerStatus、实际使用 citations 和 executionUsage |
| failed | 至多一次；稳定错误码，前端标记草稿未完成 |

answerStatus 目标枚举为 ANSWERED、PARTIAL、NO_EVIDENCE、NEEDS_CLARIFICATION。NO_EVIDENCE 是正常业务结果，不是系统错误。

Citation 至少包含 citationId、knowledgeId、indexVersion、documentId、documentVersionId、generationId、chunkId、contentHash、title 和 locator。机器契约用 Citation.location.source 承载完整来源元组、location 承载标题与 locator；locator 使用判别类型 PDF_PAGE、TEXT_RANGE、DOC_BLOCK、DOCX_BLOCK、PRESENTATION_BLOCK、SPREADSHEET_RANGE、TABLE_RANGE 和 IMAGE，不适用字段不伪造。

候选引用事件不持久化为“答案实际引用”；completed 中的引用必须是本次候选集合的子集。Java 生成用户可访问来源链接，不把 Qdrant 地址、OSS 长期 URL 或短期内部写票据发给浏览器。

### 11.6 错误与降级

| 错误码 | 行为 |
|---|---|
| AUTHORIZATION | 拒绝，不能降级为全库或匿名检索 |
| KNOWLEDGE_NOT_READY | 库无可用索引，提示等待构建 |
| SCOPE_EXPIRED | 重新经 Java 校验；不得沿用过期范围 |
| VECTOR_UNAVAILABLE | 显示检索不可用 |
| EMBEDDING_UNAVAILABLE | 显示语义检索不可用 |
| RERANK_UNAVAILABLE | 默认失败；只有检索配置明确允许时才使用融合结果，并标记 degraded |
| DOCUMENT_UNSUPPORTED / PARSE_FAILED | 入库失败，可定位文件类型/解析阶段 |
| INDEX_MISMATCH | 拒绝错误配置或错误版本写入/查询 |
| LEASE_EXPIRED / STALE_ATTEMPT | 拒绝旧 Worker 更新，不能自动发布 |
| BUDGET_EXCEEDED | 停止后续付费调用，保留已发生用量 |
| TIMEOUT / CANCELLED | 停止后续工作，收束资源与单一终态 |
| CITATION_INVALID | 草稿不得标记为成功完成 |

新增错误码需同步 Java 映射、Python Literal/枚举、Schema、前端和契约测试。不能让未识别错误被转换成无依据答复。

## 12. 数据库与配置变更清单

本节包含实施演进记录。Kafka 任务字段、索引/文档快照、索引成员、产物批次、检索配置和引用结构已进入初始化 SQL 与对应增量迁移；实际数据库是否执行仍以项目进度及部署迁移记录为准。旧 `rerank_snapshot` 仅保留历史兼容，新任务不再写入。

### 12.1 表结构

| 表 | 目标变更 |
|---|---|
| knowledge_info | 使用 `retrieval_config` 保存知识库级检索策略；保留 current_index_version、permission_revision |
| knowledge_document | 新增 publication_revision，区分发布意图；修正文档状态和当前版本投影 |
| knowledge_document_version | 保留原文件元数据；保存 processing_mode、Embedding/OCR/视觉 Profile 引用与精确修订；旧 Rerank 字段只做历史兼容，不把一个文件版本的成功等同所有索引已完成 |
| knowledge_index_version | 增加不可变索引快照、配置哈希、collection_name、文本/多模态/稀疏命名向量定义、各向量模型兼容约束、parser/splitter/tokenizer/sparse revision、manifest_revision；Rerank 不作为最终权威字段 |
| knowledge_parse_job | 保存 attempt_id、generation_id、lease_owner、lease_expire_time、last_sequence、last_result_hash 及首次固化的处理/索引快照；重试复用快照，不保存旧 HTTP leaseToken |
| knowledge_index_document（新增） | 索引内文档版本与已发布 generation 映射，manifest 哈希、片段数、成员 revision |
| knowledge_artifact_generation（新增） | 各 attempt 技术产物清单、对象引用、阶段与保留/清理状态 |
| knowledge_info.retrieval_config | 现行知识库级召回、RRF、Rerank 模型引用、候选数量和相关性阈值；空值按 Dense-only 默认配置读取 |
| chat_message_citation（新增） | messageId、citationId、精确来源标识、位置、哈希；不存长期原文 URL |

用量优先扩展现有用量明细体系；若现有表不能表示分阶段计量，在核实后新增统一明细表，不创建第二套余额账本。

必要唯一约束与索引：
- 任务幂等键唯一；新显式重新解析必须携带新的 reparseRevision，传输重试沿用同一幂等键。
- 任务 (status,next_attempt_at)、(status,lease_expire_time) 支持重试调度、租约扫描和超时回收。
- 成员 (knowledge_id,index_version,document_id) 唯一，generation_id 可反查。
- 产物 generation_id 唯一，支持生命周期与清理时间查询。
- 引用 (message_id,citation_id) 唯一。
- 用量按 (requestId 或 jobId,attemptId,stage,callId) 去重，跨阶段不重复扣款。

### 12.2 配置归属

| 参数类型 | 位置 |
|---|---|
| 上传大小、任务重试上限、保留期、可调运维阈值 | 既有 sys_config / AiRuntimeConfig |
| Dense/Sparse、召回数量、RRF、Rerank 和相关性阈值 | `knowledge_info.retrieval_config`；新知识库默认 Dense-only |
| 解析/OCR、Embedding、Chat、Rerank 模型与凭证别名 | Java 模型发布配置；文档处理配置引用 Embedding/OCR，知识库检索配置引用 Rerank，知识库或 Agent 配置引用 Chat |
| 切片、Tokenizer、稀疏编码、维度与距离 | 不可变 indexVersion 快照 |
| Qdrant 地址、内部服务地址、并发、连接超时、凭证 | Python Settings 与部署环境，密钥受控注入 |

目标初始参数：

| 参数 | 初值 | 限制 |
|---|---:|---|
| queryRewrite | 不启用 | v1 契约不包含查询改写；后续必须经评测后单独设计 |
| denseTopK / sparseTopK | 30 / 30 | 各路有界 |
| rrfK | 60 | 显式配置与排名约定 |
| rerankCandidateLimit | 50 | 全局限制 |
| contextTopK | 8 | 相关片段不足不凑数 |
| maxChunksPerDocument | 3 | 可按任务类型调整 |
| contextBudgetTokens | 6000 | 同时受模型输入和账户预算限制 |
| chunkSizeTokens / overlapTokens | 600 / 80 | 与 tokenizerRevision 绑定 |
| executionLeaseSeconds / resultHeartbeatSeconds | 60 / 10 | Java 执行租约必须显著大于 Python 进度回报间隔及网络延迟 |

上述参数是实验初值；召回/重排质量阈值由评测集标定后写入 Profile，不能虚构跨模型通用的 0.7 等分数门槛。

## 13. Token、费用与运行控制

RAG 预算包含历史、系统 Prompt、问题、证据、输出，以及 Embedding/Rerank 的额外调用；v1 不预留查询改写调用。

1. Java 按模型配置、上下文上限及检索预算预留。
2. Python 按实际 tokenizer 或经过验证的保守估算裁剪，满足模型输入上限与总上下文窗口。
3. 加入证据后再次核验，不直接沿用普通聊天的输入估算。
4. executionUsage 按 stage 记录 provider/profileRevision、callId、attempt、计量单位、数量和状态。
5. Rerank 按供应商实际计量记录请求/文档对等单位，不伪造 Token；价格规则和金额计算属于 Java。
6. Worker 任务预算按 job/attempt 预留；取消、失败和部分完成仍可能有已发生费用。
7. 供应商未返回用量时标记 UNKNOWN/ESTIMATED 待对账，不将缺失用量直接当作零费用。
8. Java 幂等结算实际费用并释放未用预留；不得因重复 complete/SSE 终态重复扣款。

所有阶段共享 Java 给定 deadline；分别设置连接、阶段、首事件、流空闲超时。检索与重排等待期间持续 keepalive；Java/浏览器断连取消传播到 Python 子任务。默认不自动重放整个聊天请求。

## 14. 前端交付

### 14.1 管理端

在现有知识库页面扩展，不重建平行管理系统：

- 上传后显示文件版本、当前在线版本、解析阶段、质量提示和失败原因。
- 任务详情展示执行批次、尝试次数、阶段进度、重试/取消和脱敏错误。
- 片段预览支持正文、标题、原文位置、片段大小及相邻片段。
- 检索测试展示原问题、过滤条件、Dense/BM25 命中、RRF 排名、可选重排结果、最终证据和阶段耗时。
- 检索测试走生产相同的检索能力和 ACL；不得通过“调试模式”读取全库。
- 索引详情展示配置版本、成员进度、失败文档、完整性检查和激活/回滚。
- 展示 Embedding 等技术配置时使用可读名称与 revision，不暴露凭证和内部地址。

### 14.2 用户端

- 显式切换普通聊天/知识库问答，可选有权访问的库。
- 等待阶段显示“正在检索资料”等简短状态，避免显示内部接口和 SDK 信息。
- 回答提供引用标记、来源标题、版本和可用的位置。
- 点击引用由 Java 重新授权，预览精确来源；撤权/删除时显示不可访问。
- 明确区分没有依据、库未就绪、服务故障和回答未完成。
- 刷新聊天仍能恢复保存的引用；不依赖仅在浏览器内存存在的 SSE 映射。

本版不新增员工逐个授权 UI，不改变已有无关菜单和页面行为。新增菜单及按钮权限通过项目既有动态菜单机制登记。

## 15. 建议代码组织与实施边界

以下为新增目标目录，按职责逐步添加，沿用实际已有同职责模块。

~~~text
main-service/docs/contracts/rag-v1/                # 当前：OpenAPI、JSON Schema、契约样例

main-service/ruoyi-modules/ruoyi-ai-core/src/main/java/org/ruoyi/
  controller/knowledge/              # 扩展管理接口与受控内部入口
  service/knowledge/                 # 范围、发布、任务、引用与清理编排
  integration/python/                # 扩展检索/片段客户端
  domain/dto/knowledge/              # 新增强类型跨服务对象
  domain/entity/knowledge/           # 索引成员、产物、检索配置
  mapper/knowledge/                  # 新增查询和原子更新
main-service/ruoyi-modules/ruoyi-ai-core/src/main/resources/mapper/knowledge/

ai-service/src/ai_service/
  contracts/knowledge.py             # 索引、任务、范围与引用契约
  api/knowledge.py                   # 内部检索与片段 API
  domain/chat_execution.py           # 接入 RAG 编排
  rag/
    ingestion.py                     # 文件入库用例，转换 Document 并调用框架组件
    pipeline.py                      # RAG 聊天 Runnable/LCEL 与引用闭环
    execution.py                     # 授权检索链及内部检索测试用例
    langchain_documents.py           # Document 与强类型元数据转换
    parsers.py                       # 文档格式分派及 Office/文本/表格解析
    parser_limits.py                 # 解析大小、块数和图片数量上限
    pdf_parser.py                    # PDF 文本与图片解析
    image_normalizer.py              # 图片格式、尺寸与哈希归一化
    chunking.py                      # 结构切片
    sparse_encoding.py               # 中文关键词编码
    retrieval.py                     # AuthorizedHybridRetriever 与多路召回/RRF
    reranking.py                     # Rerank Provider 的 Runnable 适配
    context.py                       # 上下文预算、ChatPromptTemplate 与证据组织
  consumers/
    knowledge_consumer.py            # Kafka 命令消费、取消检查与结果回报
  providers/                         # 扩展 Embedding/Rerank 抽象及适配
    ark_embedding.py                 # doubao-embedding-vision 官方能力适配
    langchain_embeddings.py          # DoubaoVisionEmbeddings 桥接
    langchain_generation.py          # 既有 ChatStreamProvider 的流式 Runnable 桥接
  infra/
    knowledge_client.py              # Java 内部客户端
    vector_store.py                  # QdrantVectorStore 与受控原生客户端适配
    artifact_store.py                # 短期对象票据访问
~~~

Java 遵循 Controller/Service/Mapper 分层，聚合和状态竞争 SQL 放 Mapper XML，跨业务域通过 Service；Python 路由不直接操作 Provider 或向量库。注释采用项目中文规范。

Python 3.14 的依赖兼容性必须先验证：解析器、Tokenizer、中文分词、向量 SDK 及其二进制依赖均需在目标 Linux 镜像内安装和最小运行。存在兼容问题时形成明确版本方案；本次文档不授权擅自降级项目运行时或安装依赖。

### 15.1 LangChain 依赖方案

| 依赖 | 状态与用途 |
|---|---|
| langchain-core | 已有；复用 Document、Embeddings、BaseRetriever、消息、Prompt 和 Runnable/LCEL |
| langchain-openai | 已有；复用已验证的 ChatOpenAI 聊天 Provider |
| langchain-qdrant | 新增目标；QdrantVectorStore 与 SparseEmbeddings 接口 |
| langchain-text-splitters | 后续按 LangChain 适配需求评估；当前稳定切片器直接按快照和 tiktoken 执行 |
| `pypdf` / `python-docx` / `Pillow` | 已直接引入；实现 PDF、DOCX 文本与图片提取、图片安全解码及统一 PNG 重编码 |
| `openpyxl` / `python-pptx` | 已直接引入；实现 XLSX、PPTX 的结构化文本解析，以及 Excel/PowerPoint 内嵌图片提取 |
| `tiktoken` | 已直接引入；为 `cl100k-base-v1` 提供可复现 Token 边界 |
| qdrant-client | 新增直接依赖目标；索引生命周期、查询诊断及需要时的原生异步调用 |
| `arkruntime` | 已锁定 0.6.0；首个方舟多模态 Embedding 原生异步适配器 |
| 中文分词/BM25 实现 | M0 选择并冻结；示例 FastEmbedSparse 不自动代表中文质量已满足 |
| langchain 主包 | 不作为“用了框架”的判定；所需组件来自独立包，有实际 API 需求时再引入 |
| langgraph | 已声明为直接依赖并通过 `StateGraph` 异步兼容测试；具体业务图、通用持久化 Agent 和 checkpoint 尚未实现 |
| langchain-community / langchain-classic | 不为复制旧 RAG 示例而整体引入；确需解析集成时单独评估 |

本版使用 LangChain 1.x 体系下的模块化组件和 Runnable/LCEL。M0 已在 Windows/Python 3.14 锁定并验证解析依赖；项目已直接引入 `pypdf 6.18.1`、`python-docx 1.2.0`、`Pillow 12.3.x`、`tiktoken 0.14.x` 和 `arkruntime 0.6.0`。切片边界使用显式 `cl100k-base-v1` revision；它是当前可复现的本地切片编码规则，不冒充任一向量模型的官方 tokenizer。目标 Linux 环境、真实 Embedding、视觉模型和 Qdrant 仍需单独验证。

框架集成与并发/流式行为以当前官方参考及锁定版本代码为准；禁止为形式统一同时维护两套生成模型客户端、两套检索状态或两个用量累计器。

### 15.2 Windows / Python 3.14 验证基线

M0 隔离探针已经在 Windows、Python 3.14 环境验证下列候选组合。该结果用于证明依赖可安装及关键 API 可运行，不等同于生产版本批准，也未覆盖 Linux、真实 Qdrant 服务端、业务数据库或付费模型。

| 依赖 | 已验证候选版本 | 验证范围 |
|---|---|---|
| `langchain-core` / `langchain-openai` | 1.6.1 / 1.6.0 | Prompt、Runnable、流式输出及等待任务取消 |
| `langchain-qdrant` / `qdrant-client` | 1.1.0 / 1.19.0 | 内存集合、命名向量、Dense/Sparse/Hybrid、metadata 过滤和原生异步客户端 |
| `langchain-text-splitters` | 1.1.2 | 中文字符切分及 `Document.metadata` 保留 |
| `volcengine-python-sdk[ark]` | 5.0.49 | `AsyncArk` 请求序列化及 `data.embedding` 单向量响应解析 |
| `jieba` | 0.42.1 | 中文分词可运行；首次导入可能出现上游正则转义警告 |
| `pypdf` / `python-docx` / `Pillow` | 6.18.1 / 1.2.0 / 12.3.x | 合成 PDF、DOCX、Markdown 的文本/图片提取，扫描页识别入口与 PNG 安全重编码 |
| `openpyxl` / `python-pptx` | 3.1.5 / 1.0.2 | 合成 XLSX、PPTX 的表格/幻灯片解析、来源定位，以及工作表/幻灯片图片提取 |
| `tiktoken` | 0.14.0 | `cl100k_base` 加载、Unicode 边界映射、Token 计数与重叠切片 |
| `numpy` / `grpcio` / `lxml` | 2.5.3 / 1.84.0 / 6.1.3 | Windows Python 3.14 二进制依赖可安装并参与探针 |

实施时必须继续遵守以下约束：

1. 锁定版本的 `QdrantVectorStore` 异步检索可能通过 executor 调用同步实现；需要有界执行器，要求原生网络取消时由 Retriever 内部使用受控 `AsyncQdrantClient`。
2. `AsyncQdrantClient` 不支持 `async with`，必须由组合根显式 `await close()`；关闭 LCEL 外层流也不能证明底层网络任务已停止。
3. Ark 多模态向量响应中的 `data.embedding` 是单向量；不能把一次请求内的多个模态项当作多个独立样本，初始实现按一个文档片段一次调用并保持有界并发和顺序。
4. Ark SDK 与当前聊天 SDK 使用的 HTTP 客户端代际不同，分别管理生命周期，不混传客户端实例。
5. 固定向量探针只验证过滤和 SDK 兼容性，不能替代真实豆包 Embedding、中文 BM25、两路独立召回及一次 RRF 的质量验证。
6. `jieba` 可运行不代表 BM25 方案已经冻结；词典 revision、稳定词项 ID、编号保护、IDF 语料范围和增量行为仍需评测。
7. 当前已冻结 `tokenizerRevision=cl100k-base-v1` 并用于切片预算；它不等于豆包官方 tokenizer，仍需按真实模型 API 验证输入上限。
8. 合成 PDF 和图片样例只验证解析、定位、OCR 路由与视觉向量写入，不代表复杂中文 PDF、表格或扫描件质量；必须使用脱敏真实资料集验收。
9. 真实 `doubao-embedding-vision` 的 Endpoint、维度、输入模式、上限和计费字段，以及 Qdrant 服务端的过滤、IDF、超时和取消，仍需在目标环境联调。

历史隔离探针已于 2026-11-11 随本地测试代码清理，不参与生产运行；项目当前完成度和历史验证结果只在 [项目开发进度](项目开发进度.md) 维护。

## 16. 测试与验收

本节定义最终统一验收标准，不表示当前立即执行。剩余入库治理、检索、Rerank 和聊天引用闭环完成前，只运行不依赖真实外部服务的开发验证；全部计划功能完成后再集中执行真实环境、质量、性能与故障验收。

### 16.1 契约与故障测试

| 测试 | 通过标准 |
|---|---|
| 签名与重放 | 错误路径、方法、body、过期时间、重复 nonce 均被拒绝；跨实例不可重复接受 |
| 空权限范围 | 不发生无过滤检索，不向外部模型发送受限片段 |
| 混合检索过滤 | Dense、BM25、扩展与相邻片段使用同一授权范围 |
| 重复与乱序消息 | 同一批次重复消息幂等，旧序列、旧 generation 和错误执行所有者被拒绝 |
| 结果重复投递 | 相同序列和摘要重复消费无副作用，冲突摘要被拒绝 |
| Worker 崩溃 | command 不被提前确认，租约扫描可创建新批次；孤立产物可清理 |
| 新版解析失败 | 旧版本继续可检索，不改为全库不可用 |
| 索引切换/回滚 | 请求快照版本一致；回滚不恢复已删除内容 |
| 撤权/停用/删除 | 后续模型发送、完成保存和来源访问被阻止；已发送字节的限制有明确行为 |
| 向量写入部分失败 | 不发布半成品，不标 READY |
| 维度/编码不一致 | 拒绝写入/查询，禁止混用 |
| 模型超时与取消 | 子任务关闭、费用按已发生计量、至多一个终态 |
| 无证据/故障 | 两类结果可区分，故障不能伪装无答案 |
| 引用 | 不接受不存在 citationId，不把旧引用指向新文件 |
| 上下文 | 问题+历史+证据+输出预算不超过模型及账户约束 |
| 普通聊天回归 | OFF 模式保留原请求行为，不要求知识库或向量库可用 |
| LangChain Embeddings | 文档输入输出数量与顺序一致，query/document 策略正确，异步路径不阻塞事件循环 |
| 框架 metadata | Document→Qdrant→Retriever→Citation 完整保留来源，嵌套过滤路径正确 |
| 请求隔离 | 并发用户不共享可变 filter、Profile、历史或预算；Runnable callback 不泄露正文 |
| RRF 唯一性 | 不重复融合 HYBRID 结果；生产与检索测试候选及排序一致 |
| LCEL 流式与用量 | 首个 delta 不等待全回答；取消传播，usage 不丢失且不重复结算 |
| 框架边界 | 路由/跨服务 DTO 不暴露 Document/AIMessage，异常归一化为项目错误码 |

### 16.2 质量评测

建立至少 50～100 个脱敏真实问题，覆盖精确编号、同义表述、多轮指代、跨段落、表格、版本冲突、无答案和权限隔离。标注正确文档版本、相关片段、参考答案关键事实和允许用户范围。

使用相同资料、Embedding 和生成模型，对比：
- Dense 基线；
- Dense + BM25 + RRF；
- 混合检索 + Rerank；
- 查询改写/扩展作为后续独立实验项，不进入首版运行契约。

初始产品验收目标（待 M0 固化评测集与负载，非当前已达成结果）：
- 可回答样本的证据 Recall@10 >= 90%；
- 抽检答案关键事实受证据支持比例 >= 95%；
- 无答案样本正确说明依据不足比例 >= 90%；
- 引用标识/版本/位置结构有效率 100%；
- 权限隔离与未发布内容泄露测试失败数为 0。

同时记录 MRR/nDCG、端到端正确率、引用语义支持率、检索/重排/首字 P50/P95、超时率和每问费用。小样本比例需报告分子/分母和失败案例，不把百分比当作统计保证。

延迟 SLO、并发数、文件规模和每日入库量必须在 M0 以目标机器及所选 API 的实测值确定；未固定这些条件不能宣布性能验收通过。

## 17. 开发里程碑与交付物

| 阶段 | 工作内容 | 出口条件 |
|---|---|---|
| M0：基线与契约 | 核对实际 SQL/部署、冻结 Schema、验证 Qdrant + doubao-embedding-vision 的实际版本/维度/请求语义，定义知识库级 Rerank、Chat Profile 和依赖版本，确定评测集与容量/SLO | Java/Python 契约样例一致，目标镜像依赖验证通过 |
| M1：入库与发布 | 数据迁移、Kafka Worker、解析切片、Embedding 协议适配器、双向量写入、产物清单、成员发布、清理 | 文件可入库、可重试、模型协议可按快照路由、旧版不断服、半成品不可检索 |
| M2：检索测试 | LangChain Runnable 链、授权 Retriever、QdrantVectorStore、中文 BM25、Dense、RRF、重排、有效性复核、内部同链诊断 | 主链路及权限测试通过，可定位各阶段结果 |
| M3：聊天闭环 | ChatPromptTemplate、现有 Provider 流式桥接、RAG 请求、上下文预算、SSE 引用、消息保存、用量和用户端预览 | 刷新可恢复引用，失败/取消/无依据行为正确 |
| M4：上线准备 | 质量评测、并发/故障测试、备份恢复、部署说明、功能开关 | 达到约定指标，文档与实现同步，回滚演练通过 |
| M5：增强 | 复杂版面/表格专项识别、更多 OCR 引擎、Query Expansion、业务接口路由；需要 Agent 多步执行时接入 LangGraph | 各能力独立评测通过后启用 |

每阶段提供代码、相关 SQL、契约与测试、配置说明和验证记录。生产首发为 M1～M4 完整闭环；M5 不阻塞基础文本 RAG 交付。

截至 2026-09-18，M3 代码与离线测试已经完成，本机开发 MySQL 的当前增量迁移已经执行并完成结构校验；真实模型、Qdrant、Kafka、Redis、OSS、目标环境数据库验收及 M4 仍在第 6 项继续执行。

## 18. 部署、观察与上线操作

在实际已有服务器部署方式上新增 Qdrant 与独立 Worker，复用现有 Java、Python、MySQL、Redis、OSS；本地源码未证明远程已具备这些新增组件。

上线步骤：

1. 备份业务数据库、原文件及相关部署配置。
2. 执行兼容性增量迁移，部署具备新契约的 Java/Python，RAG 默认关闭。
3. 部署 Qdrant 持久化存储和 Worker；内部接口、Qdrant 不开放给浏览器。
4. 配置已批准 Embedding/Rerank Profile、向量连接、服务签名、配额及保留期。
5. 使用限定知识库构建索引，完成权限、引用、故障与实际资料评测。
6. 开启目标知识库的 RAG，观察阶段延迟、调用失败和费用。
7. 出现问题时关闭 RAG 或回滚到完整历史索引；普通聊天继续工作。不得用自动无权限回退掩盖故障。

备份覆盖 MySQL 发布映射、Qdrant 数据及 OSS 不可变文件/清单。恢复后先校验三者引用和索引成员一致性，未通过不得开放检索。

日志只记录 requestId、jobId、attemptId、Profile revision、indexVersion、阶段耗时、数量、错误码和费用标识，不打印用户问题全文、文档正文、模型密钥、下载票据。可调试的正文查看通过有权限的产品页面完成。

## 19. 完成定义

只有同时满足以下条件，才能将本版 RAG 标记为完成：

- [ ] 真实文件完成“上传→解析→双索引→验收→发布”。
- [ ] 新版失败不影响旧版，半成品及旧执行批次不可进入检索。
- [ ] 每条检索路径有强制权限与版本约束。
- [ ] LangChain Document/Embeddings/Retriever/Prompt/Runnable 实际参与 RAG，不只是声明依赖。
- [ ] 混合检索、RRF、重排、证据筛选走同一生产与调试实现。
- [ ] 用户可获得有依据回答、部分依据答复、澄清和无依据答复。
- [ ] 引用可保存、刷新恢复、定位精确版本，访问时重新鉴权。
- [ ] Token/费用覆盖 RAG 额外调用，并发和重复结果不重复扣款。
- [ ] 取消、租约过期、进程重启、网络中断和清理任务测试通过。
- [ ] 普通聊天及既有知识库管理功能回归通过。
- [ ] 质量评测及已约定的性能/容量指标通过。
- [ ] 已交付可执行迁移、锁定依赖、部署与恢复说明，未执行事项如实记录。

## 20. 设计依据

- [LangChain Qdrant 集成](https://docs.langchain.com/oss/python/integrations/vectorstores/qdrant)：QdrantVectorStore、Dense/Sparse/Hybrid 与 SparseEmbeddings 适配。
- [LangChain Embeddings 接口](https://reference.langchain.com/python/langchain-core/embeddings/embeddings/Embeddings)：问题及文档的同步/异步向量接口。
- [LangGraph 概览](https://docs.langchain.com/oss/python/langgraph/overview)：后续 Agent 编排与 LangChain 组件的组合关系。

- 项目 [知识库架构](知识库架构与管理接口设计.md)：已有领域模型、上传/版本与资源权限设计；任务通道以本文第 7 节和机器契约 `main-service/docs/contracts/rag-v1/kafka.json` 为准，启用与排障见 [部署运维速查](部署运维速查.md)。
- 项目 [Java/Python 混合架构 V2](Java与Python混合架构.md)：业务控制面、AI 执行面、单租户及当前聊天链路。
- 项目 [聊天上下文设计](聊天上下文与会话设计.md)与[Token 配额设计](Token额度管理设计.md)：实现时同步核对上下文及用量边界。
- [Qdrant Hybrid Queries](https://qdrant.tech/documentation/search/hybrid-queries/)：混合检索与 RRF 能力。
- [Cohere Rerank v2 API](https://docs.cohere.com/reference/rerank)：Cohere 兼容文本 Rerank 协议的请求、响应、认证和输入上限依据。
- [阿里云百炼文本排序 API](https://help.aliyun.com/zh/model-studio/text-rerank-api)：qwen3.7-text-rerank、qwen3-rerank 等模型的端点、请求结构、响应和输入上限依据。
- [Qdrant Points](https://qdrant.tech/documentation/manage-data/points/)：点标识与技术产物存储能力。
- [字节 Seed 官方模型介绍](https://research.doubao.com/en/blog/built-on-seed1-6-flash-seed-1-6-embedding-launched)：doubao-embedding-vision 系列的文本/视觉向量能力及具体版本维度说明。
- [方舟多模态向量化 API](https://www.volcengine.com/docs/82379/1523520)与[官方 Python SDK](https://github.com/volcengine/volcengine-python-sdk)：实施时核对模型版本、请求、响应和异步适配。

外部文档仅证明产品能力，不代表本项目已完成集成、所选版本已兼容或目标环境已部署。
