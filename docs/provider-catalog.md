# M0 固定供应商清单

MVP 固定下列四个能力实现，但业务代码只依赖项目 Port。供应商 SDK 响应必须在 Adapter 边界完成校验、转换和错误归一化，不能进入 `analysis`、`retrieval`、`answer` 或 `agent` 的业务模型。

| 能力 | MVP 实现 | 版本配置 | 超时配置 | 成本计量 |
| --- | --- | --- | --- | --- |
| LLM | DeepSeek | `DEEPSEEK_MODEL_VERSION` | `DEEPSEEK_TIMEOUT` | 输入/输出 Token |
| ASR | TeleSpeechASR | `TELESPEECH_ASR_MODEL_VERSION` | `TELESPEECH_ASR_TIMEOUT` | 音频秒数 |
| Embedding | BGE-M3 | `BGE_M3_MODEL_VERSION` | `BGE_M3_TIMEOUT` | 输入 Token |
| OCR | Tesseract | `TESSERACT_VERSION` | `TESSERACT_TIMEOUT` | 本地图片数，不产生云端费用 |

## Port 与 Adapter 边界

- `LanguageModelPort`、`SpeechRecognitionPort`、`EmbeddingPort`、`OcrPort` 使用项目中立的请求和结果类型。
- 后续 Adapter 负责供应商认证、HTTP/SDK 超时、响应 Schema 校验、临时错误映射和计量转换。
- 未配置真实凭据时仅创建供应商清单配置，不注册会发起远程请求的实现，因此空业务环境可以启动。
- 凭据将来只从环境或秘密管理读取，不属于供应商清单，也不能写入 Trace、日志或消息体。

## 必须进入执行 Trace 的版本

每次模型或 Agent 执行必须记录 `promptVersion`、`modelVersion`、`embeddingVersion`、`retrievalFormulaVersion` 和 `agentPolicyVersion`。这些字段用于回答“这次结果由哪套配置产生”，是回放、离线比较和回滚的前提；缺任一版本时不得生成可晋级的评测记录。

本文件实现 ADR-0005 已接受的边界，不新增架构选择。真实外部调用、重试和预算阈值属于后续里程碑。
