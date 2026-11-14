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

T3 当时尚未冻结价格目录，真实浏览器使用一条已完成 ASR/OCR/索引的 8 秒教学视频验证三条路径：
真实 SiliconFlow Embedding 成功但价格不可得时，相关问题在首个安全 Step 后以 `UNKNOWN_COST` 停止；注入 Embedding 连接故障
后，短问题以 `NEEDS_CLARIFICATION` 终止，长且无关问题以 `NO_SUFFICIENT_EVIDENCE` 拒答。三条路径
都显示未知费用调用 1 次，没有伪造成本或测试数据。

浏览器刷新、应用重启和 Redis `FLUSHDB` 后，同一 URL 指向的 Run 轨迹仍可回放。桌面和 320px
视口均保留开关、预算、终止原因和折叠轨迹的语义结构；控制台只有 Vite debug 连接消息，没有
warning/error。Agent 创建、轮询与刷新读取均返回可渲染响应，没有页面级 4xx/5xx 或失败提示。

## 7. T4 同题 A/B 与价格

V21 在既有 `evaluation_run` 上增加 `BASELINE/AGENT` 执行模式和可空的 `agentPolicyVersion`，并把
AgentRun 关联到 evaluation run/case；没有新建第二套数据集、Runner 或报告。Agent 候选只接受与运行时
冻结版本完全相同的 `agent-policy-v1`。同一冻结 DEV 的基线与候选各至少运行三轮；TEST 和运行后才
定义的 challenge/复杂题子集不能用于晋级。

成本目录固定为 ADR-0016 的 `siliconflow-pricing-2026-11-11`：BGE-M3 成功调用为免费，
DeepSeek-V4-Flash 按调用开始时的北京时间和输入/输出 Token 估算 CNY。缓存 Token 不可核验时不套用
缓存折扣；任一成功调用价格未知时报告 `unavailablePriceCalls`，成本相关晋级规则失败关闭。

评测报告新增答案硬门通过率、答案 P95 延迟、每题平均成本和 Agent 终止路径分布。随机项仍按 series
输出至少三轮的均值和样本标准差。`GET /api/v1/evaluation-runs/comparison` 只比较同一数据集指纹下已
完成的 BASELINE 与 AGENT run；Java 确定性裁决 Roadmap 8.3 前两条。第三条在复杂题子集、显著收益
阈值和条件路由均未预冻结时固定为不可参与晋级。

即使前两条命中，候选仍必须同时达到 M5 的严格 Claim-Evidence 支持率 85% 和引用定位率 95%。
比较结果只给出候选资格，不修改 `AGENT_ENABLED`、`AGENT_WORKER_ENABLED` 或前端开关；默认启用仍需
负责人另行批准。

## 8. T5 自动目标分析结果

T5 复用同一个 Agent Worker 和 Answer 管线，不增加结果表。四个开关继续默认关闭：

```text
AGENT_ENABLED=false
AGENT_WORKER_ENABLED=false
AGENT_GOAL_ANALYSIS_ENABLED=false
VITE_AGENT_ENABLED=false
VITE_GOAL_ANALYSIS_ENABLED=false
```

本地验收需要同时启用 Agent、Worker、后端目标分析和两个前端入口。启用后，每个 Worker tick 先从
MySQL 选择最早一个 READY 且缺少目标 Run 的 Job，以数据库中的原始 `goal` 和稳定幂等键
`goal-analysis:<jobId>` 创建 Run，然后仍只推进一个 Agent Run。已有 READY Job 会逐个补齐；若不希望
产生这部分受预算约束的模型调用，不要开启 `AGENT_GOAL_ANALYSIS_ENABLED`。

页面通过以下 owner-scoped 接口读取状态：

```http
GET /api/v1/analysis-jobs/{jobId}/goal-analysis-result
```

状态为 `DISABLED/PENDING/REQUESTED/RUNNING/ANSWERED/PARTIALLY_ANSWERED/REFUSED/FAILED`。服务端只从已提交的
`GENERATE_AND_VERIFY` Step 接受严格 `answer:<uuid>`，再用现有 Answer service 校验 owner、Job 与
Answer；非法引用返回一致性冲突，不猜测结果。自动 Answer 在普通 `GET .../answers` 列表中排除，
但仍可由目标结果接口精确读取。Run 失败不反向修改 READY Job，也不自动创建替代 Run。

排查顺序：先查目标结果接口，再按其中 `runId` 读取 Agent Trace，最后核对 `model_call.run_id`。不要删
Run、改幂等键或把 UNKNOWN 调用重置为成功。Redis 清空和应用重启后，Worker 会从 MySQL 继续发现缺失
Run；已有终态 Run 不重建。

## 9. T11 有界部分回答

修复路径现在最多为 7 Step/5 次生成式调用：第二次 Answer 后还会执行一次 Critic。该 Critic 不能再
检索或扩窗，只能确认完整、发布有界部分回答或拒答。答案生成仍最多两次，工具修复仍最多一次。

`PARTIALLY_ANSWERED` 页面固定显示两区：

- “视频可以确认”：只包含通过 `AnswerEvidenceGate` 的 Claim 和可跳转时间戳引用；
- “视频证据不足以确认”：只列 `missingRequirements`，不显示模型常识或推测性补充。

排查时先核对 Answer 的 `status/claims/missingRequirements`，再查看最后一个 `CRITIQUE` Step。若任一 Claim
引用伪造、错绑或不能支撑 Claim，预期结果仍是整份 `REFUSED`，而不是部分发布。不要手工把拒答改成部分
回答，也不要通过提高 Step/Answer 上限制造第三轮。
