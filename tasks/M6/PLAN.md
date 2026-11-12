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

## 明确不做

- 不做多 Agent、插件市场、动态工具发现、通用 DAG、独立 Agent 服务或流式思考输出。
- 不保存或展示 chain-of-thought；Observation 只保存工具名、去敏参数摘要、结果引用和稳定状态。
- 不新增第二套检索、答案、证据门、预算或 Evaluation Runner。
- 不在 T1 为尚未运行的 Agent 制作占位 UI、假 Trace 或演示数据。
- 不建设多轮澄清会话；MVP 只终止为 `NEEDS_CLARIFICATION` 并返回去敏提示。
- 不使用 TEST 调参，不因 M6 局部收益删除失败题或降低 M5/M6 阈值。
- 不默认启用 Agent；未晋级时只保留本地实验开关。

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

### Checkpoint D：目标闭环（T5）

- Agent、Worker、目标分析和前端入口四个开关均默认关闭；关闭时不产生模型调用。
- Worker 从 MySQL 每次发现一个缺少 `goal-analysis:<jobId>` Run 的 READY Job；数据库唯一约束裁决并发，
  Redis 清空或进程重启不重复创建。
- 查询接口只接受 Run Step 中严格的 `answer:<uuid>`，通过现有 Answer service 做 owner/Job 绑定读取；自动
  Answer 不出现在普通问答历史中，拒答或失败不制造空答案。
