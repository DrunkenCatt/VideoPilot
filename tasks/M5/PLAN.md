# M5 实施计划：冻结基线与最小评测流水线

## 目标与当前基线

M5 把 M3 已有的单视频工程种子和测试内 Runner，收敛成可复现、可追溯、可在浏览器检查的非 Agent 基线。最终基线仍是“一次混合检索 + 一次结构化回答 + Java 确定性证据验证”，不在本里程碑优化成 Agent，也不因当前回答仅命中 2/6 而放宽证据门。

当前可直接复用：生产 `RetrievalApplicationService`、`AnswerApplicationService`、模型调用审计、版本字段、M3 的 12 道检索/6 道回答工程种子、检索指标计算和脱敏报告。当前缺口是：只有一个本机集成测试负责播种和写文件；没有持久化 `EvaluationRun`/逐题 Trace；回答指标未覆盖 required facts、forbidden claims、拒答正确率和严格 Claim-Evidence 支持；数据集没有正式 dev/test 冻结；没有评测 API 和用户界面；运行指纹、重复运行和报告导出尚未形成稳定合同。

## 最小 MVP 决策

- 只支持一个固定 `BASELINE_V1` 方案：调用现有检索、回答和证据门生产代码。M5 不实现 Runner Registry、Metric SPI、插件式 Judge、实验编排器或通用 DAG。
- MySQL 只新增 `evaluation_dataset`、`evaluation_case`、`evaluation_run`、`evaluation_case_result` 四类必要事实；聚合报告由逐题结果确定性计算，不为每种指标建表，不把 Redis 或报告文件作为真源。
- 一个 `EvaluationApplicationService` 负责数据集查询、创建运行、逐题执行、聚合和报告读取；POST 只持久化 `REQUESTED` 运行并立即返回，由一个数据库 CAS 领取 Worker 顺序续跑未完成 case。进程重启后从 MySQL case result 恢复，不复用 AnalysisJob 状态机，也不预建第二套 MQ/Outbox 框架。
- 数据导入使用现有 JSON Schema/JSONL，增加一个受控的本地导入入口或命令；不建设在线标注平台。原视频、完整转录、问题正文和工作标注留在忽略的 `benchmark/local/`，Git 只保存 Schema、脱敏样例、版本清单和脱敏报告。
- Dataset fingerprint 由规范化 case 内容、split、复核状态和 schema 版本确定；Execution fingerprint 固定包含 dataset、代码 commit、pipeline、prompt、model、embedding、chunk、retrieval formula、evidence gate、limits 和配置版本。任一缺失即拒绝启动正式运行。
- `dev` 可重复运行；`test` 只有在数据集冻结且全部题目双人批准后才能运行。服务端强制 split/frozen/review 规则，前端只展示和发起合法操作，不能靠 UI 隐藏实现隔离。
- 随机性只来自远端 LLM：检索指标要求同配置重复结果一致；答案指标按同一 execution fingerprint 下受控的 repetition index 至少运行 3 次，报告均值、标准差、每题状态分布和 UNKNOWN，不自动重试 timeout，也不把拒答或错误改写成成功。
- 前端只增加一个 `evaluation` feature 和一个工作台区域，覆盖数据集、运行、逐题 Trace、指标/成本对比和 JSON 导出；不引入全局 store、图表库、报表设计器或实时 WebSocket/SSE。
- M5 不以调参为默认交付。若 dev 暴露缺陷，修复必须形成新版本指纹并重跑；test 解封后不得再基于其结果修改当前候选。

## 依赖图

```text
T0 冻结数据/运行/指标合同
  -> T1 打通一个持久化 dev 评测纵切
      -> T2 补齐全量指标、重复运行与版本指纹
          -> T3 交付评测查看、对比与导出界面
              -> T4 扩样、双人复核并冻结 dev/test
                  -> T5 运行冻结基线并完成 M5 退出
```

先用少量已批准 dev case 验证生产流水线和持久化合同，再投入发布规模标注，避免 120 道题完成后才发现 Schema 或指标无法表达实际结果。test 的运行严格晚于合同、Runner 和候选版本冻结。

## 任务表

| ID | 任务 | 合并的高相关内容 | 依赖 | 预计规模 |
| --- | --- | --- | --- | --- |
| T0 | 冻结 M5 验收合同与数据治理 | Dataset manifest、split/冻结/复核规则、指标公式、版本指纹、脱敏边界和 REST/TypeScript 合同共同决定后续真源，合为一次合同冻结 | 无 | M |
| T1 | 交付可持久化的单个 dev 评测纵切 | 四张最小表、JSONL 导入、一个 application service、生产检索/回答调用、逐题 Trace 和聚合读取是一条不可拆的最小闭环 | T0 | M |
| T2 | 固化指标、重复运行与可复现报告 | 检索确定性、答案均值/方差、Claim 支持、拒答正确率、可靠性样本、成本和完整版本指纹必须基于同一 case result 口径计算 | T1 | M |
| T3 | 交付评测工作台 | 数据集版本、运行发起、逐题 Trace、两次运行对比和 JSON 导出共享同一只读投影与用户路径，避免拆出多套页面/API | T2 | M |
| T4 | 扩展、复核并冻结发布数据集 | 5 条视频、120 道检索题、答案/拒答/可靠性题、双人复核、dev/test 主题隔离和 manifest 冻结属于同一数据发布动作 | T3 | L（以标注为主） |
| T5 | 生成正式非 Agent 基线并完成退出 | 冻结 test 单次解封、重复性/随机性报告、人工抽检、浏览器追溯、完整质量门和运行手册必须基于同一候选 commit | T4 | M |

详细验收标准、验证方式和预计改动区域见 `tasks/M5/TODO.md`。

## 检查点

### Checkpoint A：评测真源可回放（T0-T1）

- 数据集、运行、逐题结果和版本指纹来自 MySQL；删除 Redis/本地导出文件不改变最终指标。
- Runner 直接调用生产检索/问答 application service，不复制融合公式、Prompt 生成、引用验证或成本统计。
- 未冻结、未批准或 split 不合法的数据不能进入正式分数；运行失败保留已完成逐题结果和明确终止原因。

### Checkpoint B：指标与用户路径可信（T2-T3）

- 同 dataset/execution fingerprint 的检索重复运行产生一致排名与确定性指标；远端 LLM 指标按重复运行报告均值、标准差和错误分布。
- 报告至少覆盖 `Recall@3`、`Hit@1`、`MRR@3`、严格 Claim-Evidence 支持率、引用定位率、拒答正确率、延迟、Token、调用数和已知/未知成本。
- 浏览器能从报告追溯到 dataset manifest、case ID、逐题 Trace 和所有版本指纹，并导出同一脱敏 JSON；刷新不丢运行事实。

### Checkpoint C：冻结发布基线（T4-T5）

- 发布集至少 5 条权利明确的教学视频、120 道检索题和 30 道答案题；检索精确/模糊/OCR 约为 40%/40%/20%，答案同时包含可回答和不可回答题，正式计分题均双人复核。
- dev/test 按视频或主题隔离；候选版本只用 dev 调整，test 在代码、配置和版本指纹冻结后运行，不因 test 结果原地调参。
- 人工抽检、完整自动化门和真实浏览器验收基于同一候选 commit；不足 85% 的发布指标如实阻断效果宣称，不降低证据门或修改标注迁就结果。

## 明确不做

- 不实现 AgentRun、AgentStep、ToolCall、Observation、自动补检、Critic、Agent A/B 或候选晋级；这些属于 M6/M8。
- 不建设在线标注系统、多人协作平台、数据仓库、实验平台、通用工作流引擎或独立评测微服务。
- 不新增 Metric/Runner/Reporter 插件体系、通用 `BaseRepository`、每张表一套 CRUD service、对象存储报告层或 Redis 指标真源。
- 不引入 LLM-as-Judge 作为硬门，不让模型判定引用存在性、Claim 支持、split 合法性、预算或是否通过发布门。
- 不把未授权视频、完整问题/答案/转录、Prompt、供应商原始响应、Embedding 或凭据提交到 Git或返回给非本机 owner。
- 不为了达到指标修改 test 标注、删除失败题、提高 TopK 掩盖 Recall@3，或降低现有证据阈值与质量门。

## 主要风险与控制

| 风险 | 影响 | 控制 |
| --- | --- | --- |
| 当前 M3 Runner 藏在集成测试并直接播种数据库 | 无法稳定复用或从 UI 运行 | T1 抽出单个生产 application service；测试只负责装配和断言 |
| 发布规模标注耗时远大于代码实现 | 里程碑被假数据或凑数拖偏 | T0 先冻结 rubric；T4 按视频分批复核，未达规模不运行正式 test |
| 当前回答基线仅命中 2/6 | 诱发降低证据门或把拒答算成功 | 状态命中、严格支持、拒答和依赖错误分开报告；硬门保持不变 |
| test 泄漏到 Prompt/权重调整 | 基线失去可信度 | 服务端冻结/解封审计，按视频/主题隔离，报告记录首次 test 运行和候选指纹 |
| 远端 LLM 随机与 timeout | 单次分数不可复现 | 至少 3 次答案运行，报告均值/标准差/状态分布；UNKNOWN 不自动重试 |
| 评测表和服务过度抽象 | M5 变成实验平台建设 | 固定 baseline type、四张事实表、一个 service、一个报告 DTO；真实第二种 Runner 出现前不抽象 |
| 报告包含问题或模型原文 | 泄露受限资料和隐私 | API/导出仅含 case ID、状态、计数、分数、版本和去敏错误；本地细节留在忽略目录 |
| 把可靠性评测重新实现成第二套故障框架 | 重复 M4 并增加维护面 | 复用 M4 冻结样本/结果口径，只关联 case 与结果摘要，不复制 Recovery 编排 |

## T0 必须冻结的参数

- Dataset ID/version/fingerprint、schema version、split、冻结状态、视频/主题隔离规则、source rights、复核者数量和解封审计。
- 检索题、答案题、拒答题和可靠性题的正式计分条件；required facts、forbidden claims、证据窗和一段对齐邻域的唯一解释。
- `Recall@3`、`Hit@1`、`MRR@3`、严格 Claim-Evidence 支持率、引用定位率、拒答正确率、均值/样本标准差、UNKNOWN 和成本 unknown 的公式。
- 固定 baseline type、每次运行的重复次数、并发度、deadline、test 首次运行规则和部分失败后的终止/恢复语义。
- Dataset/Execution fingerprint 字段清单、报告 JSON schema、敏感字段清单和导出保留策略。
- 最小 API：数据集列表/详情、创建运行、运行列表/详情、逐题结果、两运行对比和报告导出；不暴露通用 CRUD。
