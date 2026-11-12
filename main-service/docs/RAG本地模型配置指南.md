# 本地 RAG 模型配置与联调说明

> 适用项目：D:\project\mjn-ai  
> 适用范围：Java 管理端、Python AI 服务、知识库入库及 RAG 对话  
> 配置原则：模型和供应商由 Java 模型管理统一发布；Python 只保存真实密钥，并按 Java 下发的模型快照动态选择适配器。

## 1. 首次联调需要哪些模型

先使用 TXT、Markdown、PDF、DOCX、CSV、XLSX 或 PPTX 跑通纯文本 RAG，最少只需要两个模型：

| 模型 | 是否必需 | 用途 |
| --- | --- | --- |
| Chat 模型 | 必需 | 根据检索到的知识片段生成最终回答 |
| 文本 Embedding 模型 | 必需 | 对文档片段和用户问题进行向量化 |
| 文本 Rerank 模型 | 首次可不配置 | 对 RRF 召回结果重新排序 |
| 视觉/OCR 模型 | 图片文档需要 | 提取图片文字、图表和结构描述 |
| 多模态 Embedding 模型 | 图片文档需要 | 生成图片向量，支持图文检索 |

首次联调建议：

- 复用已经能完成普通聊天的 Chat 模型。
- 新增一个支持文本输入的 Embedding 模型。
- 文档处理模式选择 TEXT。
- 在知识库「检索策略」中关闭 Rerank，先跑通不重排的 RAG。
- 暂时不配置视觉/OCR、多模态 Embedding 和多模态 Rerank。

当前 RAG 聊天未启用问题改写，因此不需要 Query Rewrite 模型。BM25 由 Python 本地生成稀疏向量，也不需要外部模型。

当前上传白名单为 `.txt/.md/.markdown/.pdf/.docx/.csv/.xlsx/.pptx`，旧版 `.doc/.xls/.ppt` 不支持。XLSX 使用 openpyxl 解析单元格，公式作为文本保留且不执行公式、宏或外链；MULTIMODAL 模式会提取工作表内嵌栅格图片并保留工作表/单元格锚点，Excel 图表暂不渲染为图片。PPTX 使用 python-pptx 解析。

## 2. 配置位置

| 配置内容 | 配置位置 | 说明 |
| --- | --- | --- |
| 厂商名称、编码、API Base URL | 模型管理 → 厂商管理 | Java 发布模型快照时读取 |
| 模型名称、分类、协议、维度和输入模态 | 模型管理 → 模型管理 | 不在 Python 代码中写死 |
| 真实 API Key 或火山 IAM AK/SK | ai-service/.env 的 AI_PROVIDER_CREDENTIALS | Java 只保存凭证别名；火山知识库 Rerank 使用 AK/SK 对象 |
| 文本和多模态 Embedding | 知识库详情 → 索引版本 | 随索引版本锁定 |
| 视觉/OCR | 知识库详情 → 文档与版本 → 上传/重新解析 | 随物理文档版本固化 |
| 查询时 Rerank | 知识库详情 → 检索策略 | 启用时按当前已发布模型修订执行；空配置按 Dense-only 运行，不读取文档版本 Rerank 配置 |
| Chat 模型权限 | 角色模型授权 | 当前用户必须拥有所选模型权限 |
| 知识库读取权限 | 知识库 ACL | RAG 检索范围由 Java 最终裁定 |

## 3. 配置模型厂商

进入“模型管理 → 厂商管理”。

使用火山方舟时：

| 字段 | 示例或要求 |
| --- | --- |
| 厂商名称 | 火山方舟 / 豆包 |
| 厂商编码 | doubao |
| API 地址 | 方舟控制台当前地域提供的 API Base URL |
| 状态 | 启用 |

providerCode 必须与 Python 已注册的适配器匹配。当前豆包多模态 Embedding 使用 doubao + ARK_MULTIMODAL_EMBEDDINGS 路由。

厂商管理中的 API 地址是新建模型时的默认值，不会再覆盖已有模型。实际执行地址在“模型管理 → 模型管理”的“请求地址”中按 Profile 保存并进入发布快照；同一厂商下不同协议可以分别填写各自的 Base URL。

## 4. 配置 Chat 模型

进入“模型管理 → 模型管理 → 新增”。如果普通聊天已经正常，可以直接复用现有 Chat 模型。

| 字段 | 配置要求 |
| --- | --- |
| 模型分类 | chat |
| 模型名称 | 实际模型名称或推理接入点标识 |
| 供应商 | 当前已经接通的供应商 |
| API 调用模式 | 优先使用 CHAT_COMPLETIONS |
| 密钥 Key | 例如 doubao-api-key |
| 最大上下文 Token | 按模型实际规格填写 |
| 最大输入 Token | 按模型实际规格填写 |
| 最大输出 Token | 必填，按模型实际规格填写 |
| 状态 | 启用 |
| 用户端可见 | 需要用户手动选择时开启 |

当前 Chat Provider 支持：

- doubao、deepseek、zhipu + CHAT_COMPLETIONS。
- doubao + RESPONSES；其他厂商的 RESPONSES 尚未完成适配验证。

配置后，需要在角色模型授权中把 Chat 模型授权给当前用户。

## 5. 配置 Embedding 模型

### 5.1 豆包多模态 Embedding

使用 doubao-embedding-vision 时，可以把一个 Vector Profile 同时声明为支持文本和图片：

| 字段 | 配置要求 |
| --- | --- |
| 模型分类 | vector |
| 模型名称 | 方舟控制台提供的模型标识或推理接入点 ID |
| 供应商 | doubao |
| API 调用模式 | ARK_MULTIMODAL_EMBEDDINGS |
| 输入模态 | 同时选择 TEXT、IMAGE |
| 模型维度 | 必须与推理接入点实际输出一致 |
| 向量归一化 | 按模型实际行为填写 |
| 最大输入 Token | 按模型实际限制填写 |
| 密钥 Key | 例如 doubao-api-key |
| 状态 | 启用 |

火山引擎文档显示 doubao-embedding-vision 可选择 1024 或 2048 维，默认是 2048 维。模型管理中的维度必须与实际 API 返回完全一致，否则 Python 会拒绝写入。

参考：[火山引擎 Doubao 多模态向量化模型](https://www.volcengine.com/docs/85637/2477596?lang=zh)

同一个支持 TEXT 和 IMAGE 的 Profile 可以同时作为索引版本的“文本 Embedding”和“多模态 Embedding”。模型名称应使用当前方舟账号实际可调用的模型或 Endpoint 标识。

### 5.2 OpenAI 兼容文本 Embedding

其他供应商提供标准 embeddings 接口时：

| 字段 | 配置要求 |
| --- | --- |
| 模型分类 | vector |
| API 调用模式 | OPENAI_EMBEDDINGS |
| 输入模态 | TEXT |
| 模型维度 | 与接口实际输出维度一致 |
| 最大输入 Token | 按模型实际限制填写 |
| 向量归一化 | 按模型实际行为填写 |

向量模型的“API 调用模式”是可输入字段，可以直接填写 OPENAI_EMBEDDINGS。

## 6. 配置 Python 模型凭证

真实 API Key 放在：

    D:\project\mjn-ai\ai-service\.env

示例：

    AI_PROVIDER_CREDENTIALS={"doubao-api-key":"替换为真实方舟 API Key"}

Java 模型管理中的“密钥 Key”必须与 JSON 中的键完全一致：

    Java credentialKey = doubao-api-key
    Python JSON Key    = doubao-api-key

不要把真实密钥填写到 Java 模型表、设计文档或提交到 Git。修改 .env 后需要重启 Python 服务。

配置模板：

    D:\project\mjn-ai\ai-service\.env.example

## 7. 创建并激活索引版本

进入“知识库详情 → 索引版本 → 新建索引版本”。

首次纯文本联调：

| 字段 | 示例或要求 |
| --- | --- |
| 索引版本 | v1 或其他唯一版本号 |
| 文本 Embedding | 选择已发布且支持 TEXT 的 Vector Profile |
| 多模态 Embedding | 纯文本联调暂不选择 |
| 切分策略 | {"chunkSize":800,"overlap":100} |

保存后激活该索引版本。

Embedding 模型、向量维度或切分策略变化时，应新建索引版本并执行重建，不能直接修改已发布向量空间后继续写入旧索引。

## 8. 上传并解析纯文本文档

进入“知识库详情 → 文档与版本 → 上传文档”。

首次联调：

| 字段 | 配置值 |
| --- | --- |
| 目标索引版本 | 刚创建并激活的版本 |
| 处理模式 | TEXT |
| 文本 Embedding | 随索引版本自动锁定 |
| 知识库检索策略 | 关闭 Rerank |
| 自动解析 | 开启 |

首次联调仍建议上传内容较短、编码明确的 TXT 或 Markdown 文件。CSV、XLSX、PPTX、DOCX 和 PDF 可在基础链路稳定后逐类验证；任务状态变为 SUCCEEDED，文档和 generation 发布成功后，才能用于 RAG 检索。

## 9. 配置文本 Rerank

模型管理中的“适配器编码”已经改为下拉框，只展示当前 Python 执行端真实注册的协议。当前支持以下三种文本重排序适配器：

| 供应商/协议 | 适配器编码 | 适用模型 | API 地址 |
| --- | --- | --- | --- |
| 阿里云百炼 | `DASHSCOPE_TEXT_RERANK` | `qwen3.7-text-rerank`、`qwen3-rerank`、`gte-rerank-v2`；`qwen3-vl-rerank` 当前只按文本输入使用 | 百炼业务空间域名，例如 `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com` |
| Cohere v2 兼容服务 | `COHERE_RERANK_V2` | 服务端声明兼容 Cohere `/v2/rerank` 的文本模型 | 纯域名、以 `/v2` 结尾的地址或完整 `/v2/rerank` 地址 |
| 火山引擎知识库重排 | `VOLCENGINE_KNOWLEDGE_RERANK` | `doubao-seed-rerank`（当前仅文本输入） | `https://api-knowledgebase.ml.cn-beijing.volces.com`；须用 IAM AK/SK 签名 |

配置阿里云百炼 `qwen3.7-text-rerank` 时填写：

| 字段 | 配置值 |
| --- | --- |
| 模型分类 | 重排序（`rerank`） |
| 模型名称 | `qwen3.7-text-rerank` |
| 供应商 | 阿里云百炼（`qianwen`） |
| 输入模态 | 文本（`TEXT`） |
| 适配器编码 | 下拉选择“阿里云百炼文本重排序”，保存值为 `DASHSCOPE_TEXT_RERANK` |
| 单文档最大 Token | `30000` |
| 请求地址 | 对应地域的百炼业务空间域名 |
| 密钥 Key | 对应 `AI_PROVIDER_CREDENTIALS` 中的别名，例如 `aliyun-api-key` |
| 状态 | 启用 |

`qwen3-rerank` 的单文档最大 Token 填 `4000`。Python 会根据模型名称在百炼原生排序端点和 `/compatible-api/v1/reranks` 端点之间选择正确协议。Java 保存时会校验供应商与适配器组合，未知编码或错误组合不能发布。
配置后，在知识库详情的「检索策略」中开启 Rerank、选择已发布的文本重排序模型并保存。新请求使用该策略；不需重新解析文档或重建向量。Rerank 只在查询命中文档后执行。

火山引擎 `VOLCENGINE_KNOWLEDGE_RERANK` 使用 IAM AK/SK，不能把聊天与 Embedding 使用的方舟 API Key 别名填入重排序模型。先在 Python `ai-service/.env` 的 `AI_PROVIDER_CREDENTIALS` 中添加对象，例如 `"doubao-rerank-aksk":{"accessKeyId":"<AK>","secretAccessKey":"<SK>"}`，重启 Python，再把模型「凭证别名」改为 `doubao-rerank-aksk` 并保存。AK/SK 不应写入管理端或文档。若尚无 AK/SK，可先在知识库「检索策略」关闭 Rerank，保留已激活索引和 Dense/Sparse 检索。

## 10. 图片文档扩展配置

处理包含图片的 DOCX、XLSX、PPTX、PDF 或 Markdown 时：

1. 索引版本绑定支持 IMAGE 的多模态 Embedding。
2. 文档处理模式选择 MULTIMODAL。
3. 文档绑定独立的视觉/OCR 模型。
4. 首次图片联调在知识库「检索策略」中关闭 Rerank。

视觉/OCR 模型要求：

| 字段 | 配置要求 |
| --- | --- |
| 模型分类 | image |
| 输入模态 | IMAGE |
| API 调用模式 | CHAT_COMPLETIONS 或 RESPONSES |
| 最大输入 Token | 按视觉模型限制填写 |
| 密钥 Key | 对应 Python 中的凭证别名 |

doubao-embedding-vision 只负责生成图片向量，不会替代视觉/OCR 模型。视觉/OCR 模型负责生成可检索、可引用的图片文字和内容描述。XLSX 内嵌栅格图片会进入视觉链路并生成 `dense_vision`；CSV 没有内嵌图片结构，Excel 图表当前不渲染为图片。

当前已定义多模态 Rerank 契约，但 Python 尚未注册真实的多模态 Rerank Provider。首次图片联调应在知识库「检索策略」中关闭 Rerank。

## 11. 本地启动前检查

模型配置完成后，还要确认：

- 部署前执行当前全部增量脚本，包括新增的 `knowledge-document-file-types-v1.sql`。
- Redis 正常运行，用于检索授权范围和短期状态。
- Kafka 正常运行，Java 能发送任务，Python Consumer 能消费。
- Qdrant 正常运行，Python 可以创建集合和写入向量。
- OSS 配置可用，Java 能签发受控源文件票据，Python 能读取源文件。
- Java 与 Python 的内部 Token 和签名密钥一致。
- AI_KAFKA_ENABLED=true，且两端 Topic 配置一致。
- 当前用户拥有 Chat 模型权限和知识库 READ 权限。
- 知识库存在已激活索引，文档解析任务状态为 SUCCEEDED。


## 12. 首次联调顺序

1. 确认已有 Chat 模型可以完成普通聊天。
2. 新增并启用文本 Embedding Profile。
3. 在 ai-service/.env 中配置真实 API Key。
4. 重启 Python 服务。
5. 新建并激活知识库索引版本。
6. 在知识库「检索策略」关闭 Rerank，上传一个 TXT 或 Markdown 文档并选择 TEXT 处理模式。
7. 等待 Kafka 入库任务成功，并确认 Qdrant 已写入当前 generation。
8. 给当前用户授予知识库读取权限。
9. 在聊天页面选择已授权的 Chat 模型和知识库进行提问。
10. 纯文本链路稳定后，再接入文本 Rerank、视觉/OCR 和多模态 Embedding。

## 13. 当前适配器清单

| 能力 | 路由条件 | 当前实现 |
| --- | --- | --- |
| Chat | doubao/deepseek/zhipu + CHAT_COMPLETIONS | LangChain ChatOpenAI |
| Chat | doubao + RESPONSES | OpenAI 异步 SDK |
| 文本 Embedding | 任意供应商 + OPENAI_EMBEDDINGS | OpenAI 兼容 embeddings |
| 文本/图片 Embedding | doubao + ARK_MULTIMODAL_EMBEDDINGS | 火山方舟多模态 Embedding SDK |
| 视觉/OCR | 任意供应商 + CHAT_COMPLETIONS/RESPONSES | OpenAI 兼容视觉协议 |
| 文本 Rerank | qianwen + DASHSCOPE_TEXT_RERANK；doubao + VOLCENGINE_KNOWLEDGE_RERANK；任意供应商 + COHERE_RERANK_V2 | 百炼 API Key；火山知识库 IAM AK/SK；Cohere v2 兼容协议 |
| 多模态 Rerank | 尚未注册 | 首次联调在知识库检索策略中关闭 Rerank |

新厂商兼容已有协议时，只需要增加厂商和模型配置；只有协议完全不同，才需要在 Python 新增 Provider 适配器并注册路由。
