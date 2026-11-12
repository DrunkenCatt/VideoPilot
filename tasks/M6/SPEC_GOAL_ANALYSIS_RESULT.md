# M6-T5 规格：自动目标分析结果

> 状态：负责人已批准，按 `EX-2026-11-12-M6-T5` 受限实施
>
> 依赖：M6-T4 已以不可晋级状态独立提交；效果门缺口继续保留
>
> 默认启用：否
>
> 规格日期：2026-11-12

## 1. 问题与结论

当前 `AnalysisJob` 保存了用户提交时的分析目标，但 Job 到达 `READY` 后只表示视频上下文和索引可供查询，
不会主动产出面向该目标的结果。用户必须重新组织一个问题才能看到答案，导致“提交分析目标 -> 等待分析 -> 查看
分析结果”的产品闭环缺少最后一步。

在 Agent 实验能力启用时，Job `READY` 后自动以原始分析目标创建一次 `AgentRun`，并在 Job 页面独立展示
“目标分析结果”是合理的。该结果复用现有 Agent Loop、`Answer`、`Claim`、`CitedEvidence`、预算和 Trace，
不新增第二套结果模型、模型调用链或通用工作流抽象。

## 2. 用户故事

1. 用户提交“总结这节课的核心命令并说明适用场景”作为分析目标。
2. 视频解析和索引完成，Job 首次进入 `READY`。
3. 系统在后台以该目标创建唯一一次自动 `AgentRun`，页面显示正在生成目标分析结果。
4. Run 完成后，页面在手动问答区域之前展示答案、Claim 和可跳转的时间戳引用。
5. 如果证据不足、需要澄清、预算关闭或依赖失败，页面展示真实终止状态和原因，不伪造结果。
6. 刷新页面、应用重启、Worker 重复执行或 Redis 清空后，系统仍从 MySQL 恢复同一个 Run 和结果。

## 3. 范围

### 3.1 本任务包含

- 新增默认关闭的 `videopilot.agent.goal-analysis-enabled` 配置；只有 Agent、Worker 和该开关同时启用时才
  自动创建 Run。
- Worker 从 MySQL 逐个发现“已 READY 且尚无目标分析 Run”的 Job，并使用 Job 的原始 `goal` 创建 Run。
- 使用稳定幂等键 `goal-analysis:<jobId>`；重复扫描、并发 Worker 和进程重启只能得到同一个 Run。
- 新增 owner 隔离的目标分析结果查询接口，返回功能状态、Run Trace 和该 Run 产生的标准 Answer。
- 在现有 `AnswerWorkspace` 顶部增加“目标分析结果”区域，覆盖等待、运行、已回答、拒答、失败和未启用状态。
- 自动结果中的引用继续触发现有视频时间轴跳转；手动问答入口和历史保持可用。
- 更新 M6 TODO、PLAN、ACCEPTANCE、Runbook 和配置示例，并记录实际质量门证据。

### 3.2 本任务不包含

- 不修改 Analysis Job 状态机；自动 Run 的失败不能把已经 `READY` 的 Job 改成失败。
- 不把 Agent 输出写回 `AnalysisJob`，不新增 `analysis_result` 表。
- 不新增 Prompt、Agent Policy、工具注册框架或模型供应商路由。
- 不自动重试已明确终止的 Run；需要重试时以后设计显式用户操作和新的 Run 身份。
- 不把自动目标结果计入手动问答次数或在页面重复展示为一条普通问答历史。
- 不默认开启 Agent、Worker、前端实验入口或本功能。

## 4. 任务位置与依赖

本功能登记为 **M6-T5：补齐自动目标分析结果闭环**，依赖 M6-T4 完整退出。原因如下：

- T4 当前仍有实时引用定位门未通过，并存在尚未提交的实现；插入新运行时代码会混合验证证据和提交边界。
- 自动目标 Run 会产生真实检索、模型调用、费用和引用，因此必须继承 T4 已验证的价格、硬门和评测事实。
- T5 是用户体验闭环，不改变 T0-T4 的 Agent 能力和晋级裁决定义。

负责人于 2026-11-12 明确要求“记录错误，优先进入 T5”。因此 T4 以不可晋级状态独立提交后，依据
`EX-2026-11-12-M6-T5` 开始本任务；该例外不把 T4/M6 改写为通过，也不豁免本任务自身质量门。

## 5. 最小设计

### 5.1 自动触发与恢复

沿用现有 `AgentWorker` 定时循环。每个 tick 最多执行以下动作：

1. 当 `goal-analysis-enabled=true` 时，从 MySQL 选择最早一个 `READY` Job；该 Job 不存在
   `idempotency_key = goal-analysis:<jobId>` 的 AgentRun。
2. 使用数据库中该 Job 的 `owner_id`、`job_id` 和 `goal` 调用现有 Run 创建规则。
3. 继续调用现有 `processNext()`，由 Agent 的租约和 CAS 领取一个待运行 Run。

扫描是恢复机制而不是第二套队列。`agent_run` 的 `(owner_id, idempotency_key)` 唯一约束仍是最终单赢家；
Redis、进程内事件和页面轮询都不承担创建真源。启用开关后，既有 READY Job 也会按创建时间逐个补齐，避免
历史 Job 永久缺少目标结果；每 tick 最多创建一个以限制瞬时费用。

### 5.2 Run 身份

- 目标：数据库中已规范化的 `analysis_job.goal`，不得由页面再次提交或修改。
- 幂等键：`goal-analysis:<jobId>`。
- request fingerprint：继续由现有 `AgentApplicationService` 根据 Job、目标、版本和预算生成。
- 版本与预算：使用创建时现有 Agent 版本快照和预算快照，不引入目标分析专用参数。
- 结果归属：Run 的 `job_id` 和 owner 不变；Evaluation Run 字段为空。

不新增 `run_type` 字段。MVP 用保留的稳定幂等键识别唯一自动 Run；如果未来出现多类系统 Run，再通过迁移
增加明确类型，而不是现在预建枚举层。

### 5.3 结果绑定

Agent 的 ANSWER 动作已经把最终 `answerId` 写入 Step 的 `resultReference`，格式为 `answer:<uuid>`。
目标结果查询由 application service 完成：

1. 按 owner、Job 和稳定幂等键读取目标 Run Trace。
2. 从已提交 Step 中读取最后一个合法的 `answer:<uuid>` 引用。
3. 通过现有 Answer application service 按 owner、Job、answerId 读取完整 Answer。
4. 没有 Answer 的拒答、澄清或失败 Run 只返回 Run 事实，不制造空 Answer。

这避免给 `answer_record` 增加重复的 Run 归属字段，也不让 Controller 直接访问 Store。

### 5.4 API 合同

新增只读接口：

```http
GET /api/v1/analysis-jobs/{jobId}/goal-analysis-result
```

响应 `200`：

```json
{
  "state": "RUNNING",
  "run": { "runId": "...", "status": "RUNNING", "steps": [], "toolCalls": [] },
  "answer": null
}
```

`state` 取值：

- `DISABLED`：自动目标分析开关关闭。
- `PENDING`：功能开启，Job 已 READY，但 Worker 尚未创建 Run。
- `REQUESTED`、`RUNNING`：映射 Run 进行中状态。
- `ANSWERED`：Run 完成且存在通过证据门的 Answer。
- `REFUSED`：证据不足或需要澄清，没有可发布 Answer。
- `FAILED`：预算、未知费用、依赖或未知结果等原因导致 Run 失败。

边界：

- 非 READY Job 返回 `409 JOB_NOT_READY`，页面不应提前调用。
- Job 不存在或不属于当前 owner 返回统一 `404 RESOURCE_NOT_FOUND`。
- Agent 整体关闭时维持现有 Controller 不注册行为；前端实验入口默认关闭且不调用接口。
- 响应只包含现有去敏 Trace，不返回 Prompt、模型原始响应、完整转录或工具原始参数。

### 5.5 页面

在现有 Job 详情的问答工作区内，手动提问表单之前增加一个语义独立区域：

- 标题固定为“目标分析结果”，同时展示原始分析目标。
- `PENDING/REQUESTED/RUNNING` 展示明确进度文案并轮询同一只读接口。
- `ANSWERED` 复用现有 Claim/Citation 表现和 `cite` 事件，不复制一套证据交互组件。
- `REFUSED/FAILED` 展示终止原因和是否可重试的真实语义，不称为模型答案。
- `DISABLED` 只在实验入口已开启但自动功能关闭时显示“未启用自动目标分析”，不持续轮询。
- 自动 Answer 从普通问答历史中过滤，避免同一结果显示两次；手动问答和手动 Agent 开关行为不变。
- 覆盖 320px 和桌面宽度、键盘引用跳转、loading/empty/error 状态。

## 6. 确定性规则

- 只有 `READY` Job 可以创建目标 Run。
- 同一 owner/Job 最多存在一个幂等身份为 `goal-analysis:<jobId>` 的目标 Run。
- 自动扫描不得绕过现有 owner、预算、版本、证据和未知费用硬门。
- Run 终态不反向改变 Job 状态；页面必须同时允许用户继续手动问答。
- 读取 `resultReference` 时只接受严格的 `answer:<uuid>`，非法引用按服务端一致性错误处理并记录，不猜测 ID。
- 自动创建失败不得被吞掉；Worker 记录去敏原因，下一个 tick 可安全重试尚未落库的创建。
- 已落库的 FAILED/REFUSED/COMPLETED Run 不自动创建替代 Run。

## 7. 验收场景

### 场景 A：READY 后唯一创建

**Given** 功能开关开启且 Job 首次 READY；**When** 两个 Worker 并发扫描并经历应用重启；**Then** MySQL
只有一个目标 Run，只有该 Run 的预算允许的外部调用，没有重复计费。

### 场景 B：恢复与历史补齐

**Given** Redis 清空、应用重启，或功能启用前已有 READY Job；**When** Worker 继续 tick；**Then** 从 MySQL
发现缺失 Run 并逐个补齐，刷新页面恢复同一 Trace 和 Answer。

### 场景 C：证据化目标结果

**Given** 目标有充分视频证据；**When** Run 完成；**Then** 页面只展示通过现有 AnswerEvidenceGate 的 Claim，
每条事实 Claim 至少一个可点击 `CitedEvidence`，点击后跳到正确时间窗。

### 场景 D：拒答和失败隔离

**Given** 证据不足、需要澄清、未知费用、预算耗尽或依赖失败；**When** Run 终止；**Then** 页面展示准确状态
和去敏原因，Job 仍为 READY，手动问答仍可使用，不伪造目标答案。

### 场景 E：默认关闭与权限

**Given** 默认配置或其他 owner；**When** 页面加载或直接请求接口；**Then** 默认不产生模型调用，其他 owner
不能发现 Run、Answer、目标或 Trace。

### 场景 F：页面完整性

**Given** 桌面和 320px 视口；**When** 结果经历等待、完成和刷新；**Then** 无 Console 错误，网络请求符合合同，
键盘可操作引用，自动结果不在普通问答历史重复出现。

## 8. 测试与退出门

按 RED -> GREEN -> REFACTOR 实施，至少覆盖：

- application 单测：候选选择、稳定幂等键、开关关闭、严格结果引用解析、owner 隔离和状态映射。
- Store/MySQL 集成：并发创建单赢家、READY 限制、既有 READY 补齐、重启后不重复、终态不重建。
- Controller 测试：六类状态、404/409、去敏合同。
- Vue/API 测试：轮询、刷新、Answer 精确绑定、历史去重、拒答/失败、默认关闭和引用事件。
- 真实浏览器：localhost 登录后验证一个新 READY Job 和一个既有 READY Job，检查桌面/320px、Console、
  Network、刷新以及引用跳转。
- 任务完整门：`server\mvnw.cmd test`、`server\mvnw.cmd verify -Pintegration`、
  `client\npm.cmd run check`、Gitleaks、有效的依赖安全检查或仍在有效期内且适用的已登记例外。

不得降低现有 80% changed-code coverage、引用定位、Claim 支持、Agent 预算或安全门。若任一前序退出门失败，
停止后续验收和提交。

## 9. 文档与提交

实现完成后更新：

- `tasks/M6/PLAN.md`：加入 T5 依赖和最小设计决定。
- `tasks/M6/TODO.md`：加入可勾选任务与退出门。
- `tasks/M6/ACCEPTANCE.md`：记录场景、实际命令、结果和未执行项。
- `docs/M6_RUNBOOK.md`：加入开关、自动补齐、查询和故障处理。
- `.env.example` 及 Compose 示例：新增默认 `false` 的开关，不写入任何真实凭据。

T5 的测试、代码和上述文档作为一个独立功能提交；不得混入当前未提交的 T4 评测修复。若实施时发现必须
改变 Job 状态机、Outbox 语义、Agent 预算或结果身份，再先新增 ADR 并重新批准规格；按当前最小设计无需 ADR。

## 10. 已确认

1. 接受启用功能时为既有 READY Job 逐个补齐目标结果；每个 Worker tick 最多创建一个，并继续受现有预算
   和未知费用硬门约束。
2. T4 已先形成独立提交并保留失败事实；负责人接受以流程例外优先进入 T5，不接受降低任何效果或工程门。
