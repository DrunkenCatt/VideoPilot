# ADR-0005：模型调用经 Adapter 审计并受确定性预算约束

## Status

Accepted

## Date

2026-10-27

## Context

VideoPilot MVP 固定使用 DeepSeek、TeleSpeechASR、BGE-M3 和 Tesseract，但供应商 SDK 类型、错误码和计费口径不能渗入业务层。模型调用成本高且可能超时，简单重试会重复计费。未来 Agent Loop 还需要步骤、时间、Token、费用和修复次数上限。

## Decision

所有 LLM、ASR、Embedding 和 OCR 调用都通过业务 Port 与基础设施 Adapter。业务层只使用 VideoPilot 自有请求、候选结果和错误类型，不引用供应商响应类。MVP 固定供应商，不在 M0/MVP 实现动态多供应商路由。

每次外部调用先创建 `ModelCall` 审计记录，至少包含：

- `modelCallId`、`jobId`、可选 `runId`、`requestId` 和操作类型；
- provider、model/engine、modelVersion、promptVersion、embeddingVersion 或 OCR 配置版本；
- 去敏的输入指纹和产物引用，不保存凭据或不必要的完整原始响应；
- 开始/结束时间、延迟、输入/输出 Token 或供应商计量、估算成本与币种；
- 状态、attempt、稳定错误代码和 `RETRYABLE`、`NON_RETRYABLE` 或 `UNKNOWN` 分类。

调用指纹和供应商支持的幂等能力用于避免重复高成本请求。只有明确的临时错误可在有限次数、全局 deadline 和剩余预算内重试。超时或结果是否已生成不确定时记录 `UNKNOWN`，不得假定失败后立即重复调用。

预算由 Java 确定性代码在调用前后检查，模型不能覆盖。预算维度包括：

- Job/Run 全局 deadline；
- Token 或等价计量上限；
- 估算金额上限；
- 模型调用次数；
- Agent Tool Step 和答案生成/修复次数（M6 启用）。

预算不足时以 `BUDGET_EXHAUSTED` 终止或降级，记录明确终止原因。具体阈值由后续评测标定并版本化，不在 ADR 中虚构生产数值。费用估算必须记录价格表版本和币种，不能把估算值表述为最终账单。

## Alternatives Considered

### 业务服务直接调用供应商 SDK

开发快，但供应商类型、错误和鉴权会污染领域代码，难以测试和替换。

### 对所有异常统一重试

可能对权限、参数错误或 UNKNOWN 调用重复计费，也绕过全局预算。

### 只在调用结束后记录审计

进程在请求发出后退出时没有证据判断调用是否发生，无法安全处理 UNKNOWN。

### 由模型自行决定预算

模型输出是候选数据，不能作为权限、费用或终止条件的真源。

## Consequences

- Provider 配置和凭据只进入基础设施 Adapter，来自环境或秘密配置，不进入 Git、消息体和 Trace。
- Adapter 必须把供应商错误映射到稳定错误分类，并暴露受控超时与取消边界。
- Prompt、模型、Embedding、检索公式和 Agent Policy 都是版本化生产资产，相关版本进入 Trace。
- M0 只冻结接口和审计契约；真实调用、降级路径和预算指标在后续里程碑以测试驱动实现。

## Validation

- 领域/application 包不依赖 DeepSeek、TeleSpeechASR、BGE-M3 或 Tesseract 的 SDK 类型。
- 未配置真实凭据时空业务环境仍可启动，且不会发起外部调用。
- 每个调用在请求发出前已有 `ModelCall`，成功、失败和 UNKNOWN 都能审计。
- 明确不可重试错误和预算耗尽不会触发重试；UNKNOWN 必须先对账或人工处理。
- Trace 可还原 provider、模型/Prompt/Embedding/Policy 版本、延迟、Token 和估算成本，但不含密钥或完整敏感输入。
