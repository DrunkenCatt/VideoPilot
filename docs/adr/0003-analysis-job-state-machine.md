# ADR-0003：AnalysisJob 使用统一状态迁移入口

## Status

Accepted

## Date

2026-10-27

## Context

长视频任务会经历 HTTP 提交、RabbitMQ 重投、服务重启、取消、有限重试和人工 Replay。若 Controller、Consumer 或定时任务直接更新状态，重复和乱序消息可能跳过阶段、重复计费或制造无法恢复的状态。状态与错误语义必须在实现表结构和消费者前冻结。

## Decision

`AnalysisJob` 只能通过 `JobTransitionService` 迁移。每次迁移在同一个 MySQL 事务中：

1. 校验允许的前置状态和期望 `version`；
2. 以 CAS 更新状态、阶段、版本和必要错误字段；
3. 写入阶段 Checkpoint 或产物引用（如本次迁移要求）；
4. 写入带单调 Job sequence 的 Outbox 事件。

主路径状态为：

```text
CREATED -> QUEUED -> CONSUMING -> PROBING -> CONTEXT_BUILDING
-> INDEXING -> READY -> ANSWERING -> VERIFYING
-> COMPLETED | COMPLETED_WITH_WARNINGS
```

受控分支为：

```text
PROBING | CONTEXT_BUILDING | INDEXING | ANSWERING -> RETRY_WAIT -> QUEUED
CREATED | QUEUED -> CANCELED
CONSUMING | PROBING | CONTEXT_BUILDING | INDEXING | ANSWERING
  -> CANCEL_REQUESTED -> CANCELED
CONSUMING | PROBING | CONTEXT_BUILDING | INDEXING | ANSWERING -> FAILED
FAILED -> DEAD_LETTERED -> MANUAL_REPLAY -> QUEUED
```

终态为 `COMPLETED`、`COMPLETED_WITH_WARNINGS` 和 `CANCELED`。终态不得被普通消息推进。`MANUAL_REPLAY` 是受审计的过渡状态，不允许通过重投旧消息进入。

错误分类采用稳定代码而非异常类名：

- `RETRYABLE`：明确的网络错误、429、临时 5xx 或短暂基础设施故障，可在预算内有限重试。
- `NON_RETRYABLE`：无权限、非法参数、不支持或已确认损坏的媒体，直接失败。
- `UNKNOWN`：调用超时或外部结果不确定，不能假定未执行；必须先对账或由幂等调用记录决定后续动作。
- `CANCELED`：用户请求或上层终止，不计作供应商故障。
- `BUDGET_EXHAUSTED`：时间、Token、费用或步骤预算耗尽，确定性终止，不自动扩大预算。

错误记录必须包含稳定 `errorCode`、分类、发生阶段、可重试标志和脱敏摘要；堆栈只进受控日志，不作为 API 契约。

## Alternatives Considered

### 各调用方直接更新状态

代码较少，但无法统一 CAS、Outbox 和非法迁移规则，会让至少一次投递变得不安全。

### 依赖 RabbitMQ 消息顺序

RabbitMQ 不提供应用所需的全局业务顺序，重投和并发消费者仍会产生乱序。

### 把所有失败都自动重试

会重复计费并掩盖权限、文件损坏和输入错误，违反有限预算要求。

## Consequences

- Controller、Consumer、Relay 和恢复任务都不能直接修改 Job 状态字段。
- M1 必须用单元测试穷举合法/非法边，并用 ArchUnit 或代码审查防止绕过迁移入口。
- 新增状态或改变迁移边必须新增 ADR；错误代码可扩展但既有语义不得静默改变。
- M4 再用故障注入验证恢复率、取消竞态和 DLQ/Replay，不在 M0 实现运行时流程。

## Validation

- 每条合法迁移均有状态机测试，未声明的迁移全部拒绝。
- 两个执行者用同一旧 `version` 推进时只有一个 CAS 成功。
- 重复消息在目标阶段已完成时成为 no-op，不重复高成本调用。
- 每次成功状态迁移都有同事务 Outbox 事件，失败事务不留下半更新。
- `UNKNOWN`、`NON_RETRYABLE` 和 `BUDGET_EXHAUSTED` 不会进入无界自动重试。

