# M6 任务清单：最小动态工具型 Agent Loop

> 状态：T0 已完成，下一步 T1。M5 未退出；M6 依据 `EX-2026-11-10-M6` 仅以本地实验能力推进。按 T0 -> T4 顺序执行；每个任务先完成失败测试/验收，再实现和验证。只有用户可见的 T3/T4 强制真实浏览器证据；上一个退出门失败即停止。

## T0：冻结 M5 缺口与 M6 最小合同

- [x] ADR-0015 记录 M5 未退出、受限进入原因、到期日、默认关闭和不得使用 TEST 调参。
- [x] 冻结 MySQL 真源、最多 6 Step/2 次生成修复、默认关闭和 Java 确定性控制原则。
- [x] 建立 T0-T4 依赖表，把高相关 schema/API/UI/测试合并为纵向任务并明确不做通用框架。
- [x] 冻结三业务动作、稳定终止原因、Run 状态、调用前硬门和 M6 晋级可计算口径；澄清只作为终止结果，不建设多轮状态机。
- [x] 运行 `git diff --check` 和文档引用/术语一致性检查；复核本清单后提交独立 T0 commit。

**退出门**：例外不降低任何质量阈值；Roadmap、Constraints、ADR、PLAN/TODO/ACCEPTANCE 无冲突；不存在需要占位实现才能解释的合同。现有浏览器基线只记录为非阻断观察，运行时代码未改时不因既有 favicon/匿名 401 阻塞 T0。

## T1：建立 MySQL Run 真源与无费用生命周期

- [ ] 先写迁移约束、Run 状态/CAS、请求幂等、owner 隔离、去敏 Trace 和 API 合同失败测试。
- [ ] 用一份前进迁移建立最小 `agent_run`、`agent_step`、`tool_call`；Observation 保存为 Step/ToolCall 的去敏摘要和结果引用，不另建通用事件系统。
- [ ] 只用一个 Agent store Port/Adapter 和一个 `AgentApplicationService` 完成创建、领取、追加、终止及 owner-scoped 读取；Run 冻结 policy、模型/Prompt/检索/证据门版本和完整预算快照。
- [ ] 只增加创建 Run 与读取 Run/轨迹两个 owner API，不增加暂停/改单步/重放等控制 API；本任务不执行 Tool/模型调用、不增加前端入口，外部调用增量必须为 0。
- [ ] 完成定向测试、`server\mvnw.cmd test`、迁移集成测试、文档和独立提交；记录耗时与未执行项。

**退出门**：MySQL 可完整回放无费用生命周期；重复创建返回同一 Run；Redis 清空无影响；跨 owner、非法状态和敏感字段均被拒绝；没有 Tool/模型调用。

## T2：接入三动作动态循环与全部调用前硬门

- [ ] 先写“检索后回答 / 检索后扩窗再回答 / 证据不足拒答或请求澄清”三类失败场景，以及越权、非法 Schema、超预算和重复推进在外部调用前归零的测试。
- [ ] 只新增一个策略 Port；策略读取 Goal、历史 Observation 和剩余预算并返回版本化结构动作，不保存 chain-of-thought。
- [ ] Tool Registry 用枚举加显式 `switch` 白名单分发现有混合检索、Segment 扩窗、答案生成/证据门；答案动作复用现有生成、解析和 `AnswerEvidenceGate`，不复制第二套回答管线。
- [ ] 数据库 CAS Worker 每次只推进一个安全 Step；每次调用前校验 owner、Schema、白名单、step/deadline/token/费用/修复次数和幂等键，并持久化 ToolCall 状态、耗时、结果引用和去敏 Observation。
- [ ] 完成三路径 application/持久化集成测试、`server\mvnw.cmd test`、相关 `verify -Pintegration`、文档和独立提交；记录外部调用增量与未知费用。

**退出门**：至少三类 Observation 产生不同后续动作并可回放；事实 Claim 仍经现有证据门；任何越权、非法或超预算动作在调用前终止；工具不能直接访问未授权数据库、网络或文件。

## T3：证明恢复故障矩阵并在现有问答页回放

- [ ] 对已在 T2 生效的硬门执行故障矩阵：step/deadline/token/费用/修复次数边界、RUNNING/UNKNOWN ToolCall、重复 Worker、进程重启、Redis 清空和 owner 越权。
- [ ] 证明重启只从最后已提交安全 Step 继续，完成 ToolCall 不重复，高成本 UNKNOWN 不自动重放；无法安全恢复时以稳定终止原因停止。
- [ ] 在现有 `AnswerWorkspace` 增加默认关闭的 Agent 实验开关、Run 状态轮询和折叠轨迹；复用现有答案/Citation 展示，不新增 Agent 工作台或假数据。
- [ ] 页面显示已知/未知预算消耗、终止原因、权限/预算拒绝和 `NEEDS_CLARIFICATION` 提示；未知成本不得显示为 0，内部思考/Prompt/原始响应不得返回。
- [ ] 完成定向/集成/前端 `npm run check`、桌面与 320px 真实浏览器三路径/恢复回放、Console/Network 检查、运行手册和独立提交。

**退出门**：恢复、幂等、权限和全部预算硬门可重复验证；页面能回放至少三条真实路径；任何越权、超预算或 UNKNOWN 自动重放都直接失败。

## T4：完成 DEV/已冻结子集 A/B、晋级裁决和 M6 退出

- [ ] 只窄扩展现有 M5 Evaluation Runner，以执行模式和 `agentPolicyVersion` 区分 Agent 候选；不新建第二套 Runner、数据集或报告体系。
- [ ] 冻结 Agent 候选与 policy version，在冻结 DEV 上与 M5 非 Agent 基线同题运行；只有已有且在候选运行前冻结的 challenge/复杂题子集才能进入正式比较，不读取历史 TEST 逐题内容调参。
- [ ] 报告硬门通过率、P95 延迟、平均/未知成本及三类路径分布；随机项至少三轮并报告均值/样本标准差。
- [ ] Java 确定性计算 Roadmap 8.3 前两条数值规则；第三条只有在运行前已冻结复杂题子集、显著收益口径和条件路由规则时才可参与晋级，否则只报告观察结果。
- [ ] 无论 8.3 哪条命中，未同时满足 M5 原 Claim 支持/引用定位硬门时默认开关保持关闭。
- [ ] 完成后端快速/完整集成、前端、安全、变更覆盖率和真实浏览器桌面/移动回放。
- [ ] 回填 Roadmap/证据，负责人审阅后决定退出、继续实验或关闭；独立提交。

**退出门**：三类路径、恢复、权限/预算拒绝和同题 A/B 均有证据；达到晋级条件且负责人明确批准后才退出。
