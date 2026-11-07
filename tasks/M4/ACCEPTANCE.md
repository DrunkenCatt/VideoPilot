# M4 验收记录：任务可靠性发布门

> 状态：T0-T2 已完成；T3-T5 尚未实现。本文按任务持续追加实际命令、环境、结果和未执行项，未验证能力不得提前标记通过。

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

T3-T5 每完成一项在此追加候选 commit、RED/GREEN、定向/完整测试、真实浏览器、故障注入、未执行项和退出结论；上一个退出门未通过时不得开始下一项。

## 10. T1 实现与审查结论

T1 只增加一条可靠性 application service 和一份 V15 前进迁移，没有引入 retry job 表、第二套状态机或第二条实时协议：

- `analysis_job` 增加 `retry_count`、`next_retry_at` 和执行 lease；Job、阶段尝试和 Outbox 仍为 MySQL 唯一真源。
- 可重试失败进入最多 3 次的 `RETRY_WAIT`，到期后经既有 `JobTransitionService` 进入 `QUEUED`；耗尽后经合法的 `FAILED -> DEAD_LETTERED` 迁移停止自动执行。
- 活跃 Job 的过期 lease 通过 version CAS 只追加恢复事件和新 Outbox 消息；Redis 锁只减少并发，不能替代数据库 lease/CAS。
- UNKNOWN、不可重试和取消沿既有确定性分类停止或失败，不由消息 header、Broker DLQ 或模型输出改变。
- Job DTO 和既有工作台增加最小可靠性投影，状态仍由相同 REST/SSE 持久化事件恢复。

代码审查发现并修复了恢复扫描竞态：两个扫描器同时读取到期 `RETRY_WAIT` 时，CAS 失败的一方原本会中断整批；最终实现只跳过已被其他执行者领取的候选，后续候选继续处理。恢复次数、退避、lease 和 batch size 的非法配置会在执行前直接拒绝。

## 11. T1 RED/GREEN 与自动化证据

### RED

最初的 `AnalysisJobReliabilityServiceTest` 在可靠性 service、store port、`retryCount/nextRetryAt/lease` 领域事实不存在时无法编译；前端 Job 详情测试在可靠性投影和状态卡不存在时失败。没有删除断言或降低覆盖率阈值。

### GREEN

| 命令 | 环境与结果 |
| --- | --- |
| `server\mvnw.cmd -q "-Dtest=AnalysisJobReliabilityServiceTest,AnalysisJobExecutionHandlerTest,M4ContractTest" test` | 最终候选的可靠性规则、扫描竞态、配置校验、消费接线和 M4 合同通过 |
| `server\mvnw.cmd test` | Windows/JDK 21；330 项通过、0 失败、1 项既有 FFmpeg 环境条件跳过；JaCoCo 80% 门通过，1 分 34 秒 |
| `client\npm.cmd run check` | typecheck、零 warning lint、93 项 Vitest、覆盖率与生产构建全部通过；Statements 87.55%、Branches 80.64%、Functions 87.37%、Lines 92.59% |
| Docker `mvn -Dbenchmark.commit=m4-t1 verify -Pintegration` | 隔离 MySQL 数据库、Redis/RabbitMQ/MinIO/Qdrant、容器 FFmpeg/Tesseract 和经授权的真实 SiliconFlow 调用；326 项单元测试与 60 项集成测试全通过，0 跳过，9 分 16 秒 |
| Docker `mvn test-compile failsafe:integration-test failsafe:verify -Dit.test=AnalysisJobPersistenceAdapterIT,ApplicationRestartRecoveryIT` | 最终候选、全新数据库从 V1 迁移至 V15；MySQL CAS/lease/Outbox 7 项和应用重启恢复 1 项全部通过，3 分 15 秒 |

完整 Docker 门证明基准 Embedding/LLM 主路径仍可用；最终定向门在审查修复后重新证明 V15、CAS、lease、Outbox 和重启恢复。完整门生成的 M3 benchmark 内容与原基线指标一致，不作为 T1 功能改动提交。

## 12. T1 真实浏览器验收

2026-11-07 使用重新构建的 Compose server、真实 V15 主数据库与 Vite `http://localhost:5173/` 验收。登录凭据只提交到本机页面，没有输出或写入浏览器存储。

| 检查 | 观察 | 结果 |
| --- | --- | --- |
| 持久化合同 | 选择已有 FAILED Job 后显示 `该任务无法自动恢复` 和稳定原因码 `ANALYSIS_FIXTURE_FAILURE` | 通过 |
| 刷新恢复 | 页面刷新后 hash 仍指向同一 Job，`data-test=job-reliability` 从服务端重新显示相同状态与原因码 | 通过 |
| 桌面 1440x900 | `scrollWidth=1425 < innerWidth=1440`；可靠性卡位于内容边界内 | 通过 |
| 手机 375x812 | `scrollWidth=360 < innerWidth=375`；可靠性卡左右为 16/344px、宽 328px，无横向溢出 | 通过 |
| 可访问状态 | 可靠性卡使用 `role=status`，原失败详情继续使用 `alert`，两者语义可区分 | 通过 |
| Console | 最终 error/warn 为 0 | 通过 |

T1 没有提供 Manual Replay 操作，浏览器仅展示等待重试、自动恢复、需人工处理和不可恢复投影；Replay 确认与操作反馈留给 T2，不提前伪造。

## 13. T1 退出结论

T1 的自动 Recovery、有限 Retry、重试耗尽、数据库 lease/CAS、Outbox 重新排队和最小用户投影均使用现有 MySQL Job/Checkpoint/事件真源。完整与最终定向测试、代码审查及真实浏览器均通过；没有降低阈值、跳过新增测试或增加 suppression。Checkpoint A 通过，可以进入 T2。

## 14. T2 实现与审查结论

T2 在 T1 的可靠性 service、统一状态迁移和既有工作台内补齐 Manual Replay，没有增加 Replay 表、通用操作框架或第二套实时协议：

- owner、预期版本、明确确认、非空原因和操作幂等键在 application service 统一校验；仅 `DEAD_LETTERED` 可进入 Replay。
- 同一 Job 经 `DEAD_LETTERED -> MANUAL_REPLAY -> QUEUED` 产生新 attempt 和新 Outbox 消息；操作 key、意图指纹和 attempt 写入不可变事件，数据库唯一约束裁决并发。
- 相同 key 与相同意图返回同一审计结果；相同 key 改变意图稳定拒绝。竞态胜出者已推进到队列后，其他调用读取同一审计事实，不再产生第二个 attempt。
- 旧阶段尝试、Checkpoint、失败和事件不删除、不改写；后续执行继续使用已有输入指纹恢复规则，已完成且输入一致的阶段不重复调用供应商。
- REST 和 TypeScript 只增加一个 Replay 命令；工作台只在允许状态展示危险操作、二次确认和原因输入，提交中禁用重复操作，成功后重新读取持久化 Job 并继续既有 SSE。
- 内部 Manual Replay 审计事件投影到冻结的 M1 公共事件枚举，没有为局部实现扩大公开合同。

代码审查发现并修复了两个真实竞态问题：MySQL 将时间戳规范化到微秒后，首次响应和重复请求一度可能返回不同精度；最终首次响应也读取已提交审计行。两个并发相同 key 的调用也可能在第二次迁移 CAS 上竞争；最终输家只在同 attempt 已被胜出者推进时接受数据库现状，其他冲突继续失败。最终审查未发现剩余阻断项。

## 15. T2 RED/GREEN 与自动化证据

### RED

最初的 `AnalysisJobReliabilityServiceTest` 在 Replay 命令、审计查询和状态迁移接线不存在时无法编译；前端 API/组件测试在 Replay client、确认 UI 和防双击行为不存在时失败。没有删除断言、增加 suppression 或降低覆盖率门。

### GREEN

| 命令 | 环境与结果 |
| --- | --- |
| `server\mvnw.cmd -q -Dtest=AnalysisJobReliabilityServiceTest test` | Replay 规则、owner 隔离、旧版本、非法状态、幂等复用和中断恢复 13 项通过 |
| `server\mvnw.cmd -q test-compile failsafe:integration-test failsafe:verify -Dit.test=AnalysisJobPersistenceAdapterIT` | 本机 Compose MySQL `127.0.0.1:13306`；Replay 审计、历史保留和真实并发同 key 共 9 项通过 |
| `server\mvnw.cmd -q test-compile failsafe:integration-test failsafe:verify -Dit.test=AnalysisJobPersistenceAdapterIT,DatabaseMigrationIT` | 隔离数据库从 V1 前进迁移至 V16；迁移与持久化定向 15 项通过，验证微秒审计时间与唯一约束 |
| `server\mvnw.cmd test` | Windows/JDK 21；337 项通过、0 失败、1 项既有 FFmpeg 环境条件跳过；JaCoCo 80% 门通过，1 分 26 秒 |
| `client\npm.cmd run check` | typecheck、零 warning lint、96 项 Vitest、覆盖率与生产构建全部通过；Statements 86.77%、Branches 80.98%、Functions 86.89%、Lines 91.96% |

T2 没有修改 RabbitMQ 拓扑或供应商主路径，因此没有重复执行全量、可能产生外部模型费用的 `verify -Pintegration`；V16、MySQL CAS/唯一约束、Outbox 和并发操作已由隔离数据库定向集成门覆盖。T5 仍必须在同一候选提交执行完整 Docker 集成门。

## 16. T2 真实浏览器验收

2026-11-07 使用重新构建的 Compose server、真实 V16 数据库和 Vite `http://localhost:5173/` 验收。使用本地 `DEAD_LETTERED` fixture，Replay 后 ASR 配置缺失按既有故障路径安全失败，没有发起付费模型调用。

| 检查 | 观察 | 结果 |
| --- | --- | --- |
| 危险操作边界 | 仅 `operatorAction=MANUAL_REPLAY` 时显示“人工重放”；点击后才展开确认区 | 通过 |
| 明确确认 | 确认文案说明会创建新执行尝试且可能产生模型调用；原因为空时提交按钮禁用 | 通过 |
| 执行与审计 | 提交后 Job 从 attempt 1 进入 attempt 2，事件依次显示 Manual Replay accepted、queued 和后续执行阶段 | 通过 |
| 刷新持久化 | 页面刷新后仍从服务端显示 attempt 2、Replay 审计事件和最终失败状态 | 通过 |
| 防重复与响应式 | 提交期间按钮禁用；桌面 `1280x800` 与手机 `375x812` 均无横向溢出，手机详情宽 328px | 通过 |
| 运行时 | DOM 交互、请求和刷新无可见运行时错误；本次 in-app Browser 接口未提供 Console 消息读取能力 | 限制已记录 |

## 17. T2 退出结论

T2 的 owner、CAS、幂等、同 Job 新 attempt、不可变历史、新 Outbox 和真实浏览器操作均通过。已完成阶段继续由既有输入指纹规则复用，自动化断言 Replay 不清除阶段历史；没有重新投递旧 messageId。Checkpoint B 的 Replay 子项通过，deadline/预算/资源隔离两项仍保持未完成并由 T3 验证。T2 可以独立提交并进入 T3。
