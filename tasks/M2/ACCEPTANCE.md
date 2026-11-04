# M2 验收合同与证据记录

## 1. 文档状态

- 里程碑：M2 时序多模态 VideoContext
- 合同冻结日期：2026-11-04
- 当前阶段：T0-T4 已完成；下一任务为 T5 对齐并发布可查询 VideoContext
- 范围：FFmpeg 派生、TeleSpeechASR、Tesseract OCR、VideoSegment/VideoContext、分片 Checkpoint、owner-scoped 查询、播放器与统一时间轴
- 排除：VideoChunk、Embedding、Qdrant、检索、问答、章节、Agent、完整 Manual Replay

本文档先记录可执行验收场景，后续任务只追加实际证据，不改写失败场景来迁就实现。未取得真实证据的条目保持未完成，不能以 fake、小视频或历史结果代替。

## 2. 冻结供应商合同

MVP ASR 的完整身份为 SiliconFlow 托管模型 `TeleAI/TeleSpeechASR`，不是名为 TeleSpeechASR 的独立云厂商。

官方接口合同来源：

- SiliconFlow 中文 API：<https://docs.siliconflow.cn/docs/api/audio-transcriptions-post>
- SiliconFlow 英文 API：<https://docs.siliconflow.com/en/api-reference/audio/create-audio-transcriptions>

冻结内容：

| 项目 | M2 合同 |
| --- | --- |
| Endpoint | `${SILICONFLOW_ASR_BASE_URL}/audio/transcriptions`，默认 base URL 为 `https://api.siliconflow.cn/v1` |
| 鉴权 | `Authorization: Bearer <SILICONFLOW_API_KEY>`；值只能来自环境/秘密管理 |
| 请求 | `multipart/form-data`，字段 `file` 与 `model=TeleAI/TeleSpeechASR` |
| 官方单文件限制 | 时长不超过 1 小时，大小不超过 50 MB |
| M2 分片 | 固定 60 秒，无重叠；最后一片裁剪到媒体可信时长；版本 `m2-audio-slice-v1` |
| 响应 | 仅接受非空 JSON `text`；真实探针另返回秒单位 `duration` 与 `usage={type: duration, seconds: N}`，不含词/句时间戳或价格字段 |
| 时间窗 | 使用服务端已持久化的音频切片 `startMs/endMs`，不得声称为供应商词级时间戳 |
| 追踪 | 发送不含用户内容的 `X-Trace-Id`；只记录响应 `x-siliconcloud-trace-id`，不记录凭据 |
| 明确不可重试 | 400、401、403、404，空/非法响应；稳定归一化为 NON_RETRYABLE |
| 明确可重试 | 429、503、504；最多 3 次，通过延迟队列/调度，不在执行线程 sleep |
| 未知结果 | 客户端超时、连接在请求发出后中断；记为 UNKNOWN_OUTCOME，不立即假定未执行 |
| 幂等 | 官方合同与真实响应均未提供请求幂等键/回放令牌；UNKNOWN_OUTCOME 不得自动重发，高成本去重依赖本地输入指纹与分片 Checkpoint |
| 计量 | 同时记录本地可信音频毫秒数和供应商 `usage.seconds`；两者不一致时保留原值，不相互覆盖 |
| 成本 | 真实响应没有价格/成本字段；记录 `estimatedCost=null` 与 `priceStatus=UNAVAILABLE`，不得填 0 冒充免费 |

影响结果复用的冻结版本：

- `pipelineVersion=m2-context-v1`
- `extractionVersion=m2-extraction-v1`
- `audioSliceVersion=m2-audio-slice-v1`
- `alignmentVersion=m2-align-60s-v1`
- `asrProvider=SiliconFlow`
- `asrModelVersion=TeleAI/TeleSpeechASR`
- `ocrProvider=Tesseract`
- Tesseract 具体二进制版本和语言包摘要由 T4 启动时读取并进入输入指纹，不能只写 `5.x`。

## 3. 冻结 REST 合同

所有路径均在 `/api/v1` 下，使用既有 HttpOnly 会话与 CSRF 规则。GET 请求从认证 principal 推导 owner，客户端不得提交 ownerId。

| 方法 | 路径 | 成功结果 | 关键错误 |
| --- | --- | --- | --- |
| GET | `/analysis-jobs/{jobId}/context` | Context 状态、版本、时长、ASR/OCR 通道状态、Segment 数和服务端 links | 404 隐藏不存在/跨 owner；409 表示尚无可读 Context |
| GET | `/analysis-jobs/{jobId}/context/segments?cursor&pageSize&fromMs&toMs` | 按 ordinal 升序的 cursor page；默认 100，最大 500 | 400 非法窗口/cursor；404 跨 owner；409 Context 尚不可读 |
| GET | `/media-assets/{mediaId}/playback` | 短期对象 URL 与 expiresAt | 404 跨 owner/不存在；409 媒体未 READY；503 对象存储不可用 |
| GET | `/analysis-jobs/{jobId}/context/keyframes/{keyframeId}/access` | 短期对象 URL 与 expiresAt | 404 keyframe 不属于该 Job/owner；503 对象存储不可用 |

冻结响应类型见后端 `M2ApiContracts` 和前端 `contracts.ts`：

- Context status：`BUILDING | READY | FAILED`
- 通道状态：`PENDING | AVAILABLE | DEGRADED | FAILED`
- `VideoSegment`：`segmentId/ordinal/startMs/endMs/asrText/ocr/keyframes`
- Keyframe 只暴露稳定 ID、全片时间点和 owner-checked access link，不暴露 MinIO object key。
- 临时对象响应只含 URL 与过期时间；不得包含长期凭据、bucket、object key 或内部路径。

## 4. 状态与事件合同

- ADR-0006 允许 M2 context-only pipeline 在完成 Context 事务后从 `CONTEXT_BUILDING` 直接进入 `READY`。
- M2 不创建或展示 `INDEXING` attempt/event；M3 使用新 pipeline version 才走真实 INDEXING。
- `READY` 表示当前 pipeline version 的必需产物就绪，不表示 M3 问答/索引能力已经存在。
- OCR 降级不改变 Job 为 FAILED；Context 为 READY 且 `ocrState=DEGRADED`。
- ASR 永久失败时 Context/Job 进入稳定失败，不允许只用 OCR 冒充完整 VideoContext。
- 分片进度、通道降级、失败和 READY 均来自持久化 JobEvent/Context，不由前端计时器生成。

## 5. Given/When/Then 验收场景

### M2-A01：45-90 分钟视频生成完整时间轴

**Given** 权利明确、SHA-256 已记录、时长 45-90 分钟且包含讲解与幻灯片变化的本地教学视频
**When** owner 上传并提交 `m2-context-v1` Job
**Then** Context 进入 READY，Segment 从 0 覆盖到可信 `durationMs`，时间窗合法且单调，ASR/OCR/关键帧可按时间检查；不存在伪 INDEXING 记录。

### M2-A02：OCR 整通道失败仍保留 ASR

**Given** ASR 分片全部成功，Tesseract 不可用或 OCR 达到冻结的整通道失败条件
**When** Context 编排收敛
**Then** Job 进入 READY，`asrState=AVAILABLE`、`ocrState=DEGRADED`，ASR Segment 和播放器仍可用，SSE/API/刷新均显示相同降级事实。

### M2-A03：ASR 永久失败终止任务

**Given** SiliconFlow 返回明确不可重试响应或有限重试耗尽
**When** ASR 编排处理失败
**Then** Job 通过统一迁移进入 FAILED，保存稳定错误与调用审计；OCR 文本不能把任务改成 READY。

### M2-A04：ASR 已完成分片在重启后不重做

**Given** 前 N 个 60 秒分片的转录与 completed Checkpoint 已原子提交
**When** 应用在后续分片处理中被终止并以相同输入指纹重启
**Then** 前 N 个分片调用计数增量为 0，只 claim 未完成/明确可重试分片，最终 Context 不重复 Segment。

### M2-A05：供应商未知结果不被当作肯定失败

**Given** 请求已发出后发生超时或连接中断，无法确认供应商是否完成
**When** Adapter 归一化结果
**Then** `model_call` 记录 UNKNOWN_OUTCOME、trace/fingerprint/耗时；系统不在同一执行线程立即重试并重复付费。

### M2-A06：MQ 重投与并发 Consumer 收敛

**Given** 同一 Job 消息重复/乱序到达且两个 Consumer 竞争
**When** 数据库 CAS、阶段指纹和分片唯一约束裁决
**Then** 每个高成本输入只有一个有效执行者；已完成分片为 ACK/no-op；消息体不含视频、完整转录或模型响应。

### M2-A07：取消清理子进程和临时文件

**Given** FFmpeg 或 Tesseract 正在运行且用户发起合法取消
**When** Worker 观察到取消/中断
**Then** 子进程被销毁、流被关闭、受控临时目录被清理；半成品不标 completed，Job 经统一入口进入 CANCELED。

### M2-A08：跨 owner 不可读取 Context 或对象

**Given** owner A 拥有媒体、Job、Context 和关键帧，owner B 已登录
**When** B 枚举或直接请求 A 的 Context、Segment、播放或关键帧路径
**Then** 所有请求统一返回不泄露存在性的 404，且不会签发 MinIO URL。

### M2-A09：浏览器时间轴点击定位与刷新恢复

**Given** READY 或 OCR DEGRADED Context
**When** 用户点击 ASR/OCR/关键帧条目、刷新页面并在临时 URL 过期后继续操作
**Then** 播放器 seek 到声明时间，刷新从服务端恢复相同事实，URL 可重新签发，页面不持有 object key/长期凭据且 Console 无错误警告。

### M2-A10：处理中页面不伪造完成阶段

**Given** Context 只有部分分片 completed
**When** 用户刷新或 SSE 重连
**Then** 页面以 MySQL 事件/Context 为准，只显示已持久化完成项；未完成 ASR/OCR/关键帧和 INDEXING 不被画成完成。

### M2-A11：跨目标复用视频固有产物

**Given** 同一媒体以相同 extraction/provider version、不同分析目标提交两个 Job
**When** 第二个 Job 构建 Context
**Then** 指纹一致的音频、关键帧、ASR/OCR 产物被复用，不因目标不同重复高成本调用；目标相关结果仍保持 Job 隔离。

### M2-A12：版本变化不静默复用

**Given** extraction、alignment、ASR model 或 OCR binary/language-pack 摘要变化
**When** 新 pipeline version 执行
**Then** 输入指纹变化，不复用不兼容 completed 产物；旧 Context 和调用记录保持只读可追溯。

### M2-A13：Redis 清空或不可用不改变 Context 事实

**Given** 部分 ASR/OCR 分片已把结果、调用审计和 completed Checkpoint 原子提交到 MySQL，Redis 只承载现有 Job 短租约/active key
**When** Redis 执行 `FLUSHDB`、容器重建或在恢复期间暂时不可达
**Then** Context、工作项、通道状态和调用记录仍以 MySQL 为准；已完成分片的供应商调用增量为 0，未完成分片仍须通过数据库指纹、唯一约束和 version CAS 才能执行，锁丢失不能产生第二个完成事实。M2 不引入 Checkpoint/完整转录/关键帧清单 Redis 缓存，也不把 Redis Pub/Sub 当作可回放事件。

## 6. T0 本机证据

2026-11-04 完成以下实际检查：

- 长样本：`benchmark/local/videopilot-m2-synthetic-45m.mp4`，SHA-256 `E21859536375E9B6AEF70B4AAFCF06E5FADEBF8C4898278273BE66B02EE51869`，时长 `2700000 ms`，英语合成语音，`1280x720` H.264 + AAC，权利为 `synthetic`。
- 视觉预期：每分钟更新 `Minute N of 45`，持续显示 ASR/OCR/Timeline/Recovery/Checkpoint 关键词与毫秒可见时钟；首、中、尾帧已人工抽查。
- 样本边界：该文件用于冻结 45 个 60 秒切片、OCR 边界和恢复输入，不代表真实世界 ASR/OCR 质量；P8/P67 因授权未核实不进入正式 benchmark，M2 最终退出前仍须增加真实开放许可教学视频。
- 可提交 fixture：`server/src/test/resources/fixtures/m2/context-ready.json` 只含脱敏合成 Context/Segment，时长 3 秒，由 `M2ContractTest` 实际读取；没有视频、完整转录或供应商响应进入 Git。
- 凭据：`.env` 中 `SILICONFLOW_API_KEY` 存在且 `.env` 未被 Git 跟踪；探针使用默认 `https://api.siliconflow.cn/v1`，未输出或提交密钥。
- 成功探针：12.07 秒合成 WAV，SHA-256 `729334F4EA85EA8587332E354ACCBEEC6131CAE366909DEB3727F0C208A9A46C`；记录请求 HTTP 200、耗时 2416 ms、非空 text、响应键 `duration,text,usage` 和供应商 trace 前缀 `7888e1e8-766...`。
- 字段探针：2.197125 秒合成 WAV，SHA-256 `8CA1665CB4AB522536C8380B6558B0B7CABD353AAB1FE75A1D9F835E9BE719CB`；HTTP 200，返回 `duration=2.197125`（浮点秒）与 `usage={type: duration, seconds: 2}`。
- 错误探针：使用有效认证但缺少 `file` 的 multipart 请求返回 HTTP 400，归为明确不可重试输入错误。
- 调用说明：共发生 3 次成功合成音频调用；第一次已返回但本地响应头解析脚本失败，第二次记录完整顶层形状，第三次用 2.2 秒音频确认嵌套字段。另有 1 次缺文件 400。未保存或提交原始响应/完整转录。
- 宿主机 PATH 仍没有 FFmpeg/FFprobe/Tesseract，默认 Java 仍为 8；本次通过 Git 忽略的本地 FFmpeg 工具和显式 Temurin 21 完成验证，正式运行环境由 T2/T4 接入。

## 7. T0 自动化证据

- RED：`M2ApiContracts`/前端 M2 路径缺失，合同测试按预期失败；状态边测试在实现前要求 `CONTEXT_BUILDING -> READY`。
- GREEN：后端 `JobStateMachineTest,M2ContractTest` 通过；前端 `contracts.test.ts` 5 项通过。
- fixture RED/GREEN：新增读取测试先因 `/fixtures/m2/context-ready.json` 缺失失败；补入脱敏合成 fixture 后 `M2ContractTest` 3 项通过，聚焦命令耗时 17.6 秒。
- 后端任务结束门：显式 Temurin 21 执行 `server\mvnw.cmd test`，210 项测试零失败，JaCoCo 与 ArchUnit 通过，耗时 50.6 秒。
- 前端任务结束门：执行 `client\npm.cmd run check`，类型检查、Lint、56 项测试、覆盖率和生产构建通过，耗时 29.6 秒。
- diff/Floor：`git diff --check -- docs tasks server client benchmark .env.example` 与脱敏凭据模式扫描通过；`.env` 未跟踪，视频/探针音频/原始响应均未进入 Git。
- 未执行：`verify -Pintegration`、依赖审计和真实浏览器检查属于后续实现/合并门；T0 未修改数据库、消息或运行时 UI，不以未执行项冒充通过。

## 8. T3 自动化证据

- Adapter RED/GREEN：`SiliconFlowSpeechRecognitionAdapterTest` 先因实现缺失失败；实现后验证 multipart、Bearer 鉴权、trace、整片文本到可信全片窗口映射、计量、响应大小以及 HTTP 错误分类。
- 编排 RED/GREEN：`AsrExecutionApplicationServiceTest` 验证成功审计、completed 复用、有限可重试失败和 UNKNOWN 均不会在同一执行线程重发。
- MySQL 恢复：`AsrRecoveryIT` 在 V10 真实迁移后验证首次成功、第二次调用增量为 0；超时 UNKNOWN 再执行仍为 0；陈旧 STARTED 原子转为 UNKNOWN，不重新调用供应商。
- 审计字段：成功调用保存 provider/model/attempt、本地音频毫秒、供应商毫秒、trace、latency 和 `price_status=UNAVAILABLE`，不伪造价格或零成本。
- Redis 边界：ASR application/domain/infrastructure context 路径未引入 Redis 状态依赖；恢复判断完全读取 MySQL 工作项和 `model_call`。
- 范围说明：T3 完成 ASR 能力与恢复薄切片；真实 Job handler 串联、Context 最终发布和 Job 稳定失败迁移按任务依赖保留到 T5。

## 9. T4 自动化证据

- Adapter RED/GREEN：`TesseractOcrAdapterTest` 先因实现缺失失败；实现后以独立子进程验证版本/语言包指纹、TSV 置信度与区域解析、输出上限、坏图分类、缺失二进制降级以及超时进程销毁。
- 编排 RED/GREEN：`OcrExecutionApplicationServiceTest` 验证结果与调用审计原子完成、completed 复用、单帧永久失败后继续剩余帧并返回 `DEGRADED`。
- MySQL 恢复：`OcrRecoveryIT` 在 V11 真实迁移后验证首次成功、第二次 OCR 调用增量为 0；永久坏帧重复执行仍为 0，并保留失败工作项和调用审计。
- 审计字段：成功调用记录 Tesseract 二进制版本与语言包摘要、图片像素、文本字符、耗时、`estimated_cost=0`、`currency=USD`、`price_version=local-zero-v1` 和 `price_status=AVAILABLE`。
- Redis 边界：OCR application/domain/infrastructure context 路径未引入 Redis；工作项、attempt、结果引用和审计均以 MySQL 为准，完整 OCR 文本写入受控对象产物和 MySQL Checkpoint，不进入 Redis 或消息。
- 范围说明：T4 产出 OCR 通道结果与 `DEGRADED` 编排结论；把通道状态发布到最终 Context、生成 VideoSegment 并推进 Job READY 属于 T5。
