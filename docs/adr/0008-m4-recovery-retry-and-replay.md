# ADR-0008：M4 Recovery、Retry、DLQ 与 Manual Replay 语义

## Status

Accepted（deadline 作用域由 ADR-0012 补充）

## Date

2026-11-07

## Context

M1-M3 已经建立 MySQL Job/Checkpoint/Outbox 真源、RabbitMQ 至少一次投递、数据库 CAS、阶段输入指纹和模型调用审计，但这些能力仍是局部的：Broker 重试耗尽与 Job 状态可能分离，进程退出后没有统一的停滞扫描入口，`RETRY_WAIT/DEAD_LETTERED/MANUAL_REPLAY` 只存在于状态合同中，且全链路预算尚未形成确定性终止语义。

M4 是现有流程的可靠性发布门，不改变视频解析、检索、问答或证据门算法。它需要回答四个问题：谁判断任务需要恢复、哪些错误可以自动重试、Broker DLQ 与业务状态如何一致、人工 Replay 如何保留历史并避免重复计费。

## Decision

### 1. MySQL 继续是唯一恢复真源

- `analysis_job` 保存当前状态、当前执行 attempt、恢复 lease、下一次允许重试时间和稳定失败事实。
- `job_stage_attempt`、视频派生工作项、索引版本和 `model_call` 保存阶段完成事实与输入指纹。
- RabbitMQ retry queue/DLQ、Redis 锁和进程内调度器都只能加速执行，不能证明 Job 已恢复、已失败或可以 Replay。
- 恢复扫描器只处理 MySQL 中 lease 已过期、状态允许且未终止的 Job；领取和推进都使用数据库 CAS。

### 2. Recovery 与 Retry 分离

- Recovery 保留同一个 `jobId` 和 execution attempt，用于进程退出、消息丢失或锁/lease 过期后继续尚未完成的执行。
- Retry 保留同一个 `jobId` 和 execution attempt，用于明确 `RETRYABLE` 的阶段失败；初始上限沿用消息配置的 3 次，每次延迟至少 10 秒，具体值配置化并写入事件。
- 执行 lease 初始为 90 秒，运行中每 30 秒续租，恢复扫描间隔 10 秒；三项均配置化且必须满足 heartbeat 小于 lease。资源关闭或服务停止后停止续租，其他实例只能在 lease 过期后接管。
- 输入指纹一致且已原子完成的阶段、分片或模型调用是 no-op；恢复不得删除或重做它们。

### 3. 错误分类只有一种自动动作

| 错误分类 | 自动动作 | 最终用户语义 |
| --- | --- | --- |
| `RETRYABLE` | 在预算内进入 `RETRY_WAIT`，到期后由新 Outbox 消息进入 `QUEUED` | 等待自动重试/自动恢复 |
| `UNKNOWN_OUTCOME` | 不自动再次发起同一高成本调用；记录失败并转人工关注 | 结果未知，需人工确认 |
| `NON_RETRYABLE` | 进入 `FAILED`，不自动重试 | 不可自动恢复 |
| `CANCELED` | 收敛到 `CANCELED` | 用户已取消 |
| `BUDGET_EXHAUSTED` | 阻止下一次高成本调用并持久化所属聚合的预算错误 | 预算已耗尽 |

自动重试达到上限时，Job 经统一迁移入口进入 `FAILED -> DEAD_LETTERED`。Broker 消息是否已经位于物理 DLQ 是诊断信息，不改变这一业务事实。消息合同无法解析且无法安全取得 `jobId` 时只记录 `analysis_message_failure` 并进入物理 DLQ，不能猜测或修改任意 Job。

### 4. Manual Replay 创建新 execution attempt

- 只允许 owner 对 `DEAD_LETTERED` Job 执行单 Job Replay；不提供批量或任意状态修改。
- 请求必须包含当前预期版本、明确确认、非空原因和 `Idempotency-Key`。数据库 CAS、owner 和幂等唯一约束共同裁决并发请求。
- Replay 递增同一 Job 的 `attempt`，生成新的执行追踪标识、新 Job 事件和新 Outbox `messageId`；旧消息不得原样重新入队，旧事件、失败记录和阶段历史不得覆盖。
- 视频固有且输入指纹一致的已完成产物继续复用。失败或指纹失效阶段使用新 attempt 记录重新执行。
- `UNKNOWN_OUTCOME` 不因点击 Replay 自动变成“肯定未执行”；实现必须先展示风险并只从可证明的 Checkpoint 继续。

### 5. 预算错误写回所属聚合

- AnalysisJob pipeline 超限时，经 `JobTransitionService` 保存 `BUDGET_EXHAUSTED` 失败事实。
- M3 同步问答超限时，写入现有 `answer_record/model_call`，不得把已经 READY 的 AnalysisJob 倒退或改成失败。
- 成本价格不可得时保存 unknown，不按零处理；调用次数、输入/输出计量和 deadline 仍必须形成硬门。

## Alternatives Considered

- **只依赖 RabbitMQ retry header/DLQ**：Broker 无法原子更新 Job、Checkpoint 和审计，重建页面状态时会产生第二真源。
- **所有失败自动重试**：会重复 UNKNOWN 高成本调用并放大费用，也会让损坏输入永远占用队列。
- **Replay 直接重投旧消息**：旧 messageId、旧 attempt 和旧失败事实无法区分，违反 ADR-0002 的执行身份边界。
- **新建通用工作流/策略框架**：M4 只有一个模块化单体和固定流程，增加抽象不能提升当前恢复正确性。
- **创建全新 Job 作为 Replay**：会破坏同一逻辑 Job 的事件、owner 操作和恢复历史连续性；MVP 使用同 Job 新 attempt，并保留不可变历史。

## Consequences

- `analysis_job`、阶段 attempt、事件和公开 Job 投影需要增加少量恢复/attempt 事实。
- 消费者、恢复扫描器和 Manual Replay 会竞争同一 Job，必须统一使用状态机、CAS、lease 和输入指纹。
- 物理 DLQ 与业务 `DEAD_LETTERED` 可能短暂不同步；运维视图必须同时显示并标明数据来源，不能用 Broker 覆盖 MySQL。
- 后续若引入多节点调度或改变 Replay 的 Job 身份，必须用新 ADR 取代本决策。

## Validation

- 应用在 PROBING、CONTEXT_BUILDING、INDEXING 的安全点终止后，同一 Job 自动续跑且完成阶段的高成本调用增量为 0。
- 明确临时错误最多自动重试 3 次；UNKNOWN 和不可重试错误不会自动重发。
- 重试耗尽后 MySQL Job、失败审计和物理 DLQ 可关联；RabbitMQ/Redis 丢失后仍能从 MySQL 重建待办。
- 并发恢复、消费或 Replay 只有一个 CAS 胜出；重复 Replay 幂等键只产生一个新 attempt 和新消息。
- Replay 后旧阶段、事件和失败记录保持可查询且不被覆盖。
- 同步问答预算失败不会修改 READY AnalysisJob。
