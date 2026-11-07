# M5 验收记录：冻结基线与最小评测流水线

> 状态：T0-T2 已完成，T3-T5 待实施；本文按 T0-T5 持续追加实际命令、浏览器证据、候选 commit 和未执行项。没有通过当前任务退出门时不得进入下一任务。

## 1. 当前基线与 M5 缺口

M3 已有生产 `RetrievalApplicationService`、`AnswerApplicationService`、确定性引用硬门、模型调用审计和一个 `RetrievalBenchmarkLocalIT`。它在单条 7 分钟中文教学视频上运行 12 道检索题与 6 道回答题：检索 `Recall@3=100%`，回答期望状态命中 2/6，已发布引用定位率 100%。这只是单人复核的 dev 工程种子，不是正式发布结论。

M5 必须补齐数据集/运行身份、dev/test 隔离、双人复核、发布规模、逐题持久化、重复运行统计、完整版本指纹、脱敏报告和浏览器追溯。当前回答覆盖不足必须进入基线，不能通过降低证据门、删除失败题或把依赖错误算作拒答来隐藏。

## 2. 复用、补齐与不做

| 能力 | 直接复用 | M5 补齐 | 明确不做 |
| --- | --- | --- | --- |
| 检索/问答 | 生产 application service、证据门、降级路径 | EvaluationRun 调用与逐题结果 | 第二套 RAG、Prompt 或引用验证 |
| 可靠性 | M4 冻结故障样本与结果口径 | 关联 case ID 和报告摘要 | 第二套故障注入/Recovery 框架 |
| 模型审计 | `model_call` Token、延迟、状态、成本 unknown | run/case 关联与聚合 | 复制模型原始响应或 Prompt |
| 版本 | pipeline/model/embedding/retrieval/limits 等已有字段 | dataset/execution fingerprint 完整性硬门 | Provider Registry、候选晋级 |
| 运行 | MySQL 真源与数据库 CAS 模式 | 独立最小 EvaluationRun + 顺序 Worker | 复用 AnalysisJob、评测 MQ/Outbox、通用 DAG |
| 前端 | 现有工作台、认证和统一状态 | evaluation feature、Trace、对比、导出 | 独立 Admin SPA、全局 store、图表库 |

## 3. 冻结身份与状态合同

### 3.1 Dataset

- `DatasetSplit`：`DEV`、`TEST`。
- `DatasetStatus`：`DRAFT`、`FROZEN`。
- `datasetFingerprint`：对 dataVersion、schemaVersion、split、source rights、视频/主题标识、规范化 case 内容和 review 状态按稳定顺序计算 SHA-256。
- 正式计分只包含 `review.status=approved` 且 `reviewerCount>=2` 的 case；`pending`、`disputed`、单人复核和 Schema 非法 case 保留但不进正式分母。
- test 必须按视频或主题与 dev 隔离；同一内容指纹跨 split 出现即冻结失败。

### 3.2 EvaluationRun

- `BaselineType` 仅有 `BASELINE_V1`：一次生产混合检索、一次生产结构化回答、一次 Java 确定性证据验证。
- `RunStatus`：`REQUESTED -> RUNNING -> COMPLETED|FAILED`。进程退出不覆盖状态；Worker 通过 lease/CAS 重新领取并跳过已有 case result。
- 创建请求以 owner + `Idempotency-Key` 去重；同键异请求拒绝。
- 一个 series 预声明 1-3 个 repetition。同 series 可共享 execution fingerprint，但 `(seriesId,repetitionIndex,caseId)` 唯一。
- timeout/UNKNOWN 不在同一 repetition 自动重试；后续 repetition 是独立观测，不覆盖原结果。

### 3.3 Execution fingerprint

正式运行必须包含以下非空版本并计算稳定 SHA-256：

1. dataset fingerprint；
2. code commit；
3. pipeline version；
4. prompt version；
5. model version；
6. embedding version；
7. chunk version；
8. retrieval formula version；
9. evidence gate version；
10. limits version；
11. evaluation configuration version。

运行中任一实际版本与声明版本不同，当前 run 失败并保留已完成 Trace，不允许混合计算。

## 4. 冻结指标公式

所有比率同时保存 numerator、denominator、value 和排除原因；空分母返回 `value=null`，不能伪造 0。

| 指标 | 分子 | 分母 |
| --- | --- | --- |
| `Recall@3` | Top 3 至少命中一个 approved expected hit 的可回答检索题 | approved、answerable 且有 expected hit 的检索题 |
| `Hit@1` | rank=1 的上述检索题 | 同 `Recall@3` 分母 |
| `MRR@3` | 对 rank 1-3 累加 `1/rank` | 同 `Recall@3` 分母 |
| 引用定位率 | 引用时间窗合法，且与 required citation 窗或冻结的一段对齐邻域重叠 | 已发布的全部 `CitedEvidence` |
| 严格 Claim-Evidence 支持率 | Claim 的每个必需事实均由其绑定引用的允许通道/时间窗原文支持，且不包含 forbidden claim | 已发布的全部事实 Claim；无 Claim 的拒答不进入此分母 |
| 拒答正确率 | unanswerable 题得到 `REFUSED`，且不是依赖错误伪装 | approved unanswerable 答案题 |
| 可回答状态命中率 | answerable 题得到 `ANSWERED` | approved answerable 答案题 |
| 恢复成功率 | M4 冻结可恢复样本最终成功且零重复高成本调用 | 全部预先冻结的可恢复样本 |

`ERROR`、`UNKNOWN`、timeout、坏 JSON 和预算耗尽分别计数，不进入“正确拒答”。价格缺失为 `estimatedCost=null` 和 `priceStatus=UNAVAILABLE`，不记为 0。检索同配置至少 2 次并要求逐题 rank 一致；答案同配置至少 3 次，报告均值、样本标准差（n-1）和逐题 outcome 分布。

## 5. 脱敏报告合同

允许公开：dataset/run ID、fingerprint、split/status、case ID/type/outcome、rank、Claim/Citation 数、指标分子/分母/value、延迟、Token/计量、调用数、已知/未知成本、稳定错误码和全部版本号。

禁止公开：用户名/ownerId、问题/回答文本、required facts、forbidden claims、引用原文、Prompt、完整转录/OCR、模型原始响应、对象 key、Embedding、堆栈、连接串或凭据。细粒度工作标注只存在于 `benchmark/local/`。

## 6. T0 可执行场景

### 场景 A：前后端资源路径一致

**Given** 同一 datasetId/runId；**When** Java 与 TypeScript 生成 dataset、run、results、report 和 comparison 路径；**Then** 路径均位于 `/api/v1/evaluation-*`，创建运行位于 dataset 资源下。

### 场景 B：test 冻结硬门

**Given** TEST 数据集；**When** 状态不是 FROZEN、approved 数不等于 total、存在单人复核/分歧或 execution version 缺失；**Then** 服务端拒绝创建正式运行，前端禁用不能替代该判定。

### 场景 C：重复测量不覆盖

**Given** 一个完整 execution fingerprint 和 repetitions=3；**When** Worker 执行三轮；**Then** 三轮共享版本身份但 repetition index 不同，任一 UNKNOWN 保留，case result 互不覆盖。

### 场景 D：进程退出后续跑

**Given** 已持久化部分 case result；**When** Worker/应用退出并重启；**Then** 新 lease 只执行缺失 case，完成 case 的远端调用增量为 0，最终报告由 MySQL 重建。

### 场景 E：脱敏 Trace

**Given** 任一检索或回答结果；**When** 浏览器读取 Trace 或导出 JSON；**Then** 只有 case ID、状态、计数、指标、成本和版本，不包含问题、回答、Prompt、转录或原始响应。

### 场景 F：T0 浏览器无回归

**Given** 运行中的 M4 工作台；**When** 桌面和手机访问；**Then** 既有登录/工作台正常、无横向溢出、Console 无 error/warning，且 T0 不提前展示未实现的评测运行入口。

## 7. T1-T5 场景索引

| 任务 | 必须证明的场景 |
| --- | --- |
| T1 | JSONL 导入；owner；幂等 series；CAS 单领取；中断续跑；Redis/导出丢失；生产检索/回答；逐题失败 |
| T2 | 指标边界/空分母；版本漂移；检索重复确定性；答案 3 次均值/样本方差；UNKNOWN；成本 unknown；M4 可靠性摘要 |
| T3 | dataset/run/results/report/compare/export；test 禁用与服务端拒绝；loading/empty/partial/error；刷新；320/768/1024；键盘/可访问树 |
| T4 | 5 条视频；120 检索题；30 答案题；40/40/20；双人复核；split 泄漏；冻结 fingerprint；人工抽检 |
| T5 | 冻结 candidate；dev 后 test 首次运行；正式指标/随机性/成本报告；浏览器追溯；安全与完整质量门 |

## 8. T0 RED/GREEN 证据

### RED

| 命令 | 实际结果 |
| --- | --- |
| `server\mvnw.cmd -q -Dtest=M5ContractTest test` | 失败：`M5ApiContracts` 不存在，测试编译失败 |
| `client\npm.cmd exec vitest run src/api/contracts.test.ts` | 失败：`evaluationDatasetPath is not a function`；其余 13 项通过 |

### GREEN

| 命令 | 实际结果 |
| --- | --- |
| `server\mvnw.cmd -q -Dtest=M5ContractTest test` | 通过：4 项 M5 Java 合同测试 |
| `client\npm.cmd exec vitest run src/api/contracts.test.ts` | 通过：14 项前端合同测试，其中 3 项为 M5 |

## 9. T0 浏览器、完整门与退出结论

2026-11-08 使用真实 Vite `http://localhost:5173/` 和 Compose server 验收。浏览器沿用本机已登录的 `m1-reviewer` 会话，只读取页面，不提交分析、Replay 或其他写操作。T0 不改变运行时代码；本轮验证既有工作台无回归且未提前暴露 M5 功能。

| 浏览器检查 | 实际观察 | 结果 |
| --- | --- | --- |
| 1440x900 桌面 | `innerWidth=1440`、`clientWidth=scrollWidth=1425`；h1“视频分析工作台”；可靠性、上传、媒体、提交、任务和时间线区域均在可访问树中 | 通过 |
| 375x812 手机 | `innerWidth=375`、`clientWidth=scrollWidth=360`、main 328px；六个主要区域仍可读 | 通过 |
| 320x720 最窄视口 | `innerWidth=320`、`clientWidth=scrollWidth=305`、main 273px；链接、刷新、上传和媒体选择控件仍为原生可交互元素 | 通过 |
| Console | 三档 reload 后 warning/error 均为 0 | 通过 |
| 范围约束 | 页面仍为 M3/M4 工作台，正文不含“评测”，没有提前显示 dataset/run/report 控件 | 通过 |

自动化与构建：

| 命令 | 实际结果 | 耗时 |
| --- | --- | ---: |
| `server\mvnw.cmd -q -Dtest=M5ContractTest test` | 4 项通过 | 定向 GREEN |
| `client\npm.cmd exec vitest run src/api/contracts.test.ts` | 14 项通过 | 1.2 s |
| `server\mvnw.cmd test` | 364 项通过、0 failure/error、1 项既有环境条件 skip；6 项 ArchUnit 和 JaCoCo 80% 门通过 | 85.4 s |
| `client\npm.cmd run check` | typecheck、零 warning lint、16 files/104 tests、coverage、生产构建全部通过 | 35.6 s |
| 前端覆盖率 | Statements 86.66%、Branches 80.75%、Functions 87.50%、Lines 91.77% | 通过 |

提交前 mutation 将 `MAX_REPETITIONS` 从 3 临时改为 4，`M5ContractTest.requiresAnIdempotentFrozenBaselineRunWithBoundedRepetitions` 按预期 1/4 失败；恢复生产常量后 4/4 重新通过，mutation 未存活。

T0 没有迁移、Controller、Worker 或运行时装配变化，因此没有重复执行 `verify -Pintegration`；T1 引入 MySQL 表和 Worker 时必须执行定向及完整集成门。本轮未运行 OWASP/Gitleaks，它们属于每个实现任务提交前的安全检查和 T5 完整退出门，不将未运行记为通过。

退出结论：合同、ADR-0009、数据集/报告 Schema、验收矩阵、前后端合同测试、完整快速门和真实浏览器基线均通过。没有新增依赖、Agent/插件框架、第二套 Job/MQ、suppression 或跳过测试；T0 可以提交并进入 T1。
