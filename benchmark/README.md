# VideoPilot Benchmark

本目录只保存评测规范、JSON Schema 和已脱敏的小型示例，不保存原始视频、完整转录、供应商响应或未获授权的教学资料。

## 目录职责

- `schemas/`：检索、答案、Agent 和反馈四类稳定数据合同。
- `examples/`：可提交、无版权内容的最小示例，用于说明字段语义。
- `local/`：本机原始视频、转录和工作标注；整个目录被 Git 忽略。

## 样本选择与使用边界

1. 仅使用自有、明确授权或许可允许评测的教学视频，并记录 `sourceRights`；授权不明的资料不得进入共享 benchmark。
2. 覆盖短事实定位、跨片段归纳、OCR 主导、ASR 主导、视听冲突和不可回答问题，避免只选系统擅长的样本。
3. `dev` 集可用于调 Prompt、检索公式和 Agent Policy；冻结的 `test` 集只用于候选版本验收。任何看过 test 答案后做出的调整都必须产生新数据版本并重新冻结 test。
4. 每条记录都带 `dataVersion`、`split` 和 `review`。只有 `review.status=approved` 且至少完成双人复核的样本才能进入正式分数。
5. 原始资料放在 `benchmark/local/`；共享示例必须脱敏、短小且不含完整转录或模型原始响应。

## 核心标注语义

- `question`：用户会问的问题。
- `timeWindow`：答案相关的预期时间范围，毫秒为单位。
- `evidenceChannels`：`asr`、`ocr`、`keyframe` 中允许或期望使用的证据通道。
- `requiredFacts`：合格答案必须覆盖的事实。
- `forbiddenClaims`：证据不支持、答案不得声称的内容。
- `answerability`：`answerable`、`unanswerable` 或待复核的 `disputed`。
- `review`：标注状态、复核者数量和备注；未批准样本不进入发布分数。

Schema 采用 JSON Schema 2020-12。示例 ID 仅用于说明，不对应真实媒体或用户。
