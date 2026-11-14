# ADR-0018：第二轮 Critic 与有界视频证据部分回答

## Status

Accepted

## Date

2026-11-14

## Context

ADR-0017 将 Agent 修复限制为一次，但第二次 Answer 后直接结束。只要问题还有一项未覆盖，即使已经存在
通过 `AnswerEvidenceGate` 的 Claim，用户也只能看到完整拒答。这会隐藏视频中已经能够确认的内容，也让
第二次生成缺少一次独立的完整性判断。

本系统的核心仍是视频证据，而不是通用知识问答。部分回答不能成为放宽引用门、混入模型常识或继续无限
循环的理由。

## Decision

- Answer 新增 `PARTIALLY_ANSWERED` 状态。它必须至少包含一个通过确定性证据门的 Claim，并携带非空的
  `missingRequirements`；页面分为“视频可以确认”和“视频证据不足以确认”两部分。
- Answer Prompt 只允许使用当前视频证据。模型必须把可支持内容拆成原子 Claim，把不能由视频确认的请求项
  写入 `missingRequirements`；禁止使用模型通用知识补齐缺口。
- `AnswerEvidenceGate` 的安全语义不变：任何 Claim 缺少合法 evidenceId、原文引文或 Claim-Evidence 支撑，
  整个候选仍被拒绝。部分回答只改变“多个请求项未全部覆盖”时的发布语义，不改变单条 Claim 的发布门。
- 第一次和第二次 Answer 后都调用真实 Critic。第一次 Critic 只能 `PASS` 或提出一次
  `RETRIEVE/EXPAND_WINDOW`；第二次 Critic 不能再请求工具，只能 `PASS/PARTIAL/REFUSE`。
- 修复路径固定为最多 7 个 Step、5 次生成式模型调用：Planner、两次 Answer、两次 Critic。答案生成仍最多
  2 次，Critic 驱动修复仍最多 1 次；模型无权增加第三次检索、扩窗或回答。
- Java 继续控制 owner、Job、预算、动作白名单、Schema、修复次数、Evidence Gate 和最终持久化。Critic
  只判断目标覆盖与语义相关性，不能证明引用真实，也不能放行证据门拒绝的候选。
- MySQL `answer_record` 保存 `missing_requirements`，API 和目标分析状态显式返回
  `PARTIALLY_ANSWERED`。评测把它视为已发布回答，但原 required-fact 计分不因部分发布而放宽。
- Prompt、Policy 和 Critic 版本分别前进为 `answer-evidence-v3-partial`、`agent-policy-v5-partial` 和
  `agent-critic-v6-partial`。Agent、Worker 和目标分析开关继续默认关闭。

## Alternatives Considered

- 第二次仍一律拒答：最安全但会丢失已经核验的教学内容，拒绝。
- 用模型常识补齐视频缺口：破坏可追溯视频证据边界，拒绝。
- 允许 Critic 自主循环直到完整：成本、延迟、恢复和幂等边界不可控，拒绝。
- 对每条 Claim 单独丢弃失败项后发布其余 Claim：会让模型通过夹带错误 Claim 探测并削弱候选原子性，当前
  阶段拒绝；候选中任一 Claim 校验失败仍整体拒绝。

## Consequences

- 用户可以区分“视频已经证明什么”和“视频没有证明什么”，并据此继续追问或调整分析目标。
- 修复路径比 ADR-0017 多一次 Critic 调用和一个 Step，增加少量成本与延迟，但仍有明确上限且可审计。
- `PARTIALLY_ANSWERED` 不代表问题完整解决；效果晋级仍使用原 M5/M6 阈值，不能以部分回答数量替代严格
  Claim 支持率或引用定位率。
- ADR-0017 中“第二次生成后直接终止”和“最多 6 Step/4 LLM”的部分被本 ADR 取代，其余权限、预算、
  Evidence Gate、去敏和默认关闭决策继续有效。

## Validation

- 单元测试证明合法 Claim 与显式缺口可发布为部分回答，伪造/错绑/不相关引用仍整份拒绝。
- Agent 测试证明第二次 Answer 后必经第二次 Critic，最终 `PARTIAL` 会持久化缺口，且不存在第三次修复。
- 全新隔离 MySQL 从 V1 迁移到 V23，并验证部分状态、Claim、Citation 和缺口可恢复。
- 前端契约和组件测试证明手动问答与目标分析均分区展示，引用仍可跳转，拒答语义保持独立。
