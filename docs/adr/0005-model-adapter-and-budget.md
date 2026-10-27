# ADR-0005：通过 Adapter 隔离模型供应商并由 Java 控制预算

## Status

Accepted

## Date

2026-10-27

## Context

MVP 固定使用 DeepSeek、TeleSpeechASR、BGE-M3 和 Tesseract，但供应商 SDK、错误和计费口径不应渗入业务层。外部调用还可能超时或重复计费，Agent 循环必须有确定性上限。

## Decision

所有 LLM、ASR、Embedding 和 OCR 调用经过项目自己的 Port/Adapter；业务层不依赖供应商响应类型。MVP 固定供应商，未来切换通过 Adapter 边界完成，而不是让业务代码分叉。

每次调用都记录可审计的供应商、模型/版本、调用状态、耗时、计量和估算成本，并保留去敏输入指纹。只有明确临时错误才允许有限重试；超时等不确定结果标为 `UNKNOWN`，不能立即假定失败并重复付费。

时间、Token/计量、费用、调用次数和 Agent 步骤等预算由 Java 确定性代码检查，模型输出不能修改预算。具体阈值通过后续评测标定并版本化。

## Alternatives Considered

- 业务代码直接调用 SDK：供应商耦合和错误语义会扩散到所有模块。
- 所有异常统一重试：会重复计费并绕过预算。
- 让模型自行决定预算：候选输出不能成为权限或费用真源。

## Consequences

- 凭据只进入基础设施配置，不进入 Git、消息或业务 Trace。
- Prompt、模型、Embedding、检索公式和 Agent Policy 版本必须可追溯。
- 供应商替换需要维护 Adapter 契约和审计映射，但不应改动领域规则。
- M0 只冻结边界；真实调用、降级和阈值在后续里程碑实现。

## Validation

- 领域/application 包不引用供应商 SDK 类型。
- 未配置真实凭据时空业务环境仍可启动且不发起外部调用。
- 不可重试、未知结果和预算耗尽不会触发无界重试。
