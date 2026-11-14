# M6 实施计划：最小动态工具型 Agent Loop

## 目标与进入边界

M6 用一个可持久化、有预算、可恢复的最小循环验证“工具结果会改变下一步动作”。M5 仍因
Claim 支持和引用定位硬门失败而未退出；负责人通过 `EX-2026-11-10-M6` 允许实验性进入。
因此 Agent 默认关闭，非 Agent 问答保持默认路径，任何 M6 结果都不能覆盖 M5 失败事实。

## 最小 MVP 决策

- 复用现有 Retrieval、Answer、Evidence Gate、`model_call` 与执行预算，不复制检索/回答管线。
- 只提供检索、扩窗、生成并验证三个业务动作；`finish`、拒答和请求澄清是确定性终止结果，
  不是可产生副作用的 Tool。用一个枚举加显式 `switch` 完成白名单分发，不做插件 SDK、反射
  扫描、动态加载、工具工厂或多层 planner/executor/critic 类型体系。
- 只新增一个 `AgentApplicationService`、一个 Agent 持久化 Port/Adapter 和一个策略 Port；
  `AgentApplicationService` 直接编排现有 application service，不为三个 Tool 各建 controller、
  service、port 和 adapter。
- MySQL 保存 Run/Step/ToolCall 和去敏 Observation；模型内部思考不保存、不展示。
- Java 确定性控制权限、预算、引用和终止。默认最多 6 Step、2 次答案生成/修复；具体预算写入
  Run 快照，不能通过重启清零。
- Run 由数据库 CAS Worker 逐步推进，不新增 RabbitMQ 交换机/消息合同；创建请求只返回持久化
  Run，断开 HTTP 不影响继续执行。
- 前端只在现有 `AnswerWorkspace` 增加默认关闭的实验开关和折叠轨迹，不新增 Agent 工作台。

## 依赖图

```text
T0 冻结例外、合同、路径和退出门
  -> T1 MySQL Run 真源与无费用生命周期
      -> T2 三动作循环与调用前硬门
          -> T3 恢复故障矩阵与现有问答页回放
              -> T4 DEV/已冻结子集 A/B、晋级裁决与 M6 退出
                  -> T5 READY 后自动目标分析结果闭环（受限例外）

T6 冻结模型驱动闭环合同（待负责人批准继续实验）
  -> T7 让检索/扩窗证据真正进入回答生成
      -> T8 用结构化 Planner 决定首次检索内容
          -> T9 用结构化 Critic 驱动一次定向修复与重答
              -> T10 回放、A/B 与完整质量门
                  -> T11 第二轮 Critic 与有界视频证据部分回答
```

## 任务表

| ID | 任务 | 高相关内容合并理由 | 依赖 | 规模 |
| --- | --- | --- | --- | --- |
| T0 | 冻结 M5 缺口与 M6 最小合同 | 里程碑例外、数据真源、预算、工具边界和晋级语义共同约束后续 schema/API，必须先一致冻结 | 无 | S |
| T1 | 建立 MySQL Run 真源与无费用生命周期 | 迁移、CAS、owner API、版本/预算快照和去敏读取共同定义唯一真源；此时不接 Tool、不做 UI，先证明状态合同 | T0 | M |
| T2 | 接入三动作动态循环与全部调用前硬门 | 策略选择、白名单分发、预算/权限/幂等和 ToolCall 审计必须在第一次真实外部调用前一起成立，不能先调用再补安全规则 | T1 | M |
| T3 | 证明恢复故障矩阵并在现有问答页回放 | 重启/UNKNOWN/重复推进与用户看到的预算、拒绝、澄清和终止事实来自同一持久化轨迹，适合作为一个可见纵切 | T2 | M |
| T4 | 复用 M5 Runner 完成 DEV/已冻结子集 A/B、晋级裁决和退出证据 | 指标、延迟、成本、路径分布和默认启用决定必须来自同一冻结候选；不建设第二套 Agent 评测框架 | T3 | M |
| T5 | READY 后自动生成并展示目标分析结果 | 自动创建、稳定身份、标准 Answer 绑定、页面去重与恢复共同构成一个用户可见闭环；复用现有 Agent/Answer，不新增结果表或工作流框架 | T4 独立提交；`EX-2026-11-12-M6-T5` 允许效果门未退出时受限推进 | M |
| T6 | 冻结模型驱动最短闭环合同 | Planner/Critic Schema、动作图、一次修复上限、预算和失败语义会共同改变 Agent Policy，先用 ADR/验收样例冻结，避免边实现边发明协议 | T5；负责人明确批准在 T4 未退出时继续实验 | S |
| T7 | 打通证据选择到 Executor 的真实传递 | 检索命中的 Chunk、扩窗得到的时间窗、累积证据和 Answer Prompt 必须形成一条可恢复链；这是后续 Planner/Critic 有效的前提，不能拆成各自无效的半成品 | T6 | M |
| T8 | 结构化 Planner 驱动首次工具参数 | 一次 Planner 模型调用、严格 JSON Schema、Java 白名单/参数校验和首次检索共同构成第一个模型驱动纵切；不建设通用 DAG 或计划树 | T7 | M |
| T9 | 结构化 Critic 驱动一次定向修复与重答 | Critic 缺口、补检/扩窗参数、第二次生成和最终 Evidence Gate 高度相关，只有一起交付才能证明 Observation 真正改变下一次模型输入 | T8 | M |
| T10A | 拆分 Answer 结构化失败并保留脱敏诊断 | 当前 `INVALID_RESPONSE` 同时代表空 content、usage 缺失/越界和 content 非 JSON，必须先让下一次失败可归因；只记录响应形态，不保存模型原文 | T9；保留既有 T10 失败事实 | S |
| T10B | 受控复测同题结构化稳定性 | 固定问题、版本、数据和预算连续运行新的三轮 series，用 T10A 诊断区分偶发模型摇摆与稳定 Prompt/Adapter 缺陷；新 series 不覆盖原 `2/3` | T10A | S |
| T10C | 证明一次真实两轮修复闭环 | 单测用可控模型分别锁定 `RETRIEVE` 与 `EXPAND_WINDOW`；真实验收选取首轮证据有意不完整但第二轮可补齐的问题，证明 Critic 参数改变 Tool 和第二次 Answer Prompt | T10B | M |
| T10D | 完成四路径、Runner 与质量门 | 正常、补检、扩窗、拒答 Trace、同题 A/B、质量/延迟/成本和全量工程门必须基于同一冻结 policy，统一形成退出或继续关闭的证据 | T10C | M |
| T11 | 第二轮 Critic 与有界视频证据部分回答 | 第二轮完整性判断、部分状态、缺口持久化、API 和页面展示共同定义最终用户语义，合并为一个纵切且不新增工作流抽象 | T10D | M |

## 明确不做

- 不做多 Agent、插件市场、动态工具发现、通用 DAG、独立 Agent 服务或流式思考输出。
- 不保存或展示 chain-of-thought；Observation 只保存工具名、去敏参数摘要、结果引用和稳定状态。
- 不新增第二套检索、答案、证据门、预算或 Evaluation Runner。
- 不在 T1 为尚未运行的 Agent 制作占位 UI、假 Trace 或演示数据。
- 不建设多轮澄清会话；MVP 只终止为 `NEEDS_CLARIFICATION` 并返回去敏提示。
- 不使用 TEST 调参，不因 M6 局部收益删除失败题或降低 M5/M6 阈值。
- 不默认启用 Agent；未晋级时只保留本地实验开关。
- 不让模型生成任意工具名、SQL、URL、文件路径或预算；Planner/Critic 只能返回版本化白名单动作和受限参数。
- 不把“多调用几次模型”本身当作闭环成立；必须证明 Critic 缺口改变了后续 Tool 参数，并且新增证据进入第二次回答 Prompt。
- 不为首版增加长期记忆、并行分支、反思树、通用 DAG、动态插件或多 Agent。
- 不用模型通用知识补齐视频没有覆盖的请求项；只发布有视频引用且通过硬门的 Claim。

## 检查点

### Checkpoint A：MySQL 真源（T0-T1）

- Run/Step/ToolCall 可按 owner 读取，Redis 清空和刷新不丢事实。
- 创建、领取、追加和终止通过 CAS；模型内部思考、Prompt 和原始响应不进入 API。
- T1 不执行 Tool 或模型调用，先用零费用状态迁移证明合同和 owner 隔离。

### Checkpoint B：真实动态与确定性硬门（T2-T3）

- 至少三类 Observation 产生不同动作路径，而不是固定 Planner/Executor/Critic 顺序。
- owner、Schema、白名单、step/deadline/token/费用/修复次数和幂等门在每次调用前执行。
- 重启从最后安全 Step 继续；重复请求不重复高成本调用；UNKNOWN 不自动重放。

### Checkpoint C：实验裁决（T4）

- 与 M5 非 Agent 基线同题比较并报告硬门、P95 延迟、平均成本和未知成本。
- 只复用并窄扩展 M5 Evaluation Runner，以执行模式和 `agentPolicyVersion` 区分候选。
- 第 8.3 节前两条数值规则由 Java 确定性计算；若执行前没有冻结复杂题子集、显著收益口径
  和条件路由规则，第三条只报告、不用于自动晋级。
- 未同时满足 M5 原硬门与 Roadmap 8.3 晋级条件时，Agent 保持实验关闭状态。

### Checkpoint H：有界部分回答（T11）

- 两次 Answer 后都经过 Critic；第二次只能完整通过、发布已核验部分或拒答，不允许第三次修复。
- `PARTIALLY_ANSWERED` 中每条 Claim 仍通过 Evidence Gate，缺口不含模型补充事实，页面明确区分已确认与未确认。
- Policy/Prompt/Critic 版本和缺口写入 MySQL Trace/API；评测阈值与 Agent 默认关闭状态不变。

### Checkpoint D：目标闭环（T5）

- Agent、Worker、目标分析和前端入口四个开关均默认关闭；关闭时不产生模型调用。
- Worker 从 MySQL 每次发现一个缺少 `goal-analysis:<jobId>` Run 的 READY Job；数据库唯一约束裁决并发，
  Redis 清空或进程重启不重复创建。
- 查询接口只接受 Run Step 中严格的 `answer:<uuid>`，通过现有 Answer service 做 owner/Job 绑定读取；自动
  Answer 不出现在普通问答历史中，拒答或失败不制造空答案。

### Checkpoint E：证据闭环基础（T6-T7）

- 冻结的最短路径为 `PLAN -> RETRIEVE -> GENERATE_AND_VERIFY -> CRITIQUE -> [RETRIEVE|EXPAND_WINDOW] -> GENERATE_AND_VERIFY -> terminal`；正常路径在 Critic `PASS` 后提前结束。
- 最多一次 Critic 驱动修复，仍受 6 Step、2 次答案生成、deadline、Token 和费用快照约束；确定性 Evidence Gate 始终拥有最终否决权。
- Retrieval/Expand Step 保存可恢复的 Chunk 或时间窗引用；Answer 只消费这些已选择证据，不再静默按原问题重新检索。

### Checkpoint F：模型驱动修复（T8-T9）

- Planner 至少输出检索 query 和 expected facts；其输出必须实际成为 Retrieval 参数，并经 Java Schema、白名单、长度和 owner/预算门校验。
- Critic 只检查相关性与完整性，返回 `PASS` 或一次 `RETRIEVE/EXPAND_WINDOW` 修复建议；它不能批准引用、绕过 Evidence Gate 或直接结束数据库状态。
- 修复路径的第二次回答同时携带原问题、已验证首答、缺失要求、原证据和新增证据；测试证明新增证据出现在 Prompt 且改变最终回答或形成稳定拒答。

### Checkpoint G：可证明与可发布（T10）

- Answer 供应商响应失败必须能在不记录原文的前提下区分空内容、usage 异常、Token 越界和 JSON 格式错误；原失败轮永久保留，不以补跑替换。
- 同题受控复测只有在版本、数据、问题和预算固定时才可用于判断随机摇摆；若再次出现相同子错误，先修 Prompt/Adapter，不进入两轮验收。
- 两轮闭环必须同时具备确定性自动测试和至少一条真实供应商 Trace；真实 Trace 应为 6 Step/4 次生成式 LLM，第二轮新增证据和 Critic 缺口必须进入第二次 Answer Prompt。
- 正常、定向补检、扩窗修复、证据不足拒答至少四条真实 Trace 可回放；每次模型调用都有版本、Token、延迟、成本和调用指纹。
- 故障测试覆盖 Planner/Critic 非法 JSON、越权参数、超预算、进程重启和高成本调用 UNKNOWN；不得自动重放未知调用。
- 冻结新 `agentPolicyVersion` 后复用 M5/M6 Runner 完成至少三轮 DEV 同题 A/B；M5 原硬门与 M6 晋级门仍同时适用，未通过则默认关闭。
