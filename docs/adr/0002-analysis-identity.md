# ADR-0002：分离分析逻辑身份与执行身份

## Status

Accepted

## Date

2026-10-27

## Context

重复提交保护、结果复用、失败恢复、版本比较和执行审计是不同问题。若只用媒体和目标组成一个任务 ID，重试可能覆盖历史，版本变化也可能静默复用旧结果。

## Decision

分离以下身份：

- `analysisKey` 表示带媒体指纹、规范化目标、模式和管线版本的逻辑分析请求，用于结果复用判断；
- `jobId` 表示一次可恢复的后台任务，创建后不复用；
- `runId` 表示 Job 内一次执行或实验，Replay 不覆盖旧 Run；
- `idempotencyKey` 表示客户端提交意图，用于重复提交保护；
- `requestId` 只用于调用链追踪，不参与业务去重。

Recovery 保留同一个 Job；Replay 创建新的执行记录。影响结果复用的管线、模型或提示版本必须进入逻辑请求或阶段指纹，而不能隐式复用旧结果。

## Alternatives Considered

- 只使用 `mediaId + goal`：无法表达版本、恢复和审计边界。
- 每次请求新建 Job：会让客户端重试重复执行高成本阶段。
- 使用 `requestId` 去重：重试通常产生新 requestId，不能代表稳定提交意图。

## Consequences

- MySQL 必须保存这些身份及其关系；Redis 只能做快速拦截，不能做事实真源。
- API、MQ、数据库和 Trace 必须沿用同一套名称。
- 后续修改身份组成或复用语义需要新的 ADR 和迁移评估。

## Validation

- 相同用户、相同幂等键和相同请求只对应一个逻辑提交。
- Recovery 不改变 Job，Replay 不覆盖历史 Run。
- 版本变化不会静默复用不兼容结果。

