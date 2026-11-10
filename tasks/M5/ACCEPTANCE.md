# M5 验收记录：冻结基线与最小评测流水线

> 状态：T0-T4 已完成；T5 已生成正式 DEV/TEST 基线，但严格 Claim-Evidence 支持率未达到退出门，M5 阻断且不得进入 M6。本文按 T0-T5 持续追加实际命令、浏览器证据、候选 commit 和未执行项。没有通过当前任务退出门时不得进入下一任务。

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
| 严格 Claim-Evidence 支持率 | 整份答案的每个 required fact 都由至少一个合格 Claim 及其绑定引用覆盖；只有自身覆盖至少一个 required fact、引用通道/时间窗合法且不含 forbidden claim 的 Claim 计入分子；遗漏任一 required fact 时该答案的 Claim 均不计支持 | 已发布的全部事实 Claim；无 Claim 的拒答不进入此分母 |
| 拒答正确率 | unanswerable 题得到 `REFUSED`，且不是依赖错误伪装 | approved unanswerable 答案题 |
| 可回答状态命中率 | answerable 题得到 `ANSWERED` | approved answerable 答案题 |
| 恢复成功率 | M4 冻结可恢复样本最终成功且零重复高成本调用 | 全部预先冻结的可恢复样本 |

`ERROR`、`UNKNOWN`、timeout、坏 JSON 和预算耗尽分别计数，不进入“正确拒答”。价格缺失为 `estimatedCost=null` 和 `priceStatus=UNAVAILABLE`，不记为 0。检索同配置至少 2 次并要求逐题 rank 一致；答案同配置至少 3 次，报告均值、样本标准差（n-1）和逐题 outcome 分布。

2026-11-10 基线后纠偏：原实现错误地要求每个 Claim 分别重复包含题目的全部 required facts，使正常的“一个 Claim 对应一个事实”答案必然记为 0。上述公式明确 required facts 的完整性在整份答案上判断，Claim 自身仍必须绑定合法证据；硬门、分母和精确事实匹配均未降低。

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

## T4 双审完成，冻结受阻

已使用 5 条短时、权利字段明确的视频建立 dev/test 视频隔离，生成 120 条检索真实草稿（48/48/24）和 30 条答案真实草稿（25 可回答、5 拒答）。Audacity 与 ASL 原片无音轨，严格使用画面/OCR；其余样本使用既有复核上下文或少量 TeleSpeechASR 辅助标注。原视频、ASR、OCR、问题全文和人工工作表全部留在 Git 忽略的 `benchmark/local/`。

`zzm` 与 `qq` 分别完成全部 150 条题的独立复核，全部批准且无空项。合并后结构校验输出 `VALID`，但冻结前运行时预检发现两个不能降低的真实性门：Audacity 和 ASL 视频无音轨，而现有管线对无 transcript 明确返回 `ASR_EMPTY`；题目中的 `expectedHits.segmentId` 仍是生成器的稳定 UUID5 占位符，不是 MySQL 中已发布的 `VideoSegment`。因此 manifest 保持 `draft`，T4 未退出，T5 未运行。

真实 headless Edge 登录后显示 9 个既有评测数据集，Console error/warning 为 0；CDP 320px 下 `innerWidth=clientWidth=scrollWidth=320`。双审子任务没有修改前端运行时代码，浏览器检查用于证明工作台无回归。

## T4 替代素材与预算计量硬门

2026-11-09 以两条短时中文 Wikidata 教学视频替换无音轨素材，并通过真实 Vite 工作台提交分析。156466 ms 图书素材和 276637 ms 限定符素材均进入 `READY`，分别发布 3/5 个 Segment；限定符素材 5 次 ASR 的 `input_units=276637 ms`、`output_units=524 Unicode 字符`，10 次 OCR 的 `input_units=4099200 像素`、`output_units=360 字符`，不同量纲未相加裁决。页面时间轴可见，Console warning/error 为 0。

ASR 默认单次输出率门为 1200 字符/分钟，因此单个 120 秒输入最多 2400 字符；边界单元测试证明 2400 通过，超限响应以不可重试 `OUTPUT_LIMIT` 失败。全局执行限制升级为 `m4-limits-v3`，ASR、OCR、Embedding、Answer 分别聚合输入/输出计量。真实验收同时覆盖后续 Job 完成复用中 Context：发布保留 owner、media、pipeline 和 Context version CAS，不再错误要求当前 Job 必须等于 `source_job_id`。

| 验证 | 实际结果 |
| --- | --- |
| `server\mvnw.cmd test` | 387 tests，0 failure/error，1 个既有条件 skip；JaCoCo 与 ArchUnit 通过 |
| `VideoContextPersistenceAdapterIT` | 9/9 通过；包含后续 Job 发布复用中 Context 的持久化回归 |
| `JdbcExecutionBudgetStoreIT,AsrRecoveryIT` | 4/4 通过；operation 聚合与 ASR 审计恢复通过 |
| `client\npm.cmd run check` | 18 files / 115 tests，typecheck、零 warning lint、coverage、生产构建通过 |
| Compose + 真实浏览器 | 两条替代素材 READY；3/5 Segments；Console warning/error 为 0 |

本门仅解除替代素材解析和预算计量阻断。相关题目仍须绑定上述实际 Segment 并重算冻结 fingerprint，T4 尚未退出，T5 不得启动。

## T4 最终冻结结论

替代素材完成真实解析后，全部题目重新绑定已发布 Segment 并由 `zzm`、`qq` 双人批准。`0.2.0` 于 2026-11-09 冻结：DEV 为 3 条视频、72 道检索题和 18 道答案题，fingerprint `98667e464bd7099736b91c32ee2ca1013df3d330c7d136bc3a7777be9e8c31ca`；TEST 为 2 条视频、48 道检索题和 12 道答案题，fingerprint `d190636e0efaabd3cc9cb16a68854c2033a4079ca8e6feb1eeb4e9a36167e502`。22 个唯一 expected Segment 全部存在于 MySQL，T4 阻断已解除并进入 T5。

## T5 正式基线与效果门结论

候选 `07dfb15` 使用 execution fingerprint `15342a7f88f1a893fdcb8696c4c24587bf971bd38360e611052d67c12026dd71`。2026-11-10 先完成 DEV 三轮，再首次导入并完成 TEST 三轮；正式命令 1/1 通过，六轮共 450 个 CaseResult，耗时 10 分 53 秒。TEST 没有参与此前候选调参。

| Split | 指标 | 三轮结果 | 门槛/结论 |
| --- | --- | --- | --- |
| DEV | Recall@3 | 100%、100%、100% | 确定性一致 |
| DEV | 严格 Claim-Evidence 支持率 | `null`、0%、`null` | 无 Claim 的轮次保留空分母；失败 |
| DEV | 引用定位率 | `null`、100%、`null` | 不把空分母伪造为 0 |
| TEST | Recall@3 / Hit@1 / MRR@3 | 三轮均 100% | 通过 85% 检索门 |
| TEST | 严格 Claim-Evidence 支持率 | 三轮均 0% | **低于 85%，阻断 M5** |
| TEST | 引用定位率 | 三轮均 100% | 通过 95% 门 |
| TEST | 拒答正确率 | 三轮均 100% | 通过 |
| TEST | 可回答状态命中率 | 三轮均 20% | 仅 2/10 可回答题每轮得到 ANSWERED |

TEST 三轮 outcome 总计为 150 `COMPLETED`、27 `REFUSED`、3 `UNKNOWN`；其中答案题为 6 `COMPLETED`、27 `REFUSED`、3 `UNKNOWN`。稳定错误审计为 3 个 `ANSWER_DEPENDENCY_UNAVAILABLE`。这说明检索证据可定位，但非 Agent 回答基线大量拒答，且已发布 Claim 均未满足冻结 required facts；不得把检索通过等同于回答通过。

正式六轮共记录 450 次成功查询 Embedding。答案调用中，DEV 为 47 次成功、5 次 UNKNOWN、1 次失败且 1 题在调用前预算耗尽；TEST 为 33 次成功、2 次 UNKNOWN、1 次失败。成功 DeepSeek 调用合计输入 40532 tokens、输出 17041 tokens；按用户提供的普通时段单价估算约 ¥0.27，BGE-M3 因未提供单价继续标记 unknown。正式报告的价格注册表状态保持 `UNAVAILABLE`，没有写成 0。

六份 `benchmark/reports/m5-v0.2.0-{dev,test}-r{1,2,3}.json` 均通过 `evaluation-report.schema.json`；敏感键扫描未发现 question、answer、Prompt、transcript、ownerId、quote、raw response、object key 或 Embedding。失败候选 `a9af8d5` 的三个 DEV Run 因 answer UUID JDBC 绑定缺陷保留为 FAILED，没有删除或覆盖；修复和新 series 证据见 `tasks/M5/T5_EVIDENCE.md`。

2026-11-10 使用同一系统账号 `m1-reviewer` 在真实应用内浏览器完成最终验收。页面分别显示冻结 DEV `90/90 approved` 与 TEST `60/60 approved`、各自 fingerprint、候选 `07dfb15`、完整十项执行版本和相同 execution fingerprint；DEV 三个成功 Run 与三个历史失败 Run、TEST 三个成功 Run 均可追溯。人工展开检索成功、可回答、拒答和 `UNKNOWN / ANSWER_DEPENDENCY_UNAVAILABLE` 样本，报告显示聚合分子/分母；两个 TEST Run 对比能计算指标、延迟、Token、调用和成本差值。导出的 `0708583a-5c43-4989-ab1c-754d008117c2` JSON 含 60 个去敏结果，dataset/execution fingerprint 与页面一致，敏感键扫描通过。刷新后运行事实恢复；320×720 视口下 `innerWidth=320`、文档 `scrollWidth=clientWidth=305`，数据集导航使用局部横向滚动而页面无横向溢出；Console warning/error 为 0。浏览器未发起新的评测或产生额外模型费用。

可靠性维度按已冻结合同复用 M4 版本化摘要，不复制故障执行器：20/20 样本恢复成功，任务丢失、重复高成本调用和非法推进均为 0；原始分母、命令和环境边界保留在 `tasks/M4/ACCEPTANCE.md`。

## T5 完整质量门

| 命令/检查 | 实际结果 | 耗时/结论 |
| --- | --- | --- |
| `server\mvnw.cmd test` | 394 项，0 failure/error，1 项既有环境条件 skip；6 项 ArchUnit 和 JaCoCo 门通过 | 1 分 29 秒，通过 |
| `client\npm.cmd run check` | typecheck、零 warning lint、18 files / 115 tests、coverage 和生产构建通过 | 约 23 秒，通过 |
| M5 数据工具与冻结校验 | 11/11 unittest；DEV/TEST 必须作为同一 release 一起校验，输出 `VALID` | 通过 |
| 容器化 `mvn verify -Pintegration -Dbenchmark.commit=07dfb15` | 394/394 单元测试；90 项集成测试 0 failure/error、2 项需显式付费发布开关而 skip；M4 样本 20/20 | 10 分 37 秒，通过 |
| `git diff --check` | 无 whitespace error；仅 Git 的 LF/CRLF 提示 | 通过 |
| `npm audit --audit-level=high` | 退出码 0；无 high/critical，仍有 10 个 moderate 开发工具链漏洞 | high 门通过，moderate 保留 |
| OWASP Dependency-Check 12.2.2 | NVD 首分页连续 31 次以上请求失败，未取得漏洞库；为避免无上限重试后中断 | **未形成通过结论** |
| Gitleaks 8.30.1 `detect --redact` | 104 commits 中命中 1 个 `generic-api-key`：`JdbcEvaluationStoreIT` 的固定文件名夹具；人工核对不是凭据，但未增加 suppression，也未改写历史 | **工具门未通过，保留假阳性结论** |

宿主机裸 `verify -Pintegration` 首次因未加载 `.env` 端口/凭据失败；加载配置后仅剩宿主机缺少 FFmpeg/FFprobe、健康探针映射和候选 commit 前置条件。Winget 下载 FFmpeg 两次被网络中断，因此最终使用项目已有 `videopilot-maven-ffmpeg` 镜像，在 `videopilot_m5_verify` 隔离库和 Compose 内网中重跑完整集合并通过；没有跳过失败测试或触碰正式评测库。完整门按用户确认保留重新生成的两份 M3 报告。

退出结论：正式评测流水线与数据治理可复现，但回答效果硬门失败。M5 不能退出，M6 不能开始；不得修改已使用的 TEST、删除失败题或降低 85% Claim 支持门。
