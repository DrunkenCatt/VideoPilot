# MVP 固定供应商清单

MVP 固定下列四个能力实现，但业务代码只依赖项目 Port。供应商 SDK 响应必须在 Adapter 边界完成校验、转换和错误归一化，不能进入 `analysis`、`retrieval`、`answer` 或 `agent` 的业务模型。

| 能力 | MVP 实现 | 版本配置 | 超时配置 | 成本计量 |
| --- | --- | --- | --- | --- |
| LLM | SiliconFlow 托管的 `deepseek-ai/DeepSeek-V4-Flash` | `SILICONFLOW_LLM_MODEL_VERSION` | `SILICONFLOW_LLM_TIMEOUT` | 输入/输出 Token；响应无成本字段时记录不可得 |
| ASR | SiliconFlow `TeleAI/TeleSpeechASR` | `TELESPEECH_ASR_MODEL_VERSION` | `TELESPEECH_ASR_TIMEOUT` | 本地可信音频秒数 + 供应商 `usage.seconds`；价格不可得时不伪造成本 |
| Embedding | BGE-M3 | `BGE_M3_MODEL_VERSION` | `BGE_M3_TIMEOUT` | 输入 Token |
| OCR | Tesseract | 启动时探测二进制版本 + 语言包 SHA-256 | `TESSERACT_TIMEOUT` | 图片像素/文本字符；本地成本 `0 USD` |

## Port 与 Adapter 边界

- `LanguageModelPort`、`SpeechRecognitionPort`、`EmbeddingPort`、`OcrPort` 使用项目中立的请求和结果类型。
- 后续 Adapter 负责供应商认证、HTTP/SDK 超时、响应 Schema 校验、临时错误映射和计量转换。
- 未配置真实凭据时仅创建供应商清单配置，不注册会发起远程请求的实现，因此空业务环境可以启动。
- 凭据将来只从环境或秘密管理读取，不属于供应商清单，也不能写入 Trace、日志或消息体。

## 必须进入执行 Trace 的版本

每次模型或 Agent 执行必须记录 `promptVersion`、`modelVersion`、`embeddingVersion`、`retrievalFormulaVersion` 和 `agentPolicyVersion`。这些字段用于回答“这次结果由哪套配置产生”，是回放、离线比较和回滚的前提；缺任一版本时不得生成可晋级的评测记录。

本文件实现 ADR-0005 与 ADR-0007 已接受的边界。M2 已接入 ASR/OCR 的调用审计与有限恢复；M3 已接入固定 SiliconFlow BGE-M3 和 DeepSeek、调用审计、Token/延迟以及成本不可得事实。动态 Agent 预算仍属于后续里程碑。

## M3 Embedding、Qdrant 与 LLM 合同补充

- Embedding endpoint 为 SiliconFlow OpenAI-compatible `POST /v1/embeddings`，固定 `BAAI/bge-m3`；请求不发送供应商不接受的可选 `dimensions` 字段，但响应必须严格为 1024 维。
- 单批最多 4 条、单条最多 6,000 字符/8,192 估算 Token、单次总字符 100,000、响应 8 MiB、并发 1、最多 3 次有界阶段尝试。调用前写 `model_call`；UNKNOWN 不在线程内冒险重发。
- Qdrant collection 为 `videopilot_chunks_m3_bge_m3_1024_v1`，cosine 1024 维；point ID 使用稳定 Chunk UUID，payload 仅含 index/owner/media/context 与时间窗。MySQL 的 Chunk、向量事实和索引发布记录才是真源。
- 检索固定 `hybrid-0.60-0.25-0.15-v1`。Qdrant 不可用时从 MySQL 向量执行有界内存 cosine；query Embedding 不可用时保留 keyword/OCR，并显式返回降级通道。
- LLM 固定 SiliconFlow `deepseek-ai/DeepSeek-V4-Flash`、JSON mode、30 秒、最多 1,200 output tokens、20,000 Prompt 字符、64 KiB 响应、并发 1。候选 JSON 必须再经过 Java 引用硬门；timeout 为 UNKNOWN，不自动重发。
- SiliconFlow 响应未提供可核验价格时，`priceStatus=UNAVAILABLE` 且成本保持空值，不能写成 0 USD。

实际启动、故障判断、collection 重建边界和基线命令见 `docs/M3_RUNBOOK.md`。

## M2 ASR 合同补充

M2 已根据 SiliconFlow 官方[中文音频转录 API](https://docs.siliconflow.cn/docs/api/audio-transcriptions-post)和[英文 API reference](https://docs.siliconflow.com/en/api-reference/audio/create-audio-transcriptions)确认：

- Endpoint 为 `POST /v1/audio/transcriptions`，使用 Bearer API key 和 multipart `file`/`model` 字段；
- 固定模型名为 `TeleAI/TeleSpeechASR`；
- 单文件官方限制为不超过 1 小时且不超过 50 MB；
- 2026-11-04 真实探针返回 `text`、秒单位 `duration` 和 `usage={type: duration, seconds: N}`，但没有词级时间戳或价格字段。

因此 M2 用版本化 60 秒音频切片的持久化边界作为 `TranscriptSegment` 时间窗，不伪造供应商词级时间戳；同时保存本地可信音频时长和供应商 `usage.seconds`。价格不可得时成本保持未知而不是写 0。完整错误、探针与审计合同见 `tasks/M2/ACCEPTANCE.md`。

## M2 OCR 合同补充

- Compose server 镜像安装 `tesseract-ocr` 与 `tesseract-ocr-eng`，默认语言包目录为 `/usr/share/tesseract-ocr/5/tessdata`；
- Adapter 以受限子进程读取 TSV，限制图片字节/像素、输出、文本、并发和超时，超时后强制销毁进程；
- 调用成功记录 Tesseract 版本、语言包摘要、像素、文本字符、耗时和本地零成本；失败只保存稳定分类，不保存内部路径或完整 stderr；
- OCR 故障允许 Context 明确降级为 ASR-only，不能反向把 ASR 硬失败包装成成功。

具体启动配置和排障见 `docs/M2_RUNBOOK.md`。
