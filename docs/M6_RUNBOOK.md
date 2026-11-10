# M6 实验 Agent 运行手册

M6 是默认关闭的本地实验能力。M5 效果门仍失败，非 Agent 回答保持默认路径；不得把本手册中的
Run 状态或局部测试解释为 M5/M6 已晋级。

## 1. T1 持久化边界

MySQL 的 `agent_run`、`agent_step` 和 `tool_call` 是唯一真源。Redis 不保存恢复所需事实；T1 没有
Worker、Tool 或模型调用。一个 Run 冻结 policy、Prompt、模型、Embedding、检索公式、证据门、
limits 版本和完整预算快照。API 只返回这些可复核事实、预算使用、去敏摘要和结果引用，不返回
ownerId、请求指纹、幂等键、Prompt、模型原始响应或内部思考。

V20 保证 ToolCall 可在 Step 完成前以 REQUESTED/RUNNING 落库。ToolCall 只外键归属 Run；
`step_number` 是将来提交 Step 时的关联序号，不能反向要求 Step 已存在。这样 T2 才能在任何外部
调用前先完成状态和幂等审计。

## 2. Owner API

- `POST /api/v1/analysis-jobs/{jobId}/agent-runs`：需要已认证 session、CSRF、`Idempotency-Key`，
  body 为 `{"goal":"..."}`；只接受当前 owner 的 READY Job，成功返回 `202 Accepted`。
- `GET /api/v1/agent-runs/{runId}`：只读取当前 owner 的 Run、Step 和 ToolCall 轨迹；跨 owner 与
  不存在 Run 使用相同的 404 语义。

相同 owner、幂等键和请求指纹返回原 Run；相同键但 Goal、执行版本或预算上限不同返回
`IDEMPOTENCY_KEY_REUSED`。deadline 是创建结果而不是用户请求身份，不参与指纹比较。

## 3. T1 验证

快速门：

```powershell
cd server
.\mvnw.cmd test
```

迁移和 Store 聚焦门必须使用全新隔离数据库，并让应用用户只获得该库权限。导入根目录 `.env`
到当前 PowerShell 进程，但不要打印变量值。然后执行：

```powershell
cd server
.\mvnw.cmd test-compile -Pintegration `
  "-Dit.test=DatabaseMigrationIT,JdbcAgentStoreIT" `
  "-Dvideopilot.context.asr.enabled=false" `
  "-Dvideopilot.context.ocr.enabled=false" `
  "-Dvideopilot.context.derivation.enabled=false" `
  "-Dvideopilot.evaluation.worker-enabled=false" `
  "-Dvideopilot.messaging.enabled=false" `
  failsafe:integration-test failsafe:verify
```

这些开关只收窄聚焦测试的 Spring 上下文，不关闭被测的 Flyway、MySQL、Agent Store 或 Web 合同。
验证后检查隔离库 `model_call` 数量仍为 0。

## 4. 故障与恢复

- 创建响应断开后，用原幂等键重试，不创建第二个逻辑 Run。
- lease 或 version 冲突时重新读取 MySQL，不直接改状态、不清表、不放宽 CAS。
- T1 只允许验证无费用生命周期；不得手工插入假 ToolCall 或伪造完成轨迹供 UI 展示。
- V20 是前进迁移。应用回滚时保留三张表和历史事实，切换到已验证兼容当前 schema 的构建；
  不执行 `flyway repair`、删库、降版本迁移或覆盖历史。
- 到 T2 后，UNKNOWN、高成本调用和进程重启必须遵循任务表中的调用前硬门；T1 不提前声称这些
  故障矩阵已经通过。
