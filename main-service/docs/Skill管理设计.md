# RuoYi AI Skill 管理设计方案

> 现行实现核对：2026-09-28。Java Skill 元数据与 Agent 绑定属于管理能力；Python 通用 Skill 执行尚未接入。本文的“第一阶段”描述的是该管理能力的交付范围，不代表普通聊天、RAG 或 MCP 仍未接入。

## 1. 目标与边界

Skill 是可由多个 Agent 复用的能力包，例如文档摘要、数据转换、报表生成或内部系统查询。

第一阶段 Java 仅负责 Skill 元数据、状态和 Agent 绑定关系的管理，直接读取当前配置，不执行任何 Skill。

本阶段完成：

- Skill 的新增、编辑、查询、启停和删除校验；
- Skill 的稳定编码、说明、运行时、入口、参数和资源限制配置；
- Agent 与 Skill 的多对多绑定；
- 配置变更审计复用系统操作日志；
- 为后续 Python 执行端提供当前有效配置。

本阶段不做：

- Java 不执行 Python 脚本、Shell 脚本、HTTP 调用或 MCP 调用；
- 不为 Skill 单独增加角色、部门或用户权限；
- Skill 管理链路不引入 Kafka；知识库后台任务 Kafka 不属于本文范围；
- 不模拟 Skill 执行结果。

## 2. 权限原则

Skill 是全局可复用资产，不单独做权限控制。

用户最终可使用的 Skill 为：

```text
当前用户可使用的 Skill
= 用户可使用的 Agent
∩ Agent 已绑定且启用的 Skill
∩ Skill 自身启用
```

因此：

- Agent ACL 决定员工是否可以进入、使用某个 Agent；
- Agent 绑定决定该 Agent 可以使用哪些 Skill；
- Skill 状态决定该 Skill 是否可被所有 Agent 使用；
- 不创建 `role_skill_permission`、`dept_skill_permission` 或 `user_skill_permission` 表。

这样不会出现同一个通用 Skill 被多个角色反复授权的维护问题。

## 3. 菜单与页面

```text
智能体管理
├─ Agent 管理
└─ Skill 管理
```

Skill 管理页面包含：

| 页面区域 | 内容 |
|---|---|
| 查询区 | Skill 名称、编码、运行时、状态 |
| 列表 | 编码、名称、说明、运行时、入口、状态、更新时间、被 Agent 绑定次数 |
| 编辑抽屉 | 基础信息、执行契约、资源限制、备注 |
| 操作 | 新增、编辑、启停、查看绑定 Agent、删除 |

Agent 编辑页只提供“关联 Skill”多选框，不在 Agent 页面重复维护 Skill 的具体定义。

## 4. Skill 主数据

当前项目已有 `skill` 表，可在此基础上规范字段。

| 字段 | 必填 | 说明 |
|---|---:|---|
| `skill_name` | 是 | 稳定唯一编码，如 `document-summary` |
| `display_name` | 建议 | 页面展示名称；若暂不拆列，可沿用 `skill_name` |
| `description` | 是 | 能力说明、适用场景和边界 |
| `runtime` | 是 | `PYTHON`、`HTTP`、`MCP`；第一阶段只保存 |
| `entrypoint` | 是 | Python 模块名、HTTP 路径或后续执行标识 |
| `artifact_oss_id` | 否 | Skill 制品的 `sys_oss.oss_id`；由 OSS 上传组件维护 |
| `digest` | 否 | 制品摘要，用于后续完整性校验 |
| `parameter_schema` | 否 | JSON Schema，约束输入参数 |
| `output_schema` | 否 | JSON Schema，约束输出结构 |
| `resource_limits` | 否 | JSON：超时、最大文件、最大并发等 |
| `allowed_tool_ids` | 否 | Skill 内可使用的 MCP 工具白名单；第一阶段仅保存 |
| `status` | 是 | `0` 启用、`1` 停用 |
| `remark` | 否 | 管理备注 |

建议编码规则：

```text
小写字母、数字、短横线；以字母开头。
示例：document-summary、excel-analysis、crm-customer-query
```

## 5. 运行时类型

### 5.1 `PYTHON`

后续 Python 服务内置或加载的能力。

```text
runtime: PYTHON
entrypoint: skills.document_summary:run
```

Java 只校验格式并保存；Python 后续负责实际导入、执行和超时控制。

### 5.2 `HTTP`

调用受控内部 HTTP 服务的能力。

```text
runtime: HTTP
entrypoint: POST /internal/skills/excel-analysis
```

实际请求、鉴权和重试由后续 Python 统一处理，不能由 Java 管理端直接调用。

### 5.3 `MCP`

由 MCP 工具组合形成的高层能力。

```text
runtime: MCP
entrypoint: mcp:weather-service:get_weather_forecast
```

MCP 工具本身仍需满足：

```text
Agent 已绑定工具 ∩ 角色工具权限 ∩ 工具启用状态
```

## 6. Agent 绑定

关联表使用现有 `agent_skill`：

```text
agent_id
skill_name
config_json
status
```

含义：

- `skill_name`：关联 Skill 的稳定编码；
- `config_json`：仅覆盖该 Agent 下的默认参数，不保存密钥；
- `status`：允许临时禁用某个 Agent 下的 Skill，而不影响其他 Agent；
- 一个 Skill 可绑定多个 Agent，一个 Agent 可绑定多个 Skill。

Agent 保存时应校验：

1. Skill 存在；
2. Skill 状态为启用；
3. 同一 Agent 不可重复绑定相同 Skill；
4. `config_json` 必须为 JSON 对象；
5. 若已定义 `parameter_schema`，覆盖配置应满足 Schema 的基础校验。

## 7. 状态与删除规则

| 操作 | 规则 |
|---|---|
| 启用 Skill | 运行时、入口、资源限制格式必须通过校验 |
| 停用 Skill | 所有已绑定 Agent 即刻不可再使用该 Skill |
| 删除 Skill | 未被任何 Agent 绑定时才允许删除 |
| 已被绑定 | 提示关联 Agent 名称，建议先停用或解除绑定 |
| 修改编码 | 不允许直接修改；需要新建 Skill 并迁移 Agent 绑定 |

第一阶段不需要发布按钮。Skill 管理端和 Agent 一样，直接读取当前表配置。

## 8. Java 接口建议

```text
GET    /agent/skill/list
GET    /agent/skill/{id}
POST   /agent/skill
PUT    /agent/skill
PUT    /agent/skill/{id}/status
DELETE /agent/skill/{ids}
GET    /agent/skill/{id}/agents
GET    /agent/agent/skillOptions
```

其中：

- `skillOptions` 仅返回启用的 Skill，供 Agent 编辑页选择；
- `agents` 用于删除前展示当前绑定情况；
- Java 不提供 `/skill/run` 或 `/skill/execute`。

## 9. 后续 Python 执行契约

后续 Python 获取到 Agent 配置后：

```text
1. 查询当前 Agent 已绑定且启用的 Skill；
2. 过滤已停用的全局 Skill；
3. 根据 runtime 与 entrypoint 定位执行器；
4. 校验输入是否符合 parameter_schema；
5. 应用 resource_limits；
6. 执行并校验 output_schema；
7. 记录执行明细、耗时、错误和 Token 消耗。
```

Java 管理端只保存并校验配置，Python 执行端负责真正执行、安全隔离、超时、重试和日志。

## 10. 第一阶段验收标准

1. 管理员可以维护全局 Skill，且 Skill 编码唯一。
2. 管理员可以在多个 Agent 中复用同一个 Skill。
3. Skill 不存在任何角色、部门、用户级授权页面或授权表。
4. 用户能否使用 Skill 仅由可用 Agent、Agent 绑定和 Skill 状态决定。
5. 停用 Skill 后，所有 Agent 不再将其作为可执行能力。
6. Java 端不会执行 Skill，也不会调用 Python、HTTP、MCP 或模型。
