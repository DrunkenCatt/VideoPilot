# ADR-0002：分离分析逻辑身份与执行身份

## Status

Accepted

## Date

2026-10-27

## Context

VideoPilot 必须同时处理客户端重复提交、相同目标的结果复用、失败后的恢复、版本升级后的重新分析和 Agent 轨迹审计。仅用 `mediaId + goal + mode` 作为任务标识会把逻辑去重、一次后台执行和一次 Agent 执行混为一谈，导致旧结果被错误复用，或重试覆盖历史执行记录。

## Decision

使用五层身份，并由 MySQL 保存身份关系：

- `analysisKey`：`mediaFingerprint + normalizedGoal + mode + pipelineVersion` 的稳定摘要，表示可去重的逻辑分析请求。规范化算法和摘要算法必须版本化。
- `jobId`：一次持久化、可恢复的 `AnalysisJob`，创建后永不复用，也不因 Recovery 改变。
- `runId`：Job 内一次 `AgentRun` 或目标相关执行。Replay、实验或候选版本比较创建新 Run，不覆盖旧 Run。
- `idempotencyKey`：由客户端为一次提交意图提供，作用域至少包含用户；相同键和相同规范化请求返回原提交结果，键相同但请求指纹不同则拒绝。
- `requestId`：一次 HTTP、MQ 或模型调用链的追踪标识，只用于关联日志和 Trace，不参与业务去重。

媒体固有产物以 `mediaFingerprint` 和产物版本识别，可跨目标复用。`analysisKey` 相同也不表示永久复用：Prompt、模型、Embedding、检索或管线版本改变且会影响结果时，必须通过版本字段形成新执行或显式选择旧结果。

Recovery 恢复同一个 `jobId`，根据一致的阶段输入指纹跳过已完成阶段。Replay 保留历史 Job/Run 和可复用的媒体固有产物，创建新的执行尝试或 `runId`。

## Alternatives Considered

### 只使用 mediaId 和 goal

无法表达媒体内容变化、管线版本和不同执行尝试，也不能可靠审计重跑。

### 每次请求都创建新 Job

实现简单，但客户端重试会重复执行高成本阶段，不能满足幂等要求。

### 将 requestId 作为幂等键

requestId 的生命周期是一次调用链，重试通常产生新值，不能代表稳定提交意图。

## Consequences

- M1 数据库需要用户作用域的 `idempotencyKey` 约束和可查询的 `analysisKey`，但不能仅依赖 Redis 去重。
- 任何影响结果复用的版本字段必须进入输入指纹或 `analysisKey` 计算契约。
- API、MQ 和模型 Trace 使用同一组名称，不为相同概念创造别名。
- 身份摘要不能包含明文凭据或不必要的用户隐私。

## Validation

- 同一用户使用相同 `idempotencyKey` 和相同请求重复提交，只返回同一提交结果。
- 相同 `idempotencyKey` 携带不同请求指纹时被确定性拒绝。
- Recovery 不改变 `jobId`，Replay 不覆盖旧 `runId`。
- 管线版本变化时不会静默复用旧 `analysisKey` 结果。
- Redis 清空后仍可从 MySQL 判断任务身份和执行历史。

