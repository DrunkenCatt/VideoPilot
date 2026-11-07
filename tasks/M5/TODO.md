# M5 任务清单：冻结基线与最小评测流水线

> 执行规则：按 T0 -> T5 顺序推进。每个任务先补失败验收/测试，再实现；未通过当前 Checkpoint 不进入下一阶段。每项交付均记录实际命令、耗时、通过/失败和未执行项。

## T0：冻结 M5 验收合同与数据治理

**验收标准**

- [x] 在 `tasks/M5/ACCEPTANCE.md` 写出 dataset 导入/冻结、dev/test 隔离、逐题失败、重复运行、test 首次运行、报告导出、owner 隔离和敏感字段的 Given/When/Then 场景。
- [x] 冻结 retrieval/answer/refusal/reliability case 的计分条件和公式，明确未批准/单人复核/disputed/UNKNOWN/成本未知如何进入分母与报告。
- [x] 冻结 Dataset fingerprint、Execution fingerprint 和报告 JSON 合同；版本至少覆盖代码 commit、pipeline、prompt、model、embedding、chunk、retrieval formula、evidence gate、limits 和配置。
- [x] 冻结最小 REST/TypeScript 合同与 `BASELINE_V1` 运行状态；若 EvaluationRun 身份、test 解封或版本复用形成难逆决策，新增 ADR-0009。
- [x] 盘点现有 M3 Runner、M4 可靠性样本和版本审计，形成“直接复用 / M5 补齐 / 明确不做”矩阵。

**验证**

- [x] 合同测试先失败后通过；`server\mvnw.cmd test`、`client\npm.cmd run check`、`git diff --check`。
- [x] 人工审查确认没有 Agent/插件框架、第二套 Job/Outbox 或未授权数据进入范围。

**预计改动区域**：`tasks/M5/`、`docs/adr/`（仅必要时）、`benchmark/schemas/`、M5 API 合同测试。
**依赖**：无。

## T1：交付可持久化的单个 dev 评测纵切

**验收标准**

- [x] 用一份前进 Flyway 迁移建立 `evaluation_dataset`、`evaluation_case`、`evaluation_run`、`evaluation_case_result`；唯一约束保护 dataset version/fingerprint、创建请求幂等、同系列 repetition index 和 case result 幂等，不阻止同 execution fingerprint 的受控重复运行。
- [x] 一个 `EvaluationApplicationService` 完成受控 JSONL 导入、运行创建、逐题执行、聚合读取和失败终止；POST 只持久化并返回，由一个 MySQL CAS 领取 Worker 顺序续跑，Controller 不执行长任务且只调用该 service。
- [x] Runner 直接调用现有 `RetrievalApplicationService` 与 `AnswerApplicationService`，不复制检索公式、Prompt、引用硬门、模型调用或成本逻辑。
- [x] 用 M3 已有小种子打通“导入 -> 运行 -> 逐题结果 -> 聚合报告”真实 MySQL/Qdrant/模型路径；重复请求不重复创建同一 run 或高成本调用。
- [x] Redis 清空、导出文件删除或进程重启后，运行状态、逐题结果和聚合指标仍可从 MySQL 读取；Worker 从未完成 case 续跑，不重复已完成的高成本调用。

**验证**

- [x] 定向单元/Controller/迁移测试和真实依赖 IT 通过；至少包含 owner 隔离、非法 split、未批准题、重复请求和中途失败。
- [x] `server\mvnw.cmd test`；本任务相关的 `server\mvnw.cmd verify -Pintegration` 测试集。

**预计改动区域**：`evaluation/application`、`evaluation/port`、`infrastructure/evaluation`、M5 Flyway 迁移、Web 合同/Controller。
**依赖**：T0。

### Checkpoint A：评测真源可回放

- [x] MySQL 是 Dataset/Run/CaseResult/版本指纹唯一真源，Redis 和报告文件可删除重建。
- [x] 小型 dev 纵切真实调用生产检索和回答代码，并持久化明确的成功、拒答、UNKNOWN 或失败结果。
- [x] 未批准、split 不合法、指纹缺失和跨 owner 请求被服务端拒绝。

## T2：固化指标、重复运行与可复现报告

**验收标准**

- [ ] 在同一聚合器中实现并测试 `Recall@3`、`Hit@1`、`MRR@3`、严格 Claim-Evidence 支持率、引用定位率和拒答正确率；每个指标保存分子、分母和排除原因。
- [ ] 复用 `model_call`/运行事实聚合延迟、Token、调用数及已知/未知成本；不把缺价、timeout、坏 JSON、拒答或无发布 Claim 记为零成本/成功。
- [ ] 同 execution fingerprint 的检索至少重复 2 次并断言逐题 rank/指标一致；答案以同一 series 下不同 repetition index 至少重复 3 次，报告均值、样本标准差、逐题状态分布和 UNKNOWN。
- [ ] Execution fingerprint 缺任一冻结版本即拒绝正式运行；运行中版本漂移立即失败并保留已完成 Trace，不混合计算。
- [ ] 可靠性数据只复用 M4 冻结样本和结果摘要，形成丢任务、重复高成本调用、非法迁移、恢复成功率等报告，不复制故障编排框架。

**验证**

- [ ] 公式边界、空分母、部分失败、版本漂移、随机性统计和确定性重复测试通过；临时 mutation 能让关键指标测试失败。
- [ ] `server\mvnw.cmd test`；真实小样本连续运行产生可比较、可重建的脱敏报告。

**预计改动区域**：`evaluation/application` 指标/报告、持久化查询、运行 DTO、benchmark 报告 schema。
**依赖**：T1。

## T3：交付评测工作台

**验收标准**

- [ ] 新增一个 `features/evaluation` 纵切，在现有工作台显示 dataset version/split/frozen/review、运行状态、候选版本指纹和最近报告。
- [ ] 用户可合法发起 dev 运行并查看逐题 Trace；test 未冻结/未双人批准/候选未冻结时按钮不可用且服务端同样拒绝。
- [ ] 同页对比两次运行的指标、延迟、Token、调用数和成本 unknown；只展示差值，不做“提升/通过”的模型推断。
- [ ] JSON 导出内容与服务端报告一致，仅含 case ID、状态、计数、指标、版本和去敏错误，不含问题/答案/Prompt/转录/原始响应。
- [ ] loading/empty/partial/error/refresh/认证过期、320/768/1024px、键盘焦点和无横向溢出有组件与真实浏览器证据。

**验证**

- [ ] API client、状态守卫、迟到响应、对比和导出测试通过；`client\npm.cmd run check`。
- [ ] `server\mvnw.cmd test`；真实浏览器完成“查看 dataset -> 发起 dev run -> 展开 case -> 对比 -> 导出 -> 刷新恢复”，Console error/warning 为 0。

**预计改动区域**：`client/src/api/evaluation.ts`、`client/src/features/evaluation/`、现有工作台装配、Evaluation Web DTO/Controller。
**依赖**：T2。

### Checkpoint B：指标与用户路径可信

- [ ] 确定性指标可重复，随机性指标有重复次数、均值/标准差和状态分布。
- [ ] 浏览器中的任一汇总值可追溯到 dataset、case results 和完整 execution fingerprint。
- [ ] 导出、刷新、Redis 清空不改变报告；前端没有复制指标公式或 test 隔离规则。

## T4：扩展、复核并冻结发布数据集

**验收标准**

- [ ] 使用至少 5 条权利明确、主题足以隔离 dev/test 的教学视频；本地 manifest 记录指纹、授权、时长、语言、主题和 split，原视频/完整转录不入 Git。
- [ ] 完成至少 120 道检索题，精确/模糊/OCR 目标比例约 40%/40%/20%；每题有证据窗、通道、expected hits、可回答性和稳定 case ID。
- [ ] 完成至少 30 道覆盖可回答/不可回答的答案题，以及复用 M4 的可靠性集；答案题包含 required facts、forbidden claims、required citations。
- [ ] 正式计分题全部双人复核；分歧题保持 disputed 不计分，不为凑数量强制批准；自动生成题必须逐题人工核对。
- [ ] dev/test 按视频或主题隔离，生成不可变 dataset manifest/fingerprint 并冻结；冻结后变更必须产生新 dataVersion，不原地覆盖。

**验证**

- [ ] Schema、重复 case、跨 split 泄漏、比例、复核数、证据窗和 fingerprint 校验全部通过。
- [ ] 人工抽查每类题和每条视频；记录抽检人、样本量、问题和裁决，不把未复核题计入分母。

**预计改动区域**：忽略的 `benchmark/local/` 工作标注、可提交的脱敏 manifest/report、数据校验器和任务证据。
**依赖**：T3。

## T5：生成正式非 Agent 基线并完成 M5 退出

**验收标准**

- [ ] 在候选 commit、配置和版本指纹冻结后运行 dev 基线，再首次解封运行 test；记录 test 未用于此前调参的审计事实。
- [ ] 生成检索、答案/拒答、引用/Claim 支持、可靠性、延迟/Token/调用/成本的版本化报告；随机性指标包含至少 3 次运行的均值/标准差。
- [ ] 人工抽检逐题 Trace 和报告导出，浏览器可追溯 dataset fingerprint、候选 commit、全部执行版本、case ID 和聚合分子/分母。
- [ ] 新增 `docs/M5_RUNBOOK.md`，记录导入、冻结、运行、失败恢复、test 使用纪律、报告导出、脱敏和回滚；README/Roadmap 只声明实际结果。
- [ ] 达不到 `Recall@3 >= 85%`、严格 Claim-Evidence 支持率 `>= 85%` 或引用定位率 `>= 95%` 时如实阻断效果宣称；不得删除失败题、修改 test 或降低硬门。

**验证**

- [ ] `server\mvnw.cmd test`、`server\mvnw.cmd verify -Pintegration`、`client\npm.cmd run check`、`git diff --check` 全部记录结果与耗时。
- [ ] 执行架构、状态机、changed-code coverage、Gitleaks、OWASP Dependency-Check、`npm audit --audit-level=high`；不可用项记录为未形成结论，不写成通过。
- [ ] 真实浏览器复验 dataset、run、trace、compare、export、刷新、test 隔离、窄屏和敏感字段；Console error/warning 为 0。

**预计改动区域**：`benchmark/reports/` 脱敏产物、`docs/M5_RUNBOOK.md`、`tasks/M5/ACCEPTANCE.md`、README/Roadmap 完成记录。
**依赖**：T4。

### Checkpoint C：M5 退出

- [ ] 同一配置能重复生成一致的确定性指标；随机性指标报告均值、标准差和失败分布。
- [ ] dev/test 隔离、双人复核、冻结/解封和人工抽检均有可审计证据。
- [ ] 浏览器报告可追溯到 Dataset 与全部版本指纹；MySQL 真源、脱敏和 owner 边界通过验证。
- [ ] 项目负责人审阅指标、遗留风险和未执行项并明确批准后，才进入 M6 和 Agent 效果宣称。
