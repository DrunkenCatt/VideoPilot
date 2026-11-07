# M5 可复现评测报告

## 目标与边界

M5 T2 在 T1 的 MySQL 评测真源上增加确定性聚合，不引入可插拔评测框架、第二套模型调用账本或故障编排器。报告由 `evaluation_case`、`evaluation_case_result`、`evaluation_run` 和答案关联的既有 `model_call` 事实重建。

## 数据与计分

- 答案题必须声明 `answerability`；`requiredFacts`、`forbiddenClaims`、`allowedChannels` 和 `expectedHits` 只保存在 MySQL，不进入脱敏 API。
- 严格 Claim 支持要求绑定引用非空、必需事实出现在 Claim/引用证据中、禁止事实不出现在 Claim 中、引用通道允许，并命中冻结的 expected segment。
- 引用定位按引用 segment 是否命中冻结 expected segment 计数；没有 expected segment 的工程种子允许运行，但以 `MISSING_EXPECTED_HIT` 排除，不伪造分母。
- MRR 分子是浮点倒数和；空分母的 `value` 为 `null`。每个指标保存 `numerator`、`denominator` 和非零 `exclusions`。
- `UNKNOWN`、`ERROR`、依赖失败和坏响应保留在 outcome 分布中。它们不会变成正确拒答；无发布 Claim 不进入 Claim 分母。

## 重复与版本硬门

- 仅检索数据集至少运行 2 次；包含答案题的数据集必须运行 3 次。
- 每个后续 repetition 与已完成 repetition 比较检索题 outcome/rank；漂移时以 `NON_DETERMINISTIC_RETRIEVAL` 失败，不混合报告。
- 检索实际 formula/embedding/chunk 以及答案实际 prompt/model/embedding/formula/chunk 必须与声明版本一致；首个漂移 case 先持久化，再以 `VERSION_DRIFT` 终止 run。
- series 报告保存每个指标的均值、样本标准差（n-1）、有效 repetition 数和 outcome 分布。

## 调用与可靠性事实

- 评测调用显式把 `evaluationRunId/caseId` 传入生产检索与答案服务；检索 Embedding 和答案模型调用仍写入既有 `model_call`，不建立第二套账本。
- 延迟包含逐题端到端运行事实；Token、调用数、调用状态和成本从当前 run 精确关联的全部 `model_call` 聚合。
- 任一关联调用缺价格或成本时，报告 `estimatedCost=null`、`unavailablePriceCalls>0`，不把未知价格算成零。
- 可靠性 case 只接受 M4 已冻结事实枚举：丢任务恢复、零重复高成本调用、非法迁移拒绝和恢复成功。T2 读取复核后的 pass/fail 事实形成比率，不重新注入故障。

## API 与脱敏


`GET /api/v1/evaluation-runs/{runId}/report` 返回版本、指标、调用汇总和 series 统计。响应不包含 owner、问题、答案、Prompt、原始引用、required facts、forbidden claims、转录或模型原始响应。owner 校验与 dataset/run/results 使用同一 application service 边界。
