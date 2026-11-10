# ADR-0013：正式评测预算按候选系列隔离

## Status

Accepted

## Date

2026-11-10

## Context

ADR-0012 允许 READY 视频持续交互，但 Query Embedding 与 Answer 仍按 AnalysisJob
聚合调用次数和计量。M5 冻结数据集复用已经完成解析的 Job；同一 Job 上的历史人工问答、
旧候选评测和新候选评测因而共享额度。当前五个评测 Job 已分别累计 15--18 次 Answer，
而一个新候选的三次重复每个视频需要 18 次 Answer。新候选会在外部调用前被历史用量拦截，
无法得到独立、可比较的正式评测结果。

提高全局上限或删除历史调用会掩盖预算含义并破坏审计；为每个候选复制 AnalysisJob、上下文和
向量索引又会引入不必要的数据身份和抽象。

## Decision

- 普通 READY 视频查询继续按 AnalysisJob 聚合，行为与 ADR-0012 相同。
- 当 Query Embedding 或 Answer 带有 `evaluation_run_id` 时，预算只聚合同一
  `series_id`、同一 AnalysisJob 的 `model_call`。
- 同一系列的三次重复共享预算；不同系列（因此也包括不同候选执行指纹）的历史用量互不消耗。
- `evaluation_run_id` 仍由现有应用调用显式传递并写入 `model_call`，不新增表、不复制 Job、
  不引入线程上下文或第二套预算策略。
- 调用数、输入/输出单位、已知费用阈值、unknown 价格语义和拒绝行为全部保持不变。
- 历史 `model_call`、EvaluationRun 和报告不修改、不清零；只有新调用的预算查询作用域变化。

## Alternatives Considered

- 提高 Job 的 Answer/Embedding 上限：会同时放宽生产交互硬门，且旧候选继续污染新候选。
- 删除或重置旧 `model_call`：破坏 MySQL 真源和审计历史。
- 每个候选复制 AnalysisJob 或重新解析视频：成本高，并产生与媒体固有产物无关的重复身份。
- 每次 repetition 单独给额度：三轮不再共享同一候选的费用硬门，无法约束完整正式评测。

## Consequences

- 新候选可以在不修改冻结数据集和解析产物的前提下执行三轮正式评测。
- 一个系列内仍会在达到原硬门后停止；不能用新 repetition 绕过额度。
- 同一系列对不同 Job 分别计量，保持现有“单个视频输入”的预算语义。
- 若未来需要跨候选或按项目设置总费用上限，应另行设计上层评测活动预算，不能改变本 ADR 的
  候选可比性语义。

## Validation

- 单元测试证明历史 Job 已耗尽时，新评测系列使用自身用量，而普通交互仍被 Job 硬门拦截。
- JDBC 集成测试证明同一系列的多个 repetition 合并计量，同时排除普通调用和其他系列。
- Answer 与 Retrieval 回归测试证明普通请求仍走原入口。
- M5 正式执行前，以 DEV 三轮验证同系列共享预算；DEV 退出门通过后才允许执行 TEST 三轮。
