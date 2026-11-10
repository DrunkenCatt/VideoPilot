# ADR-0012：READY 后查询不继承 AnalysisJob 执行期限

## Status

Accepted（正式评测的预算聚合作用域由 ADR-0013 补充）

## Date

2026-11-10

## Context

ADR-0008 将 deadline、调用次数、输入/输出计量和已知费用作为 Job 级硬门，
ADR-0011 又将不同操作的计量拆分。该设计能限制视频解析、恢复和自动重试，但
`ExecutionBudgetPolicy` 把从 `analysis_job.created_at` 起算的 6 小时 deadline 同时用于
READY 后的同步检索与问答。

真实浏览器验收中，17 小时和 20 小时前已经 READY 的 Job 在新问题进入
Query Embedding 前分别得到 `BUDGET_EXHAUSTED:m4-limits-v3:DEADLINE`。两次请求均未
到达外部供应商，其中一个 Job 的 Answer 调用为 18/20，另一个为 0/20。这不是费用、
调用次数或 API Key 余额耗尽，而是分析执行期限错误地变成了视频问答有效期。

MVP 要求已完成解析的视频可以持续问答，同时必须保留确定性的调用次数、计量和
费用保护。

## Decision

- AnalysisJob 的 deadline 只约束后台解析、索引、Recovery、Retry 和 Replay attempt。
- Job 进入可查询状态后，同步 Retrieval Query Embedding 与 Answer 不再检查
  `analysis_job.created_at + jobDeadline`。
- READY 后查询仍按原 Job 聚合并执行 Embedding/Answer 的调用次数、输入单位、输出
  单位和已知费用上限；unknown 价格语义、并发限制、幂等和证据硬门保持不变。
- 索引 Embedding 仍属于后台执行，继续检查 deadline。Query Embedding 与索引
  Embedding 仍共享 `EMBEDDING` 次数和计量预算，不新建第二套额度。
- 历史 `BUDGET_EXHAUSTED:*:DEADLINE` 记录不改写。新行为由现有
  `m4-limits-v3` 的检查作用域修正表达，不提高任何数值阈值。

## Alternatives Considered

- 把 Job deadline 从 6 小时提高到数天：只延后同一错误，视频最终仍会永久失效。
- 每次提问自动创建新 AnalysisJob：制造重复 Job、上下文和索引身份，破坏现有真源。
- READY 后取消全部预算：会失去调用次数、Token 和费用保护。
- 为问答建立新预算框架或独立数据库聚合：MVP 不需要额外抽象；现有 Job 聚合只需
  区分执行期限与交互调用。

## Consequences

- 已 READY 的旧视频可以继续提问，不需要修改创建时间、重放分析或提高预算。
- 达到 Embedding/Answer 次数、计量或已知费用上限后仍会确定性返回
  `BUDGET_EXHAUSTED`。
- 长期使用同一视频最终可能达到 20 次 Answer 或其他现有限额；调整这些数值仍是
  独立发布配置决策，不属于本 ADR。
- 如果后续需要按用户、日或 AgentRun 计费，应新增 ADR 和独立可审计窗口，不能复用
  AnalysisJob deadline 冒充。

## Validation

- 单元测试使用已超过 deadline 的 Usage，证明交互式 Embedding/Answer 可以继续，
  后台调用仍因 `DEADLINE` 失败。
- 单元测试证明交互调用仍受 CALL_COUNT、INPUT_UNITS、OUTPUT_UNITS 和 KNOWN_COST
  限制。
- Retrieval 与 Answer 应用测试证明它们使用交互预算入口，索引和解析继续使用后台入口。
- 真实浏览器在旧 READY Job 上提交最小问题，确认经过 Qdrant 检索、模型调用和证据门，
  页面不再出现 deadline 或向量库降级标记，并由 MySQL Trace 与服务端日志交叉验证。
