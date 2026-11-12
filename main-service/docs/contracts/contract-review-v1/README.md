# 合同审查 v1 内部契约

权威定义位于 `ai-service/src/ai_service/contracts/contract_review.py`。`schema.json` 和 `fixtures.json` 由下列命令导出；Java 与 Python 的契约测试读取同一份样例。

```powershell
cd ai-service
$env:PYTHONPATH = 'src'
.venv/Scripts/python.exe scripts/export_contract_review_contract.py
.venv/Scripts/python.exe scripts/export_contract_review_contract.py --check
```

内部入口为 `POST /internal/ai/v1/contract-reviews/stream`，使用 `multipart/form-data`：`request` 是 JSON 文本字段，`file` 是 PDF/DOCX 文件字段。HMAC 对包含 boundary、文件和元数据的最终原始字节签名，沿用现有 Service Token、时间戳和 nonce。

`request.systemPrompt` 为可选字符串，可为 null，最长 20000 个 Unicode 字符。Java 从本次有权使用的 Agent 配置读取，浏览器创建任务不接收该字段。Python 仅把非空配置作为风险审查的业务要求拼入输入，保留固定系统约束，不将它传给合同事实提取节点；未配置时使用默认行为。该文本参与已有输入预算和计费，不新增模型调用。Java、Python 需同步部署新增字段；不新增数据库字段。

内部 SSE 事件为 `progress`、`usage`、`completed`、`failed`、`keepalive`。`usage` 包含请求标识和累计 `executionUsage`；成功和失败终态同样携带累计用量，成功时另带完整 `ContractReviewResult`。每次执行最多一个终态，等待期间定时保活，并在计量变化时上报快照。浏览器关闭不取消 Java 后台任务；内部连接断开时 Python 停止可取消执行并删除临时目录。

`executionPolicy.maxTotalTokens` 是 Java 预占账户后批准的执行预算，每次生成、查询向量化、重排前都检查。计量复用 RAG 的 `ExecutionUsageItem`，必须包含稳定 `callId`、本次 `attemptId`、已授权 Profile 和 Token 计量。供应商计量标记 `ACTUAL`；缺少 Token 时按实际文本估算并标记 `ESTIMATED`，不能伪装成实际 Token。Java 不将内部用量明细转发到浏览器。

浏览器只访问 Java：`POST /contract/reviews` 创建并接收进度；`GET /contract/reviews/{taskId}` 读取当前用户创建的任务。浏览器事件为 `accepted`、`progress`、`completed`、`failed`、`keepalive`。任务号以字符串传递，避免长整数精度丢失。

Java 使用强类型 DTO 和 `RagContractJson` 严格解码、Bean Validation、任务标识校验和条件终态更新；未知字段、缺失必要字段、非法风险等级、无效偏移和不一致汇总均不能保存为成功结果。Python 对原文引用做唯一匹配，风险位置由解析结果计算，知识引用仅能来自本次授权 RAG 结果。
