# M4 验收记录：任务可靠性发布门

> 状态：T0 合同与基线验收已完成；T1-T5 尚未实现。本文按任务持续追加实际命令、环境、结果和未执行项，未验证能力不得提前标记通过。

## 1. M4 针对现有流程解决的问题

M4 不新增业务流程，主要修复现有链路在故障和并发下的五类发布风险：

1. **执行中断后谁来继续**：Worker/应用退出后，当前只有阶段局部 Checkpoint，没有统一扫描停滞 Job 并重新排队的入口。
2. **什么错误可以重试**：Consumer 有 Broker 级重投，但 `RETRYABLE/UNKNOWN/NON_RETRYABLE` 尚未完整收敛为业务 Job 状态，UNKNOWN 可能被错误地重复付费。
3. **重试耗尽后页面看什么**：物理 DLQ 和 `AnalysisJob` 可能各自变化，用户可能看到消息已死信但 Job 仍显示处理中。
4. **人工如何安全重跑**：状态已预留 Manual Replay，但没有 owner、CAS、幂等、审计、确认 UI 和“复用哪些产物”的完整操作面。
5. **流程如何有界且可诊断**：已有 Adapter 局部超时/并发限制，但缺少跨阶段 deadline、调用/计量/成本预算，以及积压、失败、耗时和供应商错误聚合视图。

## 2. 复用与补齐矩阵

| 能力 | M1-M3 已有事实 | M4 补齐 | 明确不做 |
| --- | --- | --- | --- |
| 状态裁决 | `JobTransitionService` + MySQL version CAS | Retry/DLQ/Replay 的实际入口和事件 | 第二套状态机、Controller 直写状态 |
| 可靠发布 | Transactional Outbox、confirm/return、lease | 恢复重新排队和 Replay 新消息 | 重投旧消息、exactly-once 宣称 |
| 消费幂等 | Redisson 锁、状态检查、阶段指纹 | DB 执行 lease、heartbeat、停滞扫描竞态 | 以 Redis 锁作为正确性真源 |
| 阶段恢复 | ASR/OCR work item、Context/Index Checkpoint | 跨阶段 Recovery 决策与统一投影 | 通用 DAG/工作流引擎 |
| 高成本去重 | `model_call` 指纹、UNKNOWN 审计 | Retry/Replay 规则和全局预算检查 | UNKNOWN 自动重发、价格 unknown 当 0 |
| 实时界面 | MySQL 事件流、SSE cursor/重连 | 恢复/重试/人工状态及操作结果 | 第二条 WebSocket/SSE 协议 |
| 运维 | Health、稳定错误、阶段/调用事实 | 有界聚合 API 和同工作台只读视图 | 独立 Admin SPA、Grafana/ELK 平台 |

## 3. 冻结合同

### 3.1 错误到动作

错误映射以 ADR-0008 为唯一决策来源：`RETRYABLE` 有限自动重试，`UNKNOWN_OUTCOME` 停止自动高成本重发，`NON_RETRYABLE` 失败，`CANCELED` 取消，`BUDGET_EXHAUSTED` 在所属聚合记录并阻止下一调用。任何模型输出都不能改变映射。

### 3.2 初始恢复参数

| 参数 | 初始值 | 约束 |
| --- | ---: | --- |
| 自动重试上限 | 3 | 沿用 `ANALYSIS_CONSUMER_MAX_RETRIES`，不可由消息 header 放大 |
| 最小重试延迟 | 10 秒 | 沿用 retry queue TTL，最终以 MySQL `nextRetryAt` 为准 |
| execution lease | 90 秒 | MySQL 真源，配置化 |
| heartbeat | 30 秒 | 必须小于 lease |
| recovery scan | 10 秒 | 只扫描 lease 过期且状态允许的 Job |
| Replay 原因上限 | 500 字符 | 非空、去除首尾空白，不进入 MQ 消息正文 |

T3 的 deadline、调用/计量/成本和资源池阈值在实现前以现有真实调用基线冻结；不得为了让故障测试通过而放大为无界。

### 3.3 REST/TypeScript 合同

- `POST /api/v1/analysis-jobs/{jobId}/replays`：owner-scoped；`Idempotency-Key` header；body 包含 `expectedVersion`、`confirmed=true` 和 `reason`。
- Job 详情追加 `JobReliability` 投影：`recoveryState`、`operatorAction`、`attempt`、`nextRetryAt`、`reasonCode`。
- `GET /api/v1/operations/reliability`：只读聚合；固定窗口内返回 Outbox/队列积压、失败分类、阶段 P50/P95、供应商调用/错误/计量/成本和 degraded source。
- 价格不可得返回 `estimatedCost=null`；RabbitMQ 管理数据不可得时保留 MySQL 指标并返回 `degradedSources=[RABBITMQ]`。
- 响应禁止返回 ownerId、对象 key、Prompt、模型原始响应、内部异常、数据库连接或凭据。

## 4. T0 可执行场景

### 场景 A：前后端路径一致

**Given** 同一 `jobId`；**When** Java 与 TypeScript 生成 Replay 路径；**Then** 均为 `/api/v1/analysis-jobs/{jobId}/replays`，运维路径均为 `/api/v1/operations/reliability`。

### 场景 B：人工操作与恢复状态分离

**Given** 重试耗尽的 Job；**When** 返回可靠性投影；**Then** `recoveryState=MANUAL_ACTION_REQUIRED`、`operatorAction=MANUAL_REPLAY` 且 `nextRetryAt=null`，页面不能把它显示为自动恢复。

### 场景 C：Replay 必须明确确认

**Given** Replay 请求；**When** 缺少预期版本、幂等键、确认或原因；**Then** 合同拒绝请求，不产生 attempt、事件或消息。

### 场景 D：运维成本 unknown 不伪造为零

**Given** 供应商没有价格；**When** 读取可靠性摘要；**Then** `estimatedCost=null`，并继续显示调用次数和输入/输出计量。

### 场景 E：现有 M3 工作台不回归

**Given** 已运行的 M3 本地环境；**When** 浏览器访问登录页和既有工作台；**Then** 页面可渲染、响应式布局无横向溢出、Console 无 error/warning，T0 合同冻结不提前展示尚未实现的 Replay/运维功能。

## 5. T1-T5 验收场景索引

| 任务 | 必须证明的场景 |
| --- | --- |
| T1 | 三个关键阶段中断恢复；Retry 到期；UNKNOWN 不重发；不可重试；重试耗尽；DLQ/Job 关联；Redis/Rabbit 短暂不可用；扫描器/消费者竞态 |
| T2 | owner 隔离；合法 Replay；旧版本冲突；重复幂等键；并发双击；新 attempt/新消息；旧历史只读；完成阶段零重复调用 |
| T3 | deadline、调用数、输入/输出计量、已知成本、价格 unknown、429、池满、线程中断、子进程清理；同步问答不污染 READY Job |
| T4 | 积压、失败、阶段耗时、供应商错误/计量/成本；Rabbit 数据源降级；敏感字段边界；桌面/手机/键盘/刷新 |
| T5 | 至少 20 次冻结可恢复执行、成功不少于 19；任务零丢失、完成阶段零重复高成本调用、非法迁移零成功；完整浏览器和质量门 |

## 6. T0 测试证据

### RED

| 命令 | 预期失败 |
| --- | --- |
| `server\mvnw.cmd -Dtest=M4ContractTest test` | `M4ApiContracts` 不存在，测试编译失败 |
| `client\npm.cmd exec vitest run src/api/contracts.test.ts` | `jobReplaysPath` 不存在，M4 路径测试失败 |

### GREEN

| 命令 | 结果 |
| --- | --- |
| `server\mvnw.cmd -q -Dtest=M4ContractTest test` | 4 项 M4 Java 合同测试通过 |
| `client\npm.cmd exec vitest run src/api/contracts.test.ts` | 11 项前端合同测试通过，其中 3 项为 M4 |

## 7. T0 浏览器验收

2026-11-07 使用运行中的真实 Vite `http://localhost:5173/` 与 Compose server 执行。T0 只做既有工作台回归，没有登录、提交 Job 或展示未实现的 Replay/运维控件。

| 检查 | 观察 | 结果 |
| --- | --- | --- |
| DOM/可访问结构 | 页面真实渲染登录工作台；h1 为“回到视频，也回到证据。”、h2 为“进入工作台”；账号/密码均有 label，按钮可用 | 通过 |
| 桌面 1440x900 | `innerWidth=1440`、document `scrollWidth=1425`，无横向溢出 | 通过 |
| 手机 375x812 | `innerWidth=375`、document `scrollWidth=360`、main 宽 328；登录区域和两个 label 可见 | 通过 |
| Console | 桌面与手机最终 error/warn 均为 0 | 通过 |
| 范围约束 | 页面仍标识 M3，未提前暴露 Replay 或可靠性运维入口 | 通过 |

T0 未改变运行时代码或页面，浏览器验收用于冻结 M4 开始前的无回归基线；后续每个用户可见任务仍需重新执行真实交互、网络和 Console 验收。

## 8. T0 完整质量门与退出结论

| 命令 | 结果 | 耗时 |
| --- | --- | ---: |
| `server\mvnw.cmd test` | 322 项通过、0 失败、1 项既有环境条件跳过；JaCoCo 80% 门通过 | 1 分 27 秒 |
| `client\npm.cmd run check` | typecheck、零 warning lint、91 项 Vitest、coverage 和生产构建全部通过 | 约 49 秒 |
| 浏览器 `http://localhost:5173/` | 桌面/手机 DOM、响应式、可访问名称和 Console 通过 | 通过 |

前端覆盖率：Statements 87.37%、Branches 80.41%、Functions 87.37%、Lines 92.59%。T0 未改数据库、RabbitMQ 或运行时行为，因此未重复执行 `verify -Pintegration`；T1 首次引入迁移与恢复行为时必须执行定向及完整集成门。

退出结论：T0 合同、ADR、验收矩阵、前后端合同测试、完整快速门和真实浏览器基线均通过，可以进入 T1。没有降低阈值、跳过新增测试、增加 suppression 或提前宣称 M4 行为已经实现。

## 9. 后续任务证据

T1-T5 每完成一项在此追加候选 commit、RED/GREEN、定向/完整测试、真实浏览器、故障注入、未执行项和退出结论；上一个退出门未通过时不得开始下一项。
