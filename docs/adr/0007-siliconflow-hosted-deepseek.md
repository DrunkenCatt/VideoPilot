# ADR-0007：MVP 固定使用 SiliconFlow 托管的 DeepSeek 模型

## Status

Accepted

## Date

2026-11-05

## Context

ADR-0005 已决定用项目 Port/Adapter 隔离模型供应商，并固定 MVP 的模型能力，但没有冻结 LLM 的实际托管方和可调用模型。直连 DeepSeek 的现有账户模型目录与项目默认 `deepseek-chat` 不一致，最小 completion 返回 HTTP 402，无法作为当前 MVP 的可验证依赖。项目已有的 `SILICONFLOW_API_KEY` 已用于 TeleSpeechASR 与 BGE-M3，SiliconFlow 同时提供 OpenAI 兼容的 DeepSeek completion。

2026-11-05 的脱敏真实探针确认 `POST https://api.siliconflow.cn/v1/chat/completions` 可调用 `deepseek-ai/DeepSeek-V4-Flash`，`response_format={"type":"json_object"}` 返回可解析 JSON、`finish_reason=stop` 和输入/输出 Token usage；非法模型返回 HTTP 400 与稳定错误码 `20012`。响应不提供成本字段。

## Decision

MVP 的 LLM 固定为 SiliconFlow 托管的 `deepseek-ai/DeepSeek-V4-Flash`。

- 只实现一个 SiliconFlow LLM Adapter，调用 `/v1/chat/completions`；不建设直连 DeepSeek fallback、多供应商路由或 Provider Registry。
- 配置使用现有 `SILICONFLOW_API_KEY`，模型和超时分别由 `SILICONFLOW_LLM_MODEL_VERSION`、`SILICONFLOW_LLM_TIMEOUT` 显式覆盖；默认模型固定为本 ADR 已验证的完整模型 ID。
- Trace 必须把 provider 记录为 `SiliconFlow`、把模型记录为实际请求模型，不能把托管方误记为 DeepSeek，也不能把模型族名当成供应商名。
- JSON mode 只产生候选数据；Java 仍负责 Schema、预算、引用存在性和证据硬门。截断、不完整 JSON、超时和未知结果继续遵循 ADR-0005。
- 供应商响应未给出成本时记录“不可得”，不得写成 0；请求幂等由项目输入指纹和调用状态保证，不假设供应商提供 exactly-once。

## Alternatives Considered

- 继续直连 DeepSeek：当前账户无法完成已冻结默认模型的真实调用，不能作为 M3 的可验证主路径。
- 同时接入直连 DeepSeek 与 SiliconFlow：会提前引入路由、fallback 和双重错误语义，超出最小 MVP。
- 使用推理型 DeepSeek-R1：M3 只需要有界结构化候选，增加推理成本和延迟没有当前证据支持。

## Consequences

- ASR、Embedding 和 LLM 共用一个托管供应商与凭据，部署更简单，但供应商集中风险更高。
- M3/T4 的 Adapter、测试、运行手册和审计字段都必须使用本 ADR 的 provider/model 事实。
- 模型下线或目录变化必须先做真实探针并通过新 ADR/迁移修改默认值，不能静默切换。
- 应用必须验证完整 JSON，并为 Token usage、延迟、错误码和成本不可得保留审计事实。

## Validation

- 默认配置绑定为 provider `SiliconFlow`、model `deepseek-ai/DeepSeek-V4-Flash`。
- 脱敏真实探针返回 HTTP 200、合法 JSON、`finish_reason=stop`、103 input tokens、31 output tokens、134 total tokens，耗时 1045 ms。
- 非法模型探针返回 HTTP 400、错误码 `20012`；探针不保存 Key、完整 Prompt、候选正文或供应商原始响应。
