# ADR-0009：分离 EvaluationRun 并冻结 dev/test 使用边界

## Status

Accepted

## Date

2026-11-08

## Context

M3 已有一个集成测试内的单视频 Runner，可以调用生产检索与回答代码并写脱敏报告，但它没有持久化数据集身份、逐题结果、重复运行或 test 使用历史。M5 需要让发布规模评测在进程重启后续跑，并证明 test 没有参与候选调参。

`AnalysisJob` 表达用户围绕一个视频发起的可恢复解析任务；评测则固定一个数据集和一组执行版本，顺序运行多条既有视频的检索、回答或可靠性 case。把二者塞入同一状态机会让 AnalysisJob 的 owner、媒体、阶段、Replay 和 Outbox 语义承担不属于它的实验身份，也会诱发第二套通用工作流抽象。

## Decision

### 1. EvaluationRun 是独立、最小的持久化聚合

- MySQL 使用 `evaluation_dataset`、`evaluation_case`、`evaluation_run` 和 `evaluation_case_result` 保存数据集、运行、逐题结果和版本指纹。
- `EvaluationRun` 不复用 `AnalysisJob` 状态机、RabbitMQ、Outbox 或 Manual Replay。一个数据库 CAS Worker 领取 `REQUESTED`/可恢复的运行，按稳定 case 顺序执行；进程重启从缺失的 case result 继续。
- Controller 只创建运行并立即返回，不在线程内执行发布规模评测。Worker 仍调用生产 `RetrievalApplicationService`、`AnswerApplicationService` 和既有可靠性结果，不复制产品算法。
- Redis 和导出文件只可作为投影；最终指标必须由 MySQL 逐题事实重建。

### 2. 运行身份分离配置、系列和请求幂等

- `datasetFingerprint` 由规范化 case 内容、split、review、schema 和 dataVersion 计算。
- `executionFingerprint` 由 dataset fingerprint、代码 commit、pipeline、Prompt、模型、Embedding、Chunk、检索公式、证据门、limits 和配置版本计算。
- 同一 execution fingerprint 可以属于同一 series 的多个 `repetitionIndex`，用于计算远端 LLM 的均值、样本标准差和状态分布；不能用 execution fingerprint 唯一约束阻止重复测量。
- 创建请求使用 owner + `Idempotency-Key` 去重。相同键和相同请求返回原 series；相同键不同请求拒绝。每个 series/repetition/case 只有一个结果。
- timeout/UNKNOWN 不自动重试为同一 repetition 的成功结果。需要重复测量时创建预先声明的下一 repetition，保留原 UNKNOWN。

### 3. dev/test 隔离由服务端硬门裁决

- `dev` 可用于修复缺陷和形成新版本指纹；`test` 只能在数据集 `FROZEN`、全部计分题双人 `APPROVED`、候选 execution fingerprint 完整且未基于该 test 调整时运行。
- dev/test 按视频或主题隔离。`DRAFT`、单人复核、`DISPUTED` 或跨 split 重复内容不能进入正式 test 分数。
- test 首次运行时间、dataset/execution fingerprint 和解封操作者写入不可变审计事实。test 结果出现后修改候选或标注，必须产生新的代码/配置或 dataVersion，不能覆盖历史运行。
- 前端禁用非法操作只用于解释；服务端必须独立拒绝。

### 4. 报告只公开脱敏、可重建事实

- 逐题公开 Trace 只包含 case ID/type/outcome、rank、Claim/Citation 数、延迟、Token、已知/未知成本和稳定错误码。
- API、日志、Git 报告和比较视图不返回问题、回答、required facts、forbidden claims、Prompt、转录、OCR、模型原始响应、对象 key、向量或凭据。
- 原视频、完整转录、工作标注和细粒度人工复核只保存在被 Git 忽略的 `benchmark/local/`。

## Alternatives Considered

- **把评测建成 AnalysisJob 新 mode**：会混淆单视频解析身份和多 case 实验身份，并扩大现有状态机、Replay 与消息合同。
- **同步 HTTP 跑完整数据集**：120 道检索和重复答案运行会超过合理请求时限，断线后也无法可靠判断已完成 case。
- **新建评测 RabbitMQ/Outbox 框架**：M5 本地/单服务器只有一个顺序 Worker，数据库领取即可满足恢复与真源要求；第二套消息框架没有当前收益。
- **execution fingerprint 全局唯一**：会阻止同配置重复测量，无法报告随机性。
- **只用文件保存结果**：文件丢失、并发写和部分运行无法提供可靠事实，也不能支持 owner-scoped 浏览器查询。
- **允许前端控制 test 解封**：UI 不是权限或数据治理硬门，调用者可以绕过。

## Consequences

- M5 需要少量独立评测表和一个 CAS Worker，但不引入通用任务引擎或第二套 Broker。
- test 运行是有审计含义的受控动作；修改冻结数据需要发布新 dataVersion。
- 同配置的多个 repetition 会增加远端 LLM 调用，运行前必须显示次数并遵守既有预算；M5 默认最多 3 次。
- 后续 M6/M8 可以引用 EvaluationRun 做 A/B，但不能修改历史基线或绕过本 ADR 的 split/冻结规则。

## Validation

- 重复创建请求只产生一个 series；三个 repetition 可共享 execution fingerprint 且结果互不覆盖。
- 进程在任一 case 后终止，重启只执行缺失 case，已完成高成本调用增量为零。
- DRAFT、未双人批准、跨 split 泄漏或版本指纹不完整的 test 运行被服务端拒绝。
- Redis 和导出文件清空后，逐题结果与全部指标可从 MySQL 重建。
- API、浏览器和 Git 报告不包含问题、回答、Prompt、转录或模型原始响应。
