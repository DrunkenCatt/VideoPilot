# M6 任务清单：最小动态工具型 Agent Loop

> 状态：T3 已完成；T4 实现与代码质量门已通过。关闭本机代理后，宿主机和 Docker 均恢复
> DigiCert 正常证书链。负责人批准在上一组 `HTTP 503` 后以新幂等键重试，新的真实 DEV A/B
> 三轮均完成 90/90，检索指标稳定；但 Claim 严格支撑率均值仅 36.69%，引用定位率均值仅
> 74.74%，未满足 M5 原硬门，晋级裁决为 `M5_GATES_NOT_MET`。第一轮另有一条 BGE-M3
> `HTTP 400`，使成本完整性为 false。负责人已用 `EX-2026-11-12-M6-T5` 明确接受只在流程顺序上
> 优先完成 T5；T4/M6 仍未退出、不得晋级，Agent 和目标分析仍默认关闭。OWASP 仍由
> `EX-2026-11-11-M6-OWASP` 记录为未形成结论。

## T0：冻结 M5 缺口与 M6 最小合同

- [x] ADR-0015 记录 M5 未退出、受限进入原因、到期日、默认关闭和不得使用 TEST 调参。
- [x] 冻结 MySQL 真源、最多 6 Step/2 次生成修复、默认关闭和 Java 确定性控制原则。
- [x] 建立 T0-T4 依赖表，把高相关 schema/API/UI/测试合并为纵向任务并明确不做通用框架。
- [x] 冻结三业务动作、稳定终止原因、Run 状态、调用前硬门和 M6 晋级可计算口径；澄清只作为终止结果，不建设多轮状态机。
- [x] 运行 `git diff --check` 和文档引用/术语一致性检查；复核本清单后提交独立 T0 commit。

**退出门**：例外不降低任何质量阈值；Roadmap、Constraints、ADR、PLAN/TODO/ACCEPTANCE 无冲突；不存在需要占位实现才能解释的合同。现有浏览器基线只记录为非阻断观察，运行时代码未改时不因既有 favicon/匿名 401 阻塞 T0。

## T1：建立 MySQL Run 真源与无费用生命周期

- [x] 先写迁移约束、Run 状态/CAS、请求幂等、owner 隔离、去敏 Trace 和 API 合同失败测试。
- [x] 用一份前进迁移建立最小 `agent_run`、`agent_step`、`tool_call`；Observation 保存为 Step/ToolCall 的去敏摘要和结果引用，不另建通用事件系统。
- [x] 只用一个 Agent store Port/Adapter 和一个 `AgentApplicationService` 完成创建、领取、追加、终止及 owner-scoped 读取；Run 冻结 policy、模型/Prompt/检索/证据门版本和完整预算快照。
- [x] 只增加创建 Run 与读取 Run/轨迹两个 owner API，不增加暂停/改单步/重放等控制 API；本任务不执行 Tool/模型调用、不增加前端入口，外部调用增量必须为 0。
- [x] 完成定向测试、`server\mvnw.cmd test`、迁移集成测试、文档和独立提交；记录耗时与未执行项。

**退出门**：MySQL 可完整回放无费用生命周期；重复创建返回同一 Run；Redis 清空无影响；跨 owner、非法状态和敏感字段均被拒绝；没有 Tool/模型调用。

## T2：接入三动作动态循环与全部调用前硬门

- [x] 先写“检索后回答 / 检索后扩窗再回答 / 证据不足拒答或请求澄清”三类失败场景，以及越权、非法 Schema、超预算和重复推进在外部调用前归零的测试。
- [x] 只新增一个策略 Port；策略读取 Goal、历史 Observation 和剩余预算并返回版本化结构动作，不保存 chain-of-thought。
- [x] Tool Registry 用枚举加显式 `switch` 白名单分发现有混合检索、Segment 扩窗、答案生成/证据门；答案动作复用现有生成、解析和 `AnswerEvidenceGate`，不复制第二套回答管线。
- [x] 数据库 CAS Worker 每次只推进一个安全 Step；每次调用前校验 owner、Schema、白名单、step/deadline/token/费用/修复次数和幂等键，并持久化 ToolCall 状态、耗时、结果引用和去敏 Observation。
- [x] 完成三路径 application/持久化集成测试、`server\mvnw.cmd test`、相关集成检查、依赖安全记录、文档和独立提交；OWASP 因 NVD 无数据未形成通过结论，按负责人明确批准的 `EX-2026-11-11-M6-OWASP` 记录遗留风险后继续，不记作扫描通过。

**退出门**：至少三类 Observation 产生不同后续动作并可回放；事实 Claim 仍经现有证据门；任何越权、非法或超预算动作在调用前终止；工具不能直接访问未授权数据库、网络或文件。

## T3：证明恢复故障矩阵并在现有问答页回放

- [x] 对已在 T2 生效的硬门执行故障矩阵：step/deadline/token/费用/修复次数边界、RUNNING/UNKNOWN ToolCall、重复 Worker、进程重启、Redis 清空和 owner 越权。
- [x] 证明重启只从最后已提交安全 Step 继续，完成 ToolCall 不重复，高成本 UNKNOWN 不自动重放；无法安全恢复时以稳定终止原因停止。
- [x] 在现有 `AnswerWorkspace` 增加默认关闭的 Agent 实验开关、Run 状态轮询和折叠轨迹；复用现有答案/Citation 展示，不新增 Agent 工作台或假数据。
- [x] 页面显示已知/未知预算消耗、终止原因、权限/预算拒绝和 `NEEDS_CLARIFICATION` 提示；未知成本不得显示为 0，内部思考/Prompt/原始响应不得返回。
- [x] 完成定向/集成/前端 `npm run check`、桌面与 320px 真实浏览器三路径/恢复回放、Console/Network 检查、运行手册和独立提交。

**退出门**：恢复、幂等、权限和全部预算硬门可重复验证；页面能回放至少三条真实路径；任何越权、超预算或 UNKNOWN 自动重放都直接失败。

## T4：完成 DEV/已冻结子集 A/B、晋级裁决和 M6 退出

- [x] 只窄扩展现有 M5 Evaluation Runner，以执行模式和 `agentPolicyVersion` 区分 Agent 候选；不新建第二套 Runner、数据集或报告体系。
- [ ] 冻结 Agent 候选与 policy version，在冻结 DEV 上与 M5 非 Agent 基线同题运行；只有已有且在候选运行前冻结的 challenge/复杂题子集才能进入正式比较，不读取历史 TEST 逐题内容调参。
- [x] 报告硬门通过率、P95 延迟、平均/未知成本及三类路径分布；随机项至少三轮并报告均值/样本标准差。
- [x] Java 确定性计算 Roadmap 8.3 前两条数值规则；第三条只有在运行前已冻结复杂题子集、显著收益口径和条件路由规则时才可参与晋级，否则只报告观察结果。
- [x] 无论 8.3 哪条命中，未同时满足 M5 原 Claim 支持/引用定位硬门时默认开关保持关闭。
- [ ] 完成后端快速/完整集成、前端、安全、变更覆盖率和真实浏览器桌面/移动回放。
- [ ] 回填 Roadmap/证据，负责人审阅后决定退出、继续实验或关闭；独立提交。

当前阻断（2026-11-12）：人工复核窗口已明确为核心参考答案而非排斥真实补充 Claim 的 allowlist；
运行时仍由 `AnswerEvidenceGate` 对引用原文做精确/规范化绑定。最终完整门已恢复到 `4/6` 状态命中和
`13/13` 引用定位。本机代理关闭后 TLS 严格验证恢复；负责人批准对上一组 503 新建 series 后，三轮
均完成 90/90，召回与排序保持稳定，但 Claim 严格支撑率均值为 36.69%、引用定位率均值为 74.74%，
未满足 M5 原 85%/95% 硬门。Java 裁决 `ruleTwoMet=true`，但 `m5GatesMet=false`、
`promotionEligible=false`，原因 `M5_GATES_NOT_MET`；第一轮一条 BGE-M3 `HTTP 400` 还使
`costKnown=false`。按规则停止，不能因三轮执行完成而绕过原质量门。

**退出门**：三类路径、恢复、权限/预算拒绝和同题 A/B 均有证据；达到晋级条件且负责人明确批准后才退出。

## T5：补齐自动目标分析结果闭环

- [x] 以 `EX-2026-11-12-M6-T5` 记录 T4 效果门失败和负责人优先级决定；T4/M6 保持未退出且默认关闭。
- [x] 先写 RED 测试，再让 Worker 每 tick 最多从 MySQL 补齐一个 READY Job；稳定身份为
  `goal-analysis:<jobId>`，并发、重启和历史 READY Job 复用既有唯一约束与恢复语义。
- [x] 增加 owner 隔离的只读目标结果接口；严格解析 Run 的 `answer:<uuid>`，复用标准 Answer，自动结果
  从普通问答历史排除，拒答/失败不伪造答案。
- [x] 在现有 `AnswerWorkspace` 手动问答之前展示目标、等待/运行/回答/拒答/失败/关闭状态，并复用引用跳转；
  桌面和 320px 使用同一响应式页面。
- [x] 完成后端完整集成、真实浏览器桌面/320px、Console/Network/刷新/引用跳转和 npm 高危扫描；
  OWASP 依赖检查按负责人指示跳过并保持“未形成结论”，完成独立提交。

**退出门**：默认关闭时零新增调用；启用后新旧 READY Job 只生成一个目标 Run；MySQL 可恢复同一 Trace 与
Answer；owner 隔离、预算和证据门不变；前端不重复目标 Answer；T5 自身质量门全部通过。T4 的既有效果
缺口继续单独阻止 M6 晋级。

## T6：冻结模型驱动最短闭环合同

> 负责人已于 2026-11-13 批准冻结 T4 效果缺口并继续受限实验；`EX-2026-11-13-M6-T6-T10`
> 不豁免任何质量门，也不得把本次推进解释为 M6 已退出。

- [x] 新增 ADR，冻结正常路径和一次修复路径：`PLAN -> RETRIEVE -> GENERATE_AND_VERIFY -> CRITIQUE -> [RETRIEVE|EXPAND_WINDOW] -> GENERATE_AND_VERIFY -> terminal`。
- [x] 冻结 Planner 输出 `action/query/expectedFacts`，Critic 输出 `PASS` 或 `action/query|window/missingRequirements/unsupportedClaims`；禁止任意工具名、SQL、URL、文件路径和预算字段。
- [x] 明确 Critic 只判断问题覆盖和语义相关性，`AnswerEvidenceGate` 继续独占引用存在性与 Claim-Evidence 发布硬门；模型不得覆盖 Java 裁决。
- [x] 保持最多 6 Step、2 次答案生成和 1 次 Critic 修复；定义非法 Schema、无效参数、预算耗尽、UNKNOWN 和修复后仍失败的稳定终止原因。
- [x] 写出正常、补检、扩窗、拒答四条验收 Trace，以及模型调用数上限：正常最多 3 次（Planner、Executor、Critic），修复最多 4 次（再执行一次 Executor）。
- [x] 运行文档术语/引用检查和 `git diff --check`，更新 PLAN/TODO/ACCEPTANCE 后独立提交。

**依赖**：T5；负责人明确批准在 T4 未退出时继续受限实验。

**预计文件**：`docs/adr/0017-*.md`、`tasks/M6/PLAN.md`、`tasks/M6/TODO.md`、`tasks/M6/ACCEPTANCE.md`。

**退出门**：合同能解释每个模型调用、Tool 参数来源、持久化边界和终止条件；没有通用 DAG、多 Agent、动态插件或 chain-of-thought 存储。

## T7：让检索与扩窗证据真正进入 Executor

- [x] 先写失败测试：初检 Chunk 引用、扩窗时间范围和修复后的累积证据必须进入 Answer Prompt；禁止 `GENERATE_AND_VERIFY` 忽略 Step 引用后按原问题静默重检。
- [x] 使用现有 `agent_step.result_reference` 保存有界 Segment 列表和 `window:start:end`；不新增通用 Artifact 表，不保存完整 Chunk、Prompt 或模型原文。
- [x] 给现有 Answer application service 增加最小的“已选择证据范围”输入，复用当前 evidence 加载、Prompt、模型 Adapter、发布和 Evidence Gate，不复制第二套回答管线。
- [x] 第二次生成按 Step 顺序合并原证据与新增证据并去重；owner/job 绑定、最大证据数、Prompt 长度和预算在模型调用前校验。
- [x] 完成 Answer/Agent 定向单测、MySQL 引用恢复集成测试、文档、`server\mvnw.cmd test` 和独立提交。

**依赖**：T6。

**预计文件**：`AgentApplicationService`、`AnswerApplicationService`、二者测试及 M6 验收文档；如现有字段容量经测试不足，再单独评审前进迁移，不预建新表。

**退出门**：同一 Run 重启后能仅凭 MySQL Step 重建相同证据选择；扩窗内容确实出现在模型 Prompt；重复 Worker 不重复外部调用。

## T8：用结构化 Planner 驱动首次检索

- [x] 先写失败测试：不同 Planner JSON 产生不同 query/expectedFacts；非法 JSON、未知 Action、超长 query、越权参数和超预算均在 Retrieval 前终止且外部调用为 0。
- [x] 增加一个窄 Agent 决策模型 Port/Adapter，复用既有 SiliconFlow 配置、调用审计和预算，不让 Agent application 依赖供应商 DTO，不创建 Planner 类层级。
- [x] 新增 `PLAN` 动作；模型只可选择 `RETRIEVE`、`NEEDS_CLARIFICATION` 或 `REFUSE`，Java 解析严格 Schema 后持久化去敏计划摘要。
- [x] Retrieval 必须使用 Planner query，而不是固定使用 `run.goal()`；expectedFacts 随 Trace 进入后续 Critic，但不得作为未经验证的事实发布。
- [x] 完成 Planner/Policy/Adapter 定向测试、模型调用审计集成测试、文档、后端快速门和独立提交。

**依赖**：T7。

**预计文件**：一个 Agent 决策 Port、一个基础设施 Adapter、`AgentPolicy/AgentApplicationService`、相关测试和配置；优先复用现有 JSON/HTTP 基础设施。

**退出门**：至少两种目标得到不同检索参数；Planner 输出只决定受限动作和参数，Java 仍控制权限、预算、状态与终止。

## T9：用结构化 Critic 驱动一次定向修复与重答

- [x] 先写失败测试：首答完整时 `PASS -> FINISH`；缺事实时按 Critic query 补检；缺上下文时按 Critic window 扩窗；第二次仍不合格时稳定拒答且不进入第三次生成。
- [x] 复用 T8 的决策模型 Port 增加 `CRITIQUE` 请求；输入仅含目标、expectedFacts、已验证首答、引用摘要和证据摘要，不包含 chain-of-thought 或不必要的完整转录。
- [x] Critic 严格返回 `PASS` 或一个白名单修复动作；Java 校验 query/window 必须属于当前 owner/job、在视频时长内且预算允许，再执行一次补检或扩窗。
- [x] 第二次 `GENERATE_AND_VERIFY` 携带首答、missingRequirements、unsupportedClaims 和累积证据；最终仍由现有 `AnswerEvidenceGate` 发布或拒答，Critic 无权放行硬门失败。
- [x] 更新 RuleBased Policy 为版本化的有界动作图，删除只看 `EVIDENCE:single/multiple` 的伪动态分支；不增加递归、通用工作流引擎或第二个答案实现。
- [x] 完成正常/补检/扩窗/拒答、恢复、幂等、预算和 UNKNOWN 测试，更新 Trace 文档，运行后端快速门与相关集成门后独立提交。

**依赖**：T8。

**预计文件**：T8 决策 Port/Adapter、`AgentPolicy`、`AgentApplicationService`、`AnswerApplicationService` 和相关测试；若超过约 5 个生产文件，优先合并数据记录而不是再加抽象层。

**退出门**：测试能证明 Critic 缺口改变下一 Tool 的名称或参数，新增证据进入第二次 Prompt；正常路径至少 3 次模型调用、修复路径最多 4 次，且所有调用可审计、可限额、可安全停止。

## T10：回放、两轮闭环、A/B 与完整质量门

### T10A：让 Answer 结构化失败可诊断

- [x] 先写失败测试，把 HTTP 200 后的空 `content`、usage 缺失、completion Token 越界和 `content` 非 JSON 分成稳定子错误；HTTP、超时、连接、截断的既有分类保持不变。
- [x] 只审计 `finishReason`、content 字符数、usage 字段存在性和 JSON 可解析性等脱敏元数据；不保存 Prompt、模型原文、完整转录或思维链。
- [x] 完成 Adapter/Answer 定向测试、后端快速门、文档和独立提交。

**退出门**：下一次 HTTP 200 结构化失败可以唯一归入一个子错误，且日志和数据库不含模型原文或凭据；未通过即停止，不做重测。

### T10B：固定条件受控重测

- [x] 固定同一 READY Job、问题、`agent-policy-v3-loop / agent-planner-v3 / agent-critic-v4`、模型、检索版本和预算，新建一组至少三轮 series；保留原 `2/3` series，不用新结果覆盖第三轮失败。
- [x] 每轮报告 Step/LLM、Answer 子错误、Token、费用和端到端延迟；若只有单轮异常且随后三轮均成功，只能记录为“供应商结构化输出存在随机波动”，不能宣称已修复。
- [x] 若再次出现相同子错误，先建立稳定复现并修 Prompt/Adapter；若错误类型不同，分别记录，禁止笼统归因于“问题偏离”。
- [x] 完成重测记录、Roadmap/ACCEPTANCE 回填和独立提交。

**退出门**：形成可复核的稳定性结论；任何未解释失败都阻止进入 T10C。

### T10C：证明真实两轮修复路径

- [ ] 自动测试分别构造 `CRITIQUE -> RETRIEVE` 与 `CRITIQUE -> EXPAND_WINDOW`：第一次 Answer 只覆盖部分 `expectedFacts`，Critic 明确返回缺口，第二次 Answer 使用原证据和新增证据；最终通过或稳定拒答。
- [ ] 断言修复路径恰好为 `PLAN -> RETRIEVE -> GENERATE_AND_VERIFY -> CRITIQUE -> RETRIEVE|EXPAND_WINDOW -> GENERATE_AND_VERIFY`，即 6 Step/4 次生成式 LLM；Embedding 单独审计，不混入该计数。
- [ ] 真实浏览器选择一个首轮召回不足、但视频其他片段可补齐的问题，至少跑通一条两轮 Trace；验证 Critic query/window 改变下一 Tool 参数、新增 Segment 进入第二次 Prompt、刷新后仍可从 MySQL 回放。
- [ ] 完成 Agent/Answer/持久化定向测试、浏览器 Console/Network/引用跳转、桌面与 320px、文档和独立提交。

**退出门**：不能只靠 Mock 证明两轮，也不能用“重复调用同一 Prompt”冒充修复；真实 Trace 和自动测试必须共同证明 Observation 改变了下一动作及第二次模型输入。

### T10D：完成四路径、Runner 与完整质量门

- [x] 前端在现有折叠 Trace 中显示 `PLAN/CRITIQUE` 的去敏摘要、修复原因、新增证据和模型调用数；不展示思维链、Prompt 或原始模型响应。
- [ ] 真实浏览器补齐正常、补检、扩窗、拒答四条路径，以及刷新恢复、引用跳转、桌面和 320px、Console/Network。
- [x] 故障矩阵覆盖 Planner/Critic 非法输出、超预算、重启、重复 Worker、Redis 清空和 UNKNOWN；恢复成功率、重复高成本调用和终止原因按现有门统计。
- [ ] 冻结新 `agentPolicyVersion`，复用现有 Runner 在 DEV 同题至少运行三轮，报告首答通过率、补检恢复率、无效 Tool 比例、模型调用数、P95、成本、Claim 支持和引用定位。
- [ ] 依次执行相关测试、`server\mvnw.cmd test`、`server\mvnw.cmd verify -Pintegration`、`client\npm.cmd run check`、变更覆盖率、npm audit、Gitleaks；OWASP 只有得到有效数据才可记作通过。
- [ ] 回填 Roadmap/M6 ACCEPTANCE；只有 M5 原硬门和 M6 晋级门同时通过且负责人批准，才默认启用，否则保留实验关闭；独立提交。

> 2026-11-13 阻断：隔离库权限补齐后的容器化完整集成门曾通过，但浏览器验收暴露同题跨 Run
> Planner 指纹冲突与结构化 Prompt 歧义。两项均已按红绿测试修复；冻结的
> `agent-planner-v3/agent-critic-v2` 同题三轮仍只有 `2/3` 完成，第三轮 Answer 返回
> `INVALID_RESPONSE`。按退出门停止，未用补跑替代失败轮，未执行剩余四路径、Runner、后修复完整门，
> T10 不提交且 Agent 保持默认关闭。详见 `tasks/M6/ACCEPTANCE.md` 第 16 节。

**依赖**：T9；按 T10A -> T10B -> T10C -> T10D 串行，上一退出门不通过即停止。

**预计文件**：现有 Agent Trace UI/测试、Evaluation Runner/报告和 M6/Roadmap 文档；不创建第二套评测或 Agent 页面。

**退出门**：闭环行为、两轮修复、可靠性、成本和质量都有可复现证据；任何失败不得通过补跑覆盖、降低阈值、删除断言、跳过测试或 suppression 继续。
