# RAG v1 机器契约与双端 DTO

本目录是 Java 控制面与 Python 执行面的内部数据契约。知识库任务使用 Java Outbox/Kafka → Python Worker → Kafka 结果回报。TXT、Markdown、PDF、DOCX、CSV、XLSX、PPTX 文本/图片解析、结构化切片、动态 Embedding、OCR/视觉理解、中文 BM25、Qdrant 文本/视觉 generation 写入、受控清单上传、Java ARTIFACTS_READY 验收、generation 清理/对账、REBUILD 编排及入库模型预算已完成代码和离线测试；真实模型、真实 Qdrant 和实际数据库/OSS 全链路尚未验收。统一内部签名用于申请 READ_SOURCE/WRITE_MANIFEST 单用途票据，Bearer 能力绑定当前 attempt、generation、worker、对象和租约。旧 HTTP claim/heartbeat/complete/failed 契约已经删除。授权混合检索、上下文选择、RAG 流式回答、引用闭环及查询/生成结算也已完成代码和离线测试。目标环境的真实外部服务与完整链路验收状态统一维护在项目开发进度文档。

当前完成情况见 [项目开发进度](../../项目开发进度.md)；Kafka 消息语义见 [RAG 开发设计第 7 节](../../RAG开发设计.md#7-kafka-任务幂等与取消)，启用与排障见 [部署运维速查](../../部署运维速查.md)。

## 文件与维护入口

| 文件 | 用途 |
|---|---|
| [openapi.json](openapi.json) | OpenAPI 3.1；7 条内部路由；对象票据、受控下载/上传、授权范围、候选复核、检索测试与 RAG 流式聊天均已实现 |
| [kafka.json](kafka.json) | 机器可读的 Topic、方向、消息 Schema、分区键与确认语义 |
| [schema.json](schema.json) | JSON Schema 2020-12；字段类型、camelCase、必填、可空、枚举、长度、范围及判别体 |
| [fixtures.json](fixtures.json) | 两端读取同一组合法与非法样例，包含默认值和完整往返校验 |
| [generate.py](generate.py) | 从 Pydantic 模型生成 Schema、OpenAPI 和 Java records；`--check` 检查漂移，不修改文件 |
| [java_rules.py](java_rules.py) | JSON Schema 无法表达的 Java 字段关联校验，与 Python 校验器共享反例 |
| [build_fixtures.py](build_fixtures.py) | 可审查的脱敏样例构造源，不使用真实任务或凭证 |
| [内部签名规范.md](内部签名规范.md) | Java/Python 统一字节规范；聊天、对象票据 POST 已接入，源文件 GET 与清单 PUT 使用对象级 Bearer 能力 |

模型维护源位于 `ai-service/src/ai_service/contracts/`：`rag_types.py`（基础/配置/计量）、`knowledge.py`（任务/产物票据）、`retrieval.py`（范围/候选/检索/分页）、`rag_artifacts.py`（产物清单）、`rag_chat.py`（RAG 聊天/事件）、`knowledge_kafka.py`（Kafka 调度协议）。Java 生成目录为 `main-service/ruoyi-modules/ruoyi-ai-core/src/main/java/org/ruoyi/domain/dto/knowledge/rag/`。不要手改生成的 Java 文件。

`RagContractJson` 是严格 DTO 编解码工具，拒绝隐式标量转换、未知字段、未知判别类型和尾随 JSON，并执行 Bean Validation。它已注册到 Spring，供 Java/Python RAG HTTP 边界统一使用；直接 `new` Java record 仍不会自动触发 Bean Validation。它不负责认证、授权、任务状态或数据库状态。

## 边界与线格式

- 新增 RAG 字段只接受 camelCase，禁止数值与字符串相互强转，新增模型拒绝未知字段。`additionalProperties` 未关闭的定义仅来自既有聊天协议，供旧协议对照；RAG 请求内嵌的聊天模型使用严格派生类型。
- 新 RAG 数据库 ID 是 1～19 位无前导零的正十进制字符串；Java 查库前仍需验证它是否为合法 Long 以及对象是否存在。修订号和计数是 JSON 整数。原生成模型的 `modelProfile.id` 仍为数值，不改变普通聊天协议。Java 为这些旧数值字段提供独立序列化器，避免全局大整数转字符串配置干扰。
- 字符串长度以 Unicode 码点计数；Java 使用项目已有 Hibernate Validator 的 `CodePointLength`，不使用 UTF-16 单元数量。
- 时间必须是带时区的 ISO 8601/RFC 3339 字符串；截止时间由 Java 计算。DTO 只校验格式，不根据测试执行日期判定有效期。
- 新字段没有隐含默认值；标为 nullable 的必填字段必须显式发送 `null`，不能直接省略。复用的聊天字段保留既有默认值。字节签名覆盖发送的完整原始请求，不可校验后重新序列化再验证签名。
- `https://contracts.mjn-ai.invalid/...` 是离线 Schema 标识，不是服务地址；OpenAPI 仅引用本地 `schema.json`，没有虚构服务器地址。测试中的模型、维度、URL、哈希、票据均为合成值，不构成真实豆包能力验证或发布配置。

## 请求组与判别结构

| 请求组 | 请求 → 响应 | 补充语义 |
|---|---|---|
| 对象票据 | ArtifactTicketRequest → ArtifactTicketResponse；清单上传返回 ArtifactUploadResponse | READ_SOURCE/WRITE_MANIFEST 只接受任务绑定对象；短期 URL 和 token 不得发给浏览器或日志 |
| 授权成员分页 | `ManifestPageRequest` → `ManifestPageResponse` | requestId 绑定原调用；snapshotId 位于路径；按固定 manifestRevision 完整分页 |
| 候选复核 | `ValidateCandidatesRequest` → `CandidateValidationResponse` | 精确来源元组及使用目的；空请求签发绑定空集合的票据，空允许集合是合法拒绝结果，不是允许全库 |
| 检索测试 | `RetrievalTestRequest` → `RetrievalTestResponse` | 返回受控片段、阶段分数/耗时/数量和实际用量；不调用回答模型 |
| RAG 聊天 | `RagChatStreamRequest` → SSE | `/internal/ai/v1/chat/rag/stream` 已实现；复用动态聊天模型 Provider，输出候选引用、流式正文、实际用量和最终引用 |

当前 Kafka command 已携带完整 IndexSnapshot 与 DocumentProcessingSnapshot；首次 attempt 从精确发布修订生成并持久化，重试复用同一 JSON。`collectionName` 固定为 `kb_<knowledgeId>_<indexVersionName>`，末段使用数据库 `knowledge_index_version.index_version` 的业务名称；`IndexSnapshot.indexVersion` 仍保存索引版本记录的数字主键字符串，供权限、成员和任务协议关联，不随 collection 命名改变。INGESTION Worker 已完成受控下载、文本/图片解析、结构化切片、OCR/视觉理解、动态 Embedding、中文 BM25、Qdrant 文本/视觉写入、清单上传和 ARTIFACTS_READY 回报。TEXT 与 MULTIMODAL 均已完成离线链路；INGESTION 共享 Embedding 与视觉/OCR Token 硬预算，REBUILD 由 Java 父任务拆分为复用同一入库链路的 INGESTION 子任务。CLEANUP 精确删除 generation，RECONCILE 只盘点并由 Java 以最新保留集合复核后下发清理。
管理员在 Java 上传、替换或重新解析文档时选择 `TEXT`/`MULTIMODAL` 处理模式以及已发布 Embedding、OCR/视觉 Profile；Java 为物理文档版本固化 `DocumentProcessingSnapshot`。Rerank 只由知识库级 `retrieval_config` 控制，并在查询开始时冻结当前发布模型修订；旧文档快照中的 Rerank 字段仅保留历史数据可读性，不再进入运行路由。Rerank 始终只在查询阶段运行，不参与入库 Worker。`IndexSnapshot` 使用 `dense_text`、可选 `dense_vision` 与 `sparse` 命名向量配置。

受控 HTTP 接口成功响应直接返回 DTO，不套 Java 的 `R` 包装。HTTP 非成功 body 为 `InternalError`；流已建立后使用 `RagFailedPayload`。HTTP 状态按错误原因选择，不能把所有业务失败统一伪装成 403。

## 授权与执行快照

`RagExecutionScope` 携带 `knowledgeScopes`、完整 `indexSnapshots`、检索配置引用及完整 `retrievalSnapshot`、上下文预算。Embedding 的向量空间定义位于每个索引快照中，文档版本另携带被选中的处理 Profile 快照；仅有 ProfileRef 不能执行，Python 不查业务库补齐配置。`KnowledgeScope.retrievalSettings` 是查询策略的唯一权威来源：Java 在请求范围中冻结 Dense/Sparse、RRF、候选数量、Rerank 开关与当前发布模型修订、返回数量和相关性阈值。未保存配置的旧知识库按 Dense-only 默认值执行。查询改写尚未实现，因此不进入 v1 运行契约。

Embedding 供应商不在机器契约中写死。文本与多模态模型均从 Java 模型管理选择，`providerCode/apiMode/modelName/apiHost/credentialKey`、dimensions、normalization、输入能力、模型发布版本和编码修订进入不可变快照；Python 先按 `providerCode + apiMode` 精确选择适配器，再按显式协议通配注册选择通用适配器；未知协议明确失败。视觉/OCR Profile 同样按快照路由。Rerank 来自模型管理，使用明确的 `providerCode/adapterCode` 发布快照；知识库启用 Rerank 时在查询开始冻结当前发布修订。单独变更 Rerank 或查询参数无需重建向量，也不创建新文档版本；调用失败会降级为已授权候选的 RRF 顺序。

Java 保存 snapshotId 与 requestId、用户、Agent、角色/部门权限、索引成员和过期时间的绑定。Python 回调只携带快照标识及原 requestId，不提供任意 userId 查询接口。`user_id` 仍只承担审计/筛选职责；不恢复创建者自动授权，也不新增 USER ACL。

分页响应 `nextCursor=null` 才表示结束。索引成员按 knowledgeId + indexVersion + generationId 成对约束；禁止将三个独立列表笛卡尔积组合。候选复核使用 knowledgeId/indexVersion/documentId/documentVersionId/generationId/chunkId/contentHash 完整元组。票据仅覆盖返回的允许集合和原使用目的；Java 仍需在模型上下文发送、完成保存与来源访问时复核当前权限和内容有效性。

## 关联校验与运行时职责

Schema 负责结构，以下关联规则由 Python `model_validator` 和 Java `isContractConsistent` 实现，Schema 以 `x-invariant-validator` 标记。共享反例中的 `schemaValid=true, valid=false` 专门验证这种差异：

- 索引 dense/sparse 名称不冲突，overlap 小于 chunkSize。
- 文本/表格区间严格递增；文本偏移为 Unicode 码点的左闭右开区间；电子表格行列坐标从 1 开始。
- DOCX 图片使用媒体序号定位，PPTX 图片必须携带幻灯片号，PDF 图片必须携带页号；XLSX 图片必须携带工作表名，并在存在单元格锚点时同时携带行列号。
- finalTopK 不超过 rerankTopK 和上下文片段上限。
- 检索引用与完整快照一致；启用重排时必须有对应配置，开发关闭重排模式必须明确。首版运行契约不包含查询改写。
- scopes 和 indexes 一一对应且不重复，一次请求同一库固定一个索引及 manifestRevision。
- 对象票据请求必须绑定当前 attempt、generation 和 worker；Java 再校验对象、操作与任务记录一致。
- 成功入库的 chunk/dense/sparse 数量相等且非零；存在 IMAGE 条目时必须同时携带匹配多模态快照的视觉向量维度和数量，不能以截断解析结果发布成功。
- ANSWERED/PARTIAL 必须有去重引用及复核票据；NO_EVIDENCE/NEEDS_CLARIFICATION 不携带伪引用。

文档解析运行时支持 `.txt/.md/.markdown/.pdf/.docx/.csv/.xlsx/.pptx`，不接受旧版 `.doc/.xls/.ppt`。CSV 支持 UTF-8 与 GB18030；XLSX 保留公式文本但不执行公式、宏或外链，内嵌栅格图片进入视觉/OCR 与多模态向量链路，Excel 图表暂不渲染为图片。

运行时代码已接入真实检索范围授权、发布状态与过期检查、查询/聊天阶段预算、候选复核、完成前引用检查、引用持久化及回访鉴权。DTO 通过仍不代表外部服务验收完成；root 账号规则、权限撤回、对象绑定、旧 attempt 拒绝、产物数量与维度、清理保留期及统一签名仍需在真实数据库、Redis、模型和 Qdrant 环境共同验证。

## 清单、哈希、SSE 与计量

`ArtifactManifest` 定义单文档单 attempt 的不可变对象清单；entries 有完整来源位置、pointId、片段顺序和 tokenCount。它是对象存储文件 Schema，不新增公开上传接口。源文件/清单的 SHA-256 作用于原始文件字节；`contentHash` 是切分后最终片段正文 UTF-8 字节的 SHA-256，哈希后不得再改变正文。

`resultHash` 是确定性业务结果的摘要，不能包含临时票据、requestId 或 sequence。后续实现以 RFC 8785 规范化 JSON 的 UTF-8 字节计算 SHA-256：成功结果对产物结果对象计算，失败结果对 `{errorCode, usageItems}` 计算；数组顺序保持不变。认证 v2 的 raw body 哈希和该幂等结果哈希用途不同，禁止混用。当前 Kafka 状态机已按事件字节摘要执行序列去重，不可变清单与产物验收已经实现；RFC 8785 规范化业务 resultHash 仍待单独实现。

SSE `delta/usage/keepalive` 保持既有含义；`citation` 只是候选引用映射，最终 `completed.citations` 才是实际使用集合。完整传输为 `event: <name>\ndata: <JSON>\n\n`，不是把事件名塞进 payload。一次运行最多一个 completed/failed 终态。Java 保存引用前仍须检查它是本次已复核候选的子集；浏览器不接收内部 validationTicket。

`executionUsage` 按 stage/callId/attemptId/Profile revision 计量并由 Java 去重，TOKENS、REQUESTS、DOCUMENT_PAIRS 分开表达；UNKNOWN 不用零冒充免费。兼容字段 `usage` 与 `executionUsage` 中的 CHAT 若指向同一调用，不得重复结算。解析/向量检索次数不计入生成模型 Token。入库阶段的 Embedding 与视觉/OCR 调用，以及查询阶段的 Embedding/Rerank 与 RAG 回答，均按实际或 UNKNOWN 用量回报；Java 按 callId/Profile 修订去重记账，分别结算查询与生成预占额度。

## 本地验证

在仓库根目录运行：

```powershell
& ai-service/.venv/Scripts/python.exe main-service/docs/contracts/rag-v1/generate.py --check
& ai-service/.venv/Scripts/python.exe main-service/docs/contracts/rag-v1/build_fixtures.py
& ai-service/.venv/Scripts/python.exe main-service/docs/contracts/rag-v1/verify_signature_vectors.py
```

在 `ai-service` 目录运行 `& .venv/Scripts/python.exe -m mypy src` 和 `& .venv/Scripts/ruff.exe check src scripts`。在 `main-service` 目录使用 JDK 17 运行：

```powershell
mvn -pl ruoyi-modules/ruoyi-ai-core -am package -DskipTests
```

Java/Python 测试源码已于 2026-11-11 按项目提交范围要求清理；以下数字是清理前留存的历史验证结果。契约漂移检查、静态检查和编译不能代替真实模型、服务端或数据库验收。

## 历史验证结果（2026-10-20）

- 7 条内部路由、79 个 Schema 模型和 74 个 Java record/判别接口类型已生成，生成物漂移检查通过；生成器额外输出 `java-types.json`，Java 共享样例测试只校验实际存在调用方的 DTO。
- 共享样例 146 个，覆盖 Kafka 快照、维护任务、执行预算、对象票据、文本/图片产物、PDF/DOCX/PPTX/XLSX/CSV 来源定位、授权检索、RAG 聊天事件、Unicode、时间、枚举、范围、引用和类型转换。
- Python 全量 336 项通过、5 项按外部环境条件跳过；Mypy 检查 64 个源文件通过，Ruff 对 `src/tests/scripts` 全量检查通过。
- 使用 JDK 17 强制 clean 编译依赖模块与 AI 核心；修正测试输入与生成类型清单后，Java AI 核心全量 254 项中 252 项通过、2 项真实 Kafka/MySQL 集成测试按环境开关跳过，0 失败、0 错误。
- 用户端和管理端生产构建均通过；构建输出仅保留项目既有的依赖提示和体积提示。
- 文本/图片入库与 RAG 检索/回答使用内存 Qdrant 和模拟模型/HTTP 完成离线验证；真实 Embedding、视觉、Rerank、聊天模型、Qdrant、MySQL、Redis、Kafka 和 OSS 全链路尚未验收。
- 本机 `ruoyi-ai` 开发库已执行多模态产物与 `rag-chat-v1.sql`；产物发布、生命周期/预算结构此前已存在。迁移后表、字段、索引和注释校验通过，目标部署环境尚未执行。
