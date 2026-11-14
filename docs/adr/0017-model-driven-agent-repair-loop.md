# ADR-0017：有界模型驱动 Agent 修复闭环

## Status

Superseded in part by ADR-0018

## Date

2026-11-13

## Context

M6 T0-T5 已建立 `AgentRun/AgentStep/ToolCall`、MySQL CAS Worker、预算硬门、三种工具动作和轨迹回放，
但当前 `RuleBasedAgentPolicy` 只按 `EVIDENCE:none/single/multiple` 推进。`EXPAND_WINDOW` 的结果没有进入
后续 Answer Prompt，回答被证据门拒绝后也不会产生结构化缺口、定向补检和第二次生成。因此现状是可靠的
多步工作流状态机，不足以证明模型观察结果后改变下一次工具内容。

T4 的冻结 DEV A/B 已证明当前候选不可晋级：Claim 严格支撑率均值 36.69%、引用定位率均值 74.74%，
低于 85%/95% 硬门。负责人决定保留该失败事实和默认关闭状态，优先实施 T6-T10 验证最短修复闭环。

## Decision

- 使用一个有界动作图，不建设通用 DAG、递归反思、多 Agent 或动态插件：

```text
PLAN -> RETRIEVE -> GENERATE_AND_VERIFY -> CRITIQUE
                                            | PASS -> FINISH
                                            ` REPAIR -> RETRIEVE|EXPAND_WINDOW
                                                         -> GENERATE_AND_VERIFY
                                                         -> FINISH|REFUSE
```

- 正常路径最多三次 LLM 调用：Planner、Executor、Critic；修复路径最多增加一次 Executor，总计四次。
  Embedding 调用单独计量，不混入上述 LLM 次数。
- 最多 6 个 Tool Step、2 次答案生成和 1 次 Critic 驱动修复。现有 deadline、Token、金额、未知费用、
  owner、Schema、幂等和 UNKNOWN 硬门继续在每次外部调用前执行，预算快照不能因恢复而清零。
- Planner 只可返回 `RETRIEVE`、`NEEDS_CLARIFICATION` 或 `REFUSE`。可执行参数仅包含有界 query 和
  `expectedFacts`；模型不能指定任意工具、SQL、URL、文件路径、owner、预算或数据库状态。
- Critic 只检查问题覆盖和语义相关性，返回 `PASS`，或一个 `RETRIEVE(query)` /
  `EXPAND_WINDOW(startMs,endMs)` 修复动作，并给出去敏的 `missingRequirements`、`unsupportedClaims`。
- Java 严格解析版本化 JSON Schema，并验证动作白名单、参数长度、视频边界、owner、预算和修复次数。
  模型输出只是候选决策，无权直接执行工具或终止 Run。
- `AnswerEvidenceGate` 继续独占引用存在性、原文绑定和 Claim-Evidence 发布硬门。Critic 不能批准硬门
  失败的答案，也不能降低 M5 的 85% Claim 支持和 95% 引用定位阈值。
- Retrieval/Expand Step 必须保存有界、可恢复的 Chunk 或时间窗引用。Executor 只能消费这些已选择证据；
  不允许忽略 Tool 结果后按原问题静默重新检索。
- 第二次生成必须携带原目标、首个已验证答案、Critic 缺口、原证据和新增证据。若仍未通过硬门则稳定
  拒答，不进入第三次生成。
- 只保存结构化计划/批评摘要、动作参数摘要、结果引用和调用审计；不保存或展示 chain-of-thought、
  完整 Prompt、原始模型响应或不必要的完整转录。

## Alternatives Considered

- 保留现有规则状态机：可靠但不能让模型结果改变工具内容，拒绝作为完整 Agent Loop。
- 固定两轮 Planner-Executor-Critic：实现简单，但无论缺口类型都重复同一路径，不能验证工具驱动修复。
- 无限制循环直到 Critic 通过：成本、延迟和恢复风险不可控，拒绝。
- 通用工具协议、计划树或多 Agent：当前只有检索/扩窗/回答三类能力，没有足够用例支撑抽象，拒绝。
- 让 Critic 取代 Evidence Gate：模型不能成为引用真实性和发布权限的最终裁决者，拒绝。

## Consequences

- Agent 将从“检索数量驱动的状态机”升级为“模型计划检索、模型识别缺口、工具结果改变重答”的最短闭环。
- 正常请求新增 Planner/Critic 成本和延迟，必须在 T10 与非 Agent 基线同题比较；未达到双重质量门时继续
  默认关闭。
- 一次修复上限可能拒绝可由更多轮救回的问题，但能保持恢复、预算和评测边界清晰；后续只有新证据证明
  收益时才评审提高上限。
- 现有 Run/Step/ToolCall 和 Answer 管线继续复用；实现不得另建第二套检索、答案、证据门或评测框架。

## Validation

- 正常、补检、扩窗和拒答四条 Trace 都能从 MySQL 回放，并具有稳定终止原因。
- 自动化证明 Planner query 成为 Retrieval 参数，Critic 缺口改变下一 Tool 的名称或参数，新增证据进入
  第二次 Answer Prompt。
- 非法 JSON、未知动作、越界窗口、超预算、重复 Worker、重启和 UNKNOWN 均在既有硬门下安全停止。
- 冻结新 policy 后在 DEV 同题至少运行三轮，报告首答通过率、补检恢复率、无效 Tool、模型调用数、
  Claim 支持、引用定位、P95 和成本；未同时满足 M5/M6 门不得默认启用。
