# MCP 接入与验收

> 2026-09-30 更新。本文件合并原有接入步骤、兼容性验证、普通聊天/RAG 实施及回归记录；现行设计见 [MCP 架构与运行时设计](MCP架构与运行时设计.md)。以下历史测试数量保留为当时证据，不能误读为当前最新运行结果。

## 已完成范围

1. 固定普通聊天、RAG、FIXED/AUTO、内部签名、SSE、引用和预算的接入前基线。
2. 用官方 Everything MCP Server 验证 Streamable HTTP、`tools/list`、`tools/call` 与 LangChain Agent 工具循环，并锁定依赖。
3. 在普通聊天和 RAG 请求中加入可选授权工具清单；Java 按角色授权、普通聊天白名单或 Agent 绑定及模型能力生成单次快照。
4. Python 复用 `AI_PROVIDER_CREDENTIALS` 解析凭证别名，只连接并调用授权的只读工具。
5. 普通聊天及 RAG 生成阶段共用 LangChain `create_agent`。无工具时保留原生成分支。
6. 补齐权限/Schema 失效、缺密钥、超时、预算与失败结算；完成自动化及本地测试 Server 验证。
7. 独立统一 HTTP→MCP 适配服务完成模拟 ERP 工具的本地验证；真实 ERP 尚未接入。

管理端账号、真实业务数据库、真实付费模型和实际第三方 MCP Server 的全链路人工验收**尚未完成**，不能用测试替身结果代替。

## 依赖与本地测试 Server

`ai-service/pyproject.toml` 使用 `langchain[mcp]>=1.4.0,<1.5.0`；2026-09-28 的 `uv.lock` 锁定 `langchain==1.4.2`、`fastmcp==4.0.10`、`mcp==2.2.0`、`langgraph==1.2.12`。本地验证环境是 Windows / Python 3.14.6 / Node.js 22.23.2。LangChain `MCPAdapter` 在此版本提示 beta，升级后应重新运行兼容性测试。

在独立终端启动官方 [Everything MCP Server](https://github.com/modelcontextprotocol/servers/blob/main/src/everything/README.md)：

```powershell
npx -y '@modelcontextprotocol/server-everything@2026.8.31' streamableHttp
```

确认监听 `http://127.0.0.1:3001/mcp`。测试用 `echo` 工具实际发现、调用并返回 `Echo: mcp-compatibility-check`。脚本模型通过 LangChain 发出工具调用，收到成功的 ToolMessage 后生成最终回复；此验证不消耗付费模型额度，也**不证明**实际模型供应商能主动选择工具。退出时 Ctrl+C 停止 Server。

## 统一 HTTP 适配服务：模拟 ERP 本地验收

`integration-mcp-service` 是独立 Python 工程，同一个 MCP Server 可以按固定业务动作逐步增加多个系统的工具。当前只实现 `demo_erp_get_order_status`，调用仓库内置的**模拟** ERP HTTP API；它不是公司的真实 ERP 接口。依赖记录在该工程的 `uv.lock`，测试使用 FastMCP 4.0.10、LangChain 1.4.2 和 Python 3.14.6。

在两个 PowerShell 终端分别运行（令牌仅供本机测试，实际部署需改为受控密钥）：

```powershell
cd D:\project\mjn-ai\integration-mcp-service
uv sync --locked --extra dev
$env:ERP_DEMO_TOKEN = 'local-only-erp-token'
uv run --locked --extra dev python -m integration_mcp_service.mock_erp
```

```powershell
cd D:\project\mjn-ai\integration-mcp-service
$env:INTEGRATION_MCP_TOKEN = 'local-only-mcp-token'
$env:INTEGRATION_MCP_ENABLE_DEMO_ERP = 'true'
$env:ERP_DEMO_BASE_URL = 'http://127.0.0.1:8766'
$env:ERP_DEMO_TOKEN = 'local-only-erp-token'
uv run --locked --extra dev python -m integration_mcp_service
```

MCP 地址为 `http://127.0.0.1:8765/mcp`。入站 MCP Bearer 与适配服务访问模拟 ERP 的 Bearer 分开配置；服务默认只监听本机，且默认**不启用**模拟工具。真实部署必须取得真实接口文档，另行配置其认证与访问范围，不得把演示地址当作生产 ERP。

在管理端新增 Streamable HTTP Server，编码可用 `company-http-adapter-local`，Endpoint 填上述 MCP 地址，协议版本填本次 SDK 的 `2026-07-28`，认证选择 `BEARER`，凭证别名填 `mcp.company-http-local`。在已有 `ai-service` 的 `AI_PROVIDER_CREDENTIALS` JSON 中配置同名别名，值为本地 MCP 令牌；修改后重启 `ai-service`。该别名是连接 MCP 入口的令牌，**不是** ERP 下游令牌。

在第三个 PowerShell 终端获取实时 `tools/list` Manifest，再粘贴到该 Server 的“导入工具”窗口：

```powershell
cd D:\project\mjn-ai\integration-mcp-service
$env:INTEGRATION_MCP_TOKEN = 'local-only-mcp-token'
$exportManifest = @"
import asyncio
import json
import os
from fastmcp import Client
from fastmcp.client.transports import StreamableHttpTransport

async def main():
    transport = StreamableHttpTransport(
        "http://127.0.0.1:8765/mcp",
        headers={"Authorization": "Bearer " + os.environ["INTEGRATION_MCP_TOKEN"]},
    )
    async with Client(transport) as client:
        tools = await client.list_tools()
        print(json.dumps(
            {"tools": [tool.model_dump(by_alias=True, exclude_none=True) for tool in tools]},
            ensure_ascii=False,
        ))

asyncio.run(main())
"@
uv run --locked --extra dev python -c $exportManifest | Set-Clipboard
```

导入后检查工具名 `demo_erp_get_order_status`、输入字段 `order_no`、注解 `readOnlyHint=true` 且 `destructiveHint=false`；设为低风险、启用、免确认，并授予测试用户有效角色。普通聊天还需开启“允许普通聊天”；Agent 使用则绑定同一工具；模型需具备 `TOOL` 能力。测试提问：“请调用演示 ERP 工具查询 DEMO-1001 的订单状态”。模拟状态为 `SHIPPED`，工具结果只包含 `orderNo` 与 `status`，模拟接口附带的 `customerPhone` 必须被裁剪。以 `tools/call` 或适配服务日志判断是否真正执行，不能只凭模型回答判断。

已完成本地验证：锁定环境下 pytest 9 通过，Ruff 和 mypy 通过；真实 HTTP 下匿名 MCP 连接被拒绝，带令牌的 `tools/list`、`tools/call` 和 LangChain `MCPAdapter` 均成功；现有 `ai-service.authorized_mcp_tools` 使用 Java 样式授权快照后得到模拟订单状态。管理端账号、真实聊天模型和公司 ERP 的端到端验收仍未执行。取得 ERP 测试环境的请求方式、URL、认证、入参/返回样例和数据权限后，才新增真实只读业务工具并重新导入其准确 Manifest。

## 管理端配置与权限

- 建立 `STREAMABLE_HTTP` Server，Endpoint 使用实际可达地址；本地示例为 `http://127.0.0.1:3001/mcp`。
- `NONE` 无需密钥；`BEARER` 时 `credentialKey` 填别名，例如 `mcp.weather`，并在 `ai-service/.env` 的现有 `AI_PROVIDER_CREDENTIALS` JSON 中增加同名键。不要将令牌写入管理端、URL 或文档。
- 导入工具 Manifest，确认 Schema 和 `readOnlyHint=true`，管理员启用、设为低风险且免确认，然后授予角色。无 Agent 使用时，还需在工具策略中开启「允许普通聊天」；选择 Agent 使用时，则需将工具绑定 Agent。聊天模型还要声明 `TOOL` 能力。
- 当前仅执行 `NONE/BEARER` + Streamable HTTP；其他管理端可配置类型不会进入聊天执行清单。
- 权限或配置变更对**下一次**请求生效。执行时若远端工具已删除、Schema 改动或只读标记撤销，当前请求也会失败，不继续使用旧定义。

## 当前如何使用

Python 启动时显示的 `LangChainBetaWarning` 是 `langchain.mcp.MCPAdapter` 的 beta API 提示；Uvicorn 启动成功只证明 Python 服务就绪。只有聊天请求获得非空授权工具清单后，Python 才连接 MCP Server 并执行 `tools/list`、必要时执行 `tools/call`。

1. 先运行一个可从 Python 服务访问的 Streamable HTTP MCP Server。可按上节命令启动 Everything 测试 Server，地址为 `http://127.0.0.1:3001/mcp`。
2. 在管理端「MCP Server 管理」新增 Server。测试配置可使用编码 `everything-test`、类型「第三方」、传输「Streamable HTTP」、该服务地址、服务实际协议版本、认证「无需认证」、连接超时 30 秒、默认风险「低风险」、状态「启用」。只有已运行的服务地址可供聊天连接；保存 Server 本身不会建立连接。
3. 在该 Server 行点击「导入工具」，粘贴远端 `tools/list` 返回的完整 Manifest JSON（包括准确的 `inputSchema` 与 `annotations`）。这是手动导入，此时只更新工具目录，不会由 Java 自动连接服务。获得工具授权的聊天请求由 Python 连接服务，校验实时定义后才可能调用工具。优先选明确声明 `readOnlyHint: true` 且未声明破坏性的工具；未经服务端声明的工具即使导入，也不能进入当前聊天执行链。
4. 在「MCP 工具目录」检查导入的工具状态为「有效」，策略设为低风险、启用且无需确认；在「角色 MCP 工具权限」给测试用户的有效角色分配它；授权工具下拉框可按显示名、工具标识或 Server 名称搜索。无 Agent 测试时开启「允许普通聊天」；Agent 测试时在 Agent 编辑页「关联工具」勾选同一工具。
5. 在模型管理中选用真实支持工具调用、且能力包含「工具调用（TOOL）」的聊天模型。无 Agent 测试时直接使用该模型发问；Agent 测试时选择上述 Agent 和模型。示例提问：「请调用 echo 工具返回 mcp-hello」。模型决定是否实际调用；观察 MCP Server 的 `tools/call` 日志或跟踪，不能仅凭回答文字判断工具已执行。没有角色授权、场景白名单或 Agent 绑定、模型缺少 TOOL 能力时，聊天仍走原无工具分支。

如使用 Bearer，Server 的「凭证别名」填写 `mcp.weather` 一类别名，实际值放到 Python `AI_PROVIDER_CREDENTIALS` 的同名键中；修改 `.env` 后需重启 Python 服务。当前聊天执行不支持 STDIO、API_KEY 自定义请求头或 OAuth。工具 Manifest 的输入 Schema 和只读注解必须与运行时 `tools/list` 完全匹配，否则返回 `TOOL_REJECTED`。
## 可用于本地试验的开源 MCP（2026-09-29 实测）

以下是开源 Server 提供的公开托管实例。当前项目无需安装 Server 即可连接，但管理端仍需手动导入实时 `tools/list` 清单，并完成工具策略、角色与模型配置。公开实例的可用性由提供者维护；生产使用可按对应仓库自行部署。

| 用途 | MCP Endpoint | 认证 | 已验证的工具 |
| --- | --- | --- | --- |
| 天气、空气质量、地点定位 | `https://open-meteo.caseyjhand.com/mcp` | `NONE` | [Open-Meteo MCP](https://github.com/cyanheads/open-meteo-mcp-server) 的 11 个工具，包括 `openmeteo_search_locations`、`openmeteo_get_forecast` |
| 地理编码、周边地点、OSM 空间查询 | `https://openstreetmap.caseyjhand.com/mcp` | `NONE` | [OpenStreetMap MCP](https://github.com/cyanheads/openstreetmap-mcp-server) 的 6 个工具，包括 `openstreetmap_search_places`、`openstreetmap_query_nearby` |

使用项目已锁定的 FastMCP 客户端实测：两个地址均能完成初始化及 `tools/list`；17 个工具都带 `readOnlyHint=true`，输入 Schema 为对象且未超过项目上限。LangChain `MCPAdapter` 能加载 11/6 个工具，并分别调用天气预报、北京地点搜索获得非空结果。这验证了客户端兼容性，尚不等同于管理端账号和真实聊天模型的端到端验收。

在管理端分别新增 Server：传输选择 `STREAMABLE_HTTP`、认证选择 `NONE`，Endpoint 填上表地址。对每个 Server 执行下面的 PowerShell 命令，将最新的 `{"tools":[...]}` JSON 放入剪贴板，再粘贴到该 Server 的「导入工具」窗口；切换 Server 时修改 `$serverUrl`。不要把 MCP 客户端配置 `{"mcpServers":{...}}` 当成工具 Manifest 导入。

```powershell
$serverUrl = 'https://open-meteo.caseyjhand.com/mcp'
$pythonCode = @"
import asyncio
import json
import sys

from fastmcp import Client
from fastmcp.client.transports import StreamableHttpTransport

async def main():
    async with Client(StreamableHttpTransport(sys.argv[1]), timeout=15, init_timeout=15) as client:
        tools = await client.list_tools()
        payload = {"tools": [tool.model_dump(by_alias=True, exclude_none=True) for tool in tools]}
        print(json.dumps(payload, ensure_ascii=False))

asyncio.run(main())
"@
& 'D:\project\mjn-ai\ai-service\.venv\Scripts\python.exe' -c $pythonCode $serverUrl | Set-Clipboard
```

已保存 2026-09-29 的实测快照：[天气服务工具清单](MCP工具清单-天气服务-20260929.json)（11 个工具）和[地图服务工具清单](MCP工具清单-地图服务-20260929.json)（6 个工具）。可分别复制整份 JSON 粘贴到对应 Server 的「导入工具」窗口；服务端工具定义更新后，应按上面的命令重新获取实时清单，避免运行时 Schema 校验失败。

导入后只启用所需只读工具，设低风险、无需确认并授予角色；不选 Agent 聊天还需开启「允许普通聊天」，所选模型需声明 `TOOL` 能力。

智能选择（AUTO）在本次用户有可执行工具且存在已授权的 `TOOL` 模型时，只从这类模型中选择；若没有可用的 `TOOL` 模型，则保留普通聊天路由，工具不会下发。固定模型（FIXED）仍要求所选模型本身具备 `TOOL` 能力。

若天气工具已启用、允许普通聊天且角色已授权，但回复仍称无法调用工具，先在「模型管理」检查当前用户有权使用的聊天模型是否勾选「工具调用（TOOL）」。能力标签为空时，系统按通用模型处理，不会下发 MCP 工具；重启服务不会改变此配置。编辑模型并保存会生成新的发布修订，随后以同一授权角色重新发起聊天，核对实际选中模型和 MCP Server 的 `tools/call` 记录。若仍未调用，再核对登录用户的角色与授予工具的角色是否一致。

Open-Meteo 官方 [免费 API 条款](https://open-meteo.com/en/pricing)限非商业用途、每日 10,000 次请求且无可用性保证，商业用途需另行取得许可；OSM 的公共 [Nominatim 使用政策](https://operations.osmfoundation.org/policies/nominatim/)要求限速、可识别的 User-Agent 和署名，不能当成无限量地图服务。

[高德官方 MCP](https://developer.amap.com/api/mcp-server/gettingstarted) 的远程地址把 Key 放在 `?key=` 查询参数中，而当前 Java 会拒绝带查询参数的 Endpoint，因此不能直接填进管理端。[社区开源自建版](https://github.com/sugarforever/amap-mcp-server)支持 Streamable HTTP 和高德天气、路线、POI，但需要自己申请高德 Key，且当前源码的工具未声明 `readOnlyHint`；按现有执行规则，未经服务端补齐真实只读注解的工具不能进入聊天调用。高德的免费配额也须以账户控制台实际额度为准。

## 存量库迁移与默认值

先在现有业务数据库执行 [mcp-general-chat-tools-v1.sql](script/sql/mcp-general-chat-tools-v1.sql)，再部署更新后的 Java 服务与管理端。脚本可重复执行，新增字段对已有工具默认关闭，不会自动扩大权限；新导入工具也默认关闭。管理员只需在现有工具策略中逐项开启，已有角色权限仍生效。Python 服务和 Java→Python 工具契约无需改动。
## 普通聊天与 RAG 的行为

Java 将同一授权清单下发普通聊天 `PythonChatStreamRequest` 或 RAG `RagChatStreamRequest`。工具分支预占最多四轮模型预算，Python 对各轮实际用量求和并遵守 `maxTotalTokens`。SSE 只输出最终回答片段、累计用量和唯一终态；工具参数、工具结果与中间模型消息不会作为正文输出。工具分支在最终模型轮次完成后才发送正文，当前不逐 Token 透传。

RAG 先授权检索、选择上下文，再进行可选工具循环。无检索证据时工具可辅助回答，结果为 `NO_EVIDENCE` 且无知识库引用。已有候选但实际成功调用 MCP 工具、最终只依据工具回答时（例如天气查询），同样以 `NO_EVIDENCE` 和空引用列表完成，不展示无关知识库来源。出现引用时，只允许上下文扩展后第二次 Java 复核的候选标识，Python 校验最终引用为该集合的子集并复用其票据；未知引用继续拒绝，不再增加第三次完成复核。工具返回中的 `[C1]` 之类文本不构成知识库证据。

| 错误码 | 含义及排查 |
| --- | --- |
| `TOOL_REJECTED` | 授权、工具状态、实时 Schema、只读标记或调用限制不满足；检查角色/Agent/工具配置 |
| `TOOL_CREDENTIAL_MISSING` | `AI_PROVIDER_CREDENTIALS` 中缺少管理端指定的非空别名 |
| `TOOL_TIMEOUT` | MCP 连接、发现或调用超时；检查 Endpoint、网络和超时配置 |
| `BUDGET_EXCEEDED` | 模型多轮预算不足；检查 Token 额度与调用上限 |

验收天气类提问时，先在 MCP Server 日志确认地点搜索和预报工具实际调用，再检查聊天完成事件：纯工具回答应成功、无知识库引用；未调用工具而遗漏资料引用或编造 `[C99]` 的回答仍应返回 `CITATION_INVALID`。

失败事件使用稳定错误码，不向浏览器传认证头、带密钥 URL 或上游异常。若工具失败前已有模型用量，Java 按实际用量失败结算并释放未消耗的预占额；这与 MCP 工具调用次数本身无直接 Token 扣费不同。

## 可重复检查

在仓库根目录执行契约漂移及内部签名样例校验：

```powershell
& 'ai-service/.venv/Scripts/python.exe' main-service/docs/contracts/rag-v1/generate.py --check
& 'ai-service/.venv/Scripts/python.exe' main-service/docs/contracts/rag-v1/verify_signature_vectors.py
```

在 `ai-service` 执行 Python 回归。设置测试 URL 后，兼容性、普通聊天和签名 RAG HTTP/SSE 用例会实际连接本地 Everything Server；未设置时相关连接用例跳过，普通回归仍运行。

```powershell
cd D:\project\mjn-ai\ai-service
$env:AI_MCP_TEST_URL = 'http://127.0.0.1:3001/mcp'
& '.venv/Scripts/python.exe' -m pytest -q
& '.venv/Scripts/python.exe' -m ruff check src tests
& '.venv/Scripts/python.exe' -m mypy src
Remove-Item Env:AI_MCP_TEST_URL
```

在 `main-service` 执行 Java AI 核心模块回归：

```powershell
cd D:\project\mjn-ai\main-service
$env:JAVA_HOME = 'F:\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -pl ruoyi-modules/ruoyi-ai-core test
```

2026-09-28 接入前定向基线：Python **194 passed**；Java **163 tests，0 failures/errors**。步骤 2 的独立 LangChain/MCP 兼容性用例 **1 passed**。步骤 3～5 验证了 Bearer 别名、授权 `echo`、第四次调用拒绝、缺密钥和 Schema 变化。步骤 6 验证普通聊天两轮模型用量 `13 输入 + 5 输出 = 18`、SSE 唯一终态及超预算拒绝。步骤 7～9 的历史本地测试 Server 回归为 Python **299 passed**，Java AI 核心模块 **239 tests / 0 failures / 0 errors / 2 skipped**；当前 RAG 已收敛为两次候选复核并复用最终票据，最新验证数据以[项目开发进度](项目开发进度.md)为准。以上测试中的模型、Java 授权/检索接口使用受控替身；真实供应商与管理端到端仍待运行环境验证。

2026-09-29 普通聊天白名单变更：JDK 17 下 Java AI 核心模块回归 **240 tests / 0 failures / 0 errors / 2 skipped**；MCP 授权与聊天定向测试 **7 passed**。管理端 MCP 工具表单和类型文件单文件 ESLint 通过；全量 Vue 类型检查仍被其他既有文件错误阻断，本次 MCP 文件无新增类型错误。存量库迁移和真实管理端、MCP Server、模型端到端流程未在本次自动化中执行。

如修改跨服务契约、工具过滤、RAG 引用或结算，须重跑对应契约测试及两端回归；升级 LangChain/FastMCP/MCP 版本时还要重跑真实 Everything Server 兼容性用例。
