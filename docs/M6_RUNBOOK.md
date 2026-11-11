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

## 4. T2 动态循环

Agent 与 Worker 由两个独立开关控制，默认均为 `false`：

```text
AGENT_ENABLED=false
AGENT_WORKER_ENABLED=false
```

启用实验时，Worker 每次只领取并推进一个 Run。策略只返回版本化枚举动作；application service 以
显式 `switch` 分发到现有 Retrieval、VideoContext Query 和 Answer service。ToolCall 在任何业务调用
前以 RUNNING 落库，完成后才原子提交 Step。Observation 只包含证据数量、窗口、答案状态和结果引用，
不保存检索原文、Prompt、模型原始响应或内部思考。

每次动作前重新检查 owner/READY Job、动作 Schema、step、deadline、输入/输出 Token、已知费用、
未知费用和答案次数。Answer 和 Retrieval 产生的既有 `model_call` 通过 `run_id` 归属 Run；预算事实
由 Store 聚合，不新建第二套调用账本。任一未知结果或无价格成功调用会使后续业务动作失败关闭；
不再产生 Tool 或模型调用的确定性拒答/澄清可以落库，但仍保留未知费用事实，不能显示为 0。

T2 聚焦验证在全新隔离数据库上执行：

```powershell
cd server
.\mvnw.cmd test-compile -Pintegration `
  "-Dit.test=DatabaseMigrationIT,JdbcAgentStoreIT,AnswerPersistenceAdapterIT,VideoIndexPersistenceAdapterIT" `
  "-Dvideopilot.context.asr.enabled=false" `
  "-Dvideopilot.context.ocr.enabled=false" `
  "-Dvideopilot.context.derivation.enabled=false" `
  "-Dvideopilot.evaluation.worker-enabled=false" `
  "-Dvideopilot.messaging.enabled=false" `
  failsafe:integration-test failsafe:verify
```

## 5. 故障与恢复

- 创建响应断开后，用原幂等键重试，不创建第二个逻辑 Run。
- lease 或 version 冲突时重新读取 MySQL，不直接改状态、不清表、不放宽 CAS。
- T1 只允许验证无费用生命周期；T2 测试中的 fixture 只验证数据库预算聚合，不作为 UI 演示数据。
- 过期租约下若存在 RUNNING ToolCall，Worker 标记 UNKNOWN 并终止 Run；不能假设调用未发生并自动重放。
- V20 是前进迁移。应用回滚时保留三张表和历史事实，切换到已验证兼容当前 schema 的构建；
  不执行 `flyway repair`、删库、降版本迁移或覆盖历史。
- T3 已以真实 MySQL 证明重复 Worker 只有一个 CAS 胜出、完成 Step 不重复；过期 RUNNING ToolCall
  变为 UNKNOWN 并稳定终止。应用重启和项目 Redis DB 清空后，Run 仍从 MySQL 读取。
- 页面只把最新 `runId` 放入 URL 查询参数作为无敏感信息的回放指针；刷新后仍调用 owner-scoped
  `GET /api/v1/agent-runs/{runId}` 读取 MySQL，不把浏览器状态作为第二真源。
- `AGENT_ENABLED`、`AGENT_WORKER_ENABLED` 和前端 `VITE_AGENT_ENABLED` 均默认 `false`；仅本地验收
  临时开启，Compose 和示例配置不改变默认关闭事实。

## 6. T3 本地验收

真实浏览器使用一条已完成 ASR/OCR/索引的 8 秒教学视频验证三条路径：真实 SiliconFlow Embedding
成功但价格不可得时，相关问题在首个安全 Step 后以 `UNKNOWN_COST` 停止；注入 Embedding 连接故障
后，短问题以 `NEEDS_CLARIFICATION` 终止，长且无关问题以 `NO_SUFFICIENT_EVIDENCE` 拒答。三条路径
都显示未知费用调用 1 次，没有伪造成本或测试数据。

浏览器刷新、应用重启和 Redis `FLUSHDB` 后，同一 URL 指向的 Run 轨迹仍可回放。桌面和 320px
视口均保留开关、预算、终止原因和折叠轨迹的语义结构；控制台只有 Vite debug 连接消息，没有
warning/error。Agent 创建、轮询与刷新读取均返回可渲染响应，没有页面级 4xx/5xx 或失败提示。
