# M2 验收合同与证据记录

## 1. 文档状态

- 里程碑：M2 时序多模态 VideoContext
- 合同冻结日期：2026-11-04
- 当前阶段：T0-T6 已完成；下一任务为 T7 真实视频、恢复与降级验收矩阵
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

## 10. T5 自动化证据

- 对齐 RED/GREEN：`VideoContextAlignerTest` 先因实现缺失失败；实现后验证 60 秒窗口完整覆盖可信时长、首尾裁剪、边界观察只进入一个 Segment、ordinal 单调以及内容摘要/标识稳定。边界条件突变为闭区间时测试按预期失败。
- 编排与重复消息：`VideoContextPipelineApplicationServiceTest` 验证派生、ASR、OCR、对齐、发布和 READY 事件串联，OCR 降级仍可 READY；`AnalysisJobExecutionHandlerTest` 验证不产生 INDEXING、重复消息不重开、Redis 锁不可用时 MySQL CAS 仍裁决、忙锁/未完成步骤返回 retry。
- OCR 清单修正：`OcrExecutionApplicationServiceTest` 验证派生的关键帧 manifest 会展开为逐帧 OCR 工作项，不把 JSON 清单作为图片输入。
- owner 查询：application 测试覆盖 Context 未就绪 409、跨 owner 404、游标和时间窗限制、READY 原视频短链；真实 MySQL `VideoContextPersistenceAdapterIT` 7 项通过，覆盖发布回滚、owner 隔离和 Segment 游标分页。
- 后端任务结束门：`server\mvnw.cmd clean test` 实际执行 254 项测试零失败、1 项按环境跳过，ArchUnit 通过；Maven 最终仅因当前全包 JaCoCo 行覆盖率 `74% < 80%` 失败，未降低阈值或跳过断言。聚焦 MySQL 集成测试使用 `.env` 连接 Compose，7 项全部通过。
- 未执行：完整 `verify -Pintegration`、真实 TeleSpeechASR 长视频和浏览器播放器验收保留到 T7/T8；T5 不以单元 fixture 冒充长视频退出证据。

## 12. T6 前端播放器与时间轴证据

- RED：Context API client 与播放器/时间轴组件不存在，新增的 9 项聚焦测试先因模块无法解析失败。
- GREEN：Context client 使用服务端返回的 owner-scoped links 读取摘要、游标页和临时访问 URL；页面数据结构不包含 ownerId、对象 key 或长期凭据。
- 交互与恢复：`VideoContextTimeline.test.ts` 覆盖 READY 恢复、OCR DEGRADED、Segment/关键帧 seek、本地播放时间高亮、显式后续页、认证过期、404 隐藏语义、播放/关键帧链接有限续签和失败保留已加载页；Job 工作台继续只维护既有局部状态。
- 前端质量门：`client\npm.cmd run check` 实际执行，69 项测试零失败；statement 86.95%、branch 80.48%、function 85.88%、line 92.20%，typecheck、ESLint 和 Vite production build 均通过。
- 浏览器证据：应用内真实浏览器访问 Vite + Compose server，在 320、375、768px 检查登录壳，`scrollWidth <= innerWidth` 且 Console 无 warning/error。浏览器没有已认证会话，本次未向页面输入本机密码，因此 READY Context 的 Range、seek、刷新和降级真实数据链路仍明确保留到 T7；不以组件 fixture 代替 M2 退出证据。

## 13. T7 真实长视频、恢复与降级证据

### 13.1 45 分钟 Compose 全链路

- 输入为 Git 忽略目录中的 T0 冻结合成教学样本 `benchmark/local/videopilot-m2-synthetic-45m.mp4`，SHA-256 为 `E21859536375E9B6AEF70B4AAFCF06E5FADEBF8C4898278273BE66B02EE51869`，可信时长 `2700000 ms`。上传使用 3 个分片；端到端到 READY 为 `291.6 s`，其中真实 Context pipeline 为 `283.9 s`，MySQL 中 Context 从创建到原子发布为 `281.164 s`。
- 最终 Context 为 READY，ASR/OCR 均 AVAILABLE；45 个连续 `60000 ms` VideoSegment 完整覆盖 `[0,2700000]`，ordinal `0..44`，相邻窗口空洞数为 0。45 个 Segment 均有非空 ASR、OCR 观察和关键帧引用。
- 派生/Checkpoint 为 45 个 AUDIO_SLICE、45 个 ASR_SLICE、90 个 OCR_FRAME 和 1 个 KEYFRAME coordinator，全部 COMPLETED。真实模型审计为 45 次 TeleSpeechASR 成功与 90 次 Tesseract 成功；ASR 输入/输出计量均为 `2700000 ms`，平均/最大延迟 `5345.4/6725 ms`，供应商未提供价格，明确记录 `price_status=UNAVAILABLE`；OCR 处理 `82944000` 像素、产出 `7542` 字符，平均/最大延迟 `292.7/716 ms`，本地成本 `0 USD` 且价格状态 AVAILABLE。
- 开头/中间/结尾抽查 ordinal `0/22/44`，窗口分别为 `[0,60000]`、`[1320000,1380000]`、`[2640000,2700000]`；三个窗口均同时存在 ASR、28 条 OCR 观察与 2 个关键帧引用。验收只记录长度和计数，不保存完整转录或模型原始响应。
- 新增 `scripts/run-m2-compose-sample.ps1` 复用登录、流式分片上传、校验、提交和轮询流程，输出只包含 ID、状态、耗时与计数；不会输出 `.env` 凭据、转录或供应商响应。

### 13.2 故障矩阵

| 场景 | 实际结果 |
| --- | --- |
| 部分 ASR 后硬终止 | 10 分钟样本在 3 个 ASR_SLICE 已原子完成、1 个调用 STARTED 时强制终止。重启和 MQ 重投后 3 个 completed 分片及 3 条成功调用均未增加；中断中的 STARTED 调用保持不确定事实，不会冒险重复付费，陈旧后由既有恢复规则转 UNKNOWN/人工复核。`AsrRecoveryIT` 同时证明 completed 重投为零调用、UNKNOWN 不自动重发。 |
| MQ 重投 | `AnalysisMessageConsumerIT.concurrentAndSerialRedeliveryConvergeOnOneFakePipeline` 验证并发与串行重投只产生一组完成事实；Context pipeline 的同指纹 completed 工作项由 MySQL 唯一约束与 CAS 复用。 |
| Redis `FLUSHDB`/不可达 | 对部分 ASR 作业执行 `FLUSHDB` 前后，MySQL 均保持 `CONTEXT_BUILDING` 且 completed ASR 数保持 3，Redis key 从 2 变 0；停止 Redis 后，已完成 45 分钟 Context 仍可从 MySQL 查询为 READY/45 段。`AnalysisJobCreationIT` 和 `ApplicationRestartRecoveryIT` 覆盖 active key 重建与空 Redis 重启恢复。 |
| OCR 全通道失败 | 用不存在的 Tesseract executable 跑 60 秒新媒体：Job/Context 仍 READY，ASR AVAILABLE、OCR DEGRADED、1 个 Segment 可用；1 次 ASR 成功，4 次 OCR 失败均有稳定分类和零本地成本审计。随后恢复正常 Tesseract 配置。 |
| Tesseract 超时 | `TesseractOcrAdapterTest.killsTimedOutProcess` 验证超时分类、强制销毁子进程和临时文件清理。 |
| 取消竞态 | `AnalysisJobPersistenceAdapterIT.cancellationAndConsumerRaceEndsOnALegalCasBranch` 验证取消与 Consumer 同时 CAS 时只收敛到合法的 CANCELED/CANCEL_REQUESTED 分支。 |
| 双用户越权 | 临时第二 owner 对 45 分钟 Context、Segments 和 playback 均得到 404；临时用户与会话在验证后删除。 |
| CAS 单赢家 | `VideoContextPersistenceAdapterIT.concurrentClaimHasExactlyOneWinner` 在真实 MySQL 上证明并发 claim 恰好一个成功。 |
| 同媒体不同目标复用 | 第二个 Job 复用 READY Context，45 段可查且新增模型调用为 0；查询只复用同 owner、同媒体、同 pipeline 的 READY Context。 |

硬终止恰好发生在一次外部调用期间，因此该调用没有可证明的供应商结果。系统选择 UNKNOWN/复核而不是自动重发，这是 T0 冻结的付费调用安全语义；验收目标“已完成分片调用增量为 0”已满足，不把“不确定调用自动重试并最终 READY”写成已实现能力。

### 13.3 本轮发现并修复的窄缺陷

- 移除整条长视频 pipeline 的外层事务。此前 45 分钟处理期间逐片 completed Checkpoint 对其他连接不可见，进程中断会丢失本轮事务内进度；现在每片的既有事务可独立提交，静态回归测试禁止重新给编排入口增加 `@Transactional`。
- Context Controller 原先依赖组件扫描时机的 `@ConditionalOnBean`，Compose 中实际未注册。新增延后于 `VideoContextAutoConfiguration` 的 Web 自动配置：真实查询服务存在时显式注册，轻量无数据库测试上下文则不装载；生产接口和轻量健康测试均有回归证据。
- 同 owner、同媒体但不同目标的新 Job 原先无法读取已 READY 的共享 Context。Mapper 现在只在同 pipeline 的 READY Context 上复用，关键帧查询使用相同 owner/pipeline 边界；真实 MySQL 集成测试覆盖零新增调用复用。
- Compose server 镜像补入 FFmpeg、Tesseract 与英文语言包，并把正式 M2 默认切片设为 60 秒；`.env.example` 只记录非秘密配置。

### 13.4 质量门与尚待浏览器确认项

- `client\\npm.cmd run check`：通过，69 项测试零失败；statement `86.95%`、branch `80.48%`、function `85.88%`、line `92.20%`，生产构建成功。
- `server\\mvnw.cmd clean test`：通过，265 项测试零失败、1 项按宿主机 FFmpeg 条件跳过；JaCoCo 80% bundle 门与 ArchUnit 通过，约 57 秒。新增结果导向单测覆盖恢复裁决、持久化原子边界、Context API 映射和派生资源限制绑定，没有降低阈值或增加 suppression。
- `server\\mvnw.cmd clean verify -Pintegration`：在带 FFmpeg/Tesseract 的 Java 21 验证容器中连接 Compose 的 MySQL、Redis、RabbitMQ、MinIO 与 Qdrant，265 项单元测试和 52 项集成测试全部零失败；JaCoCo 80% bundle 门与 ArchUnit 通过，总耗时 `6m12s`。验证命令把本机 `.env` 的宿主机端口改为容器内地址，并显式保持 `IDENTITY_SECURE_COOKIE=true`；未修改安全断言或本机 `.env`。
- 运行态 API：重新构建并启动 Compose server 后，45 分钟 Context 查询为 READY，单页返回 45 段且覆盖 `[0,2700000]`；间隔 2 秒重新申请播放短链得到不同 URL，对新 URL 发出 `Range: bytes=0-1023` 返回 HTTP 206 和 `Content-Range: bytes 0-1023/17962074`。未记录短链正文、Cookie 或对象 key。
- 真实浏览器使用负责人确认的本机 `m1-reviewer` 账号登录；密码只从 Git 忽略的 `.env` 读取并提交给 localhost 登录页，未进入聊天、日志或截图。选择正式 45 分钟 READY Job 后，页面显示 `45 / 45 SEGMENTS`，原生 video 的可信时长为 `2700s` 且 `readyState=4`。
- 定位与恢复：鼠标点击 `22:00 — 23:00` 后播放器为 `1320s`；对 `44:00 — 45:00` 按 Enter 后为 `2640s`。刷新页面后仍恢复同一 Job、媒体和 `45 / 45 SEGMENTS`，不是前端假状态。
- 短链与网络：点击“续签播放链接”后 video `src` 发生变化；独立 HTTP Range 核验返回 206 与 `Content-Range: bytes 0-1023/17962074`。页面和记录不包含短链正文、Cookie、object key 或长期 MinIO 凭据。
- 响应式与 Console：320px 时 `scrollWidth=innerWidth=320`，375px 时 `scrollWidth=360 <= innerWidth=375`；两种视口播放器可见且无横向内容溢出。验收期间 Console warning/error 数为 0。截图仅保存在 Git 忽略的 `benchmark/local/t7-browser-timeline.png`。
- 项目负责人于 2026-11-05 审阅上述证据和“合成样本不代表真实课程质量”的限制后确认 M2 工程验收；M3 数据集仍须加入权利明确的真实教学材料。

## 14. T8 文档、质量门与退出记录

- 文档：README、`.env.example`、Compose、`docs/M2_RUNBOOK.md`、供应商目录和 Roadmap 已按实际 M2 能力更新。文档没有宣称 M3 检索、M4 证据问答或真实课程质量已经完成；运行手册明确记录资源上限、OCR 降级、UNKNOWN 外部调用、恢复方式和排障入口。
- 后端完整门：在带 Java 21、FFmpeg/Tesseract 且连接 Compose 依赖的验证容器中执行 `server\\mvnw.cmd clean verify -Pintegration`，265 项单元测试和 52 项集成测试零失败，JaCoCo 80% bundle 门与 ArchUnit 通过，总耗时 `6m12s`。T8 最后移除一处新测试中的泛型 suppression 后，另执行 `-Dtest=VideoContextApplicationServiceTest test`，3 项零失败，编译、覆盖率门通过，耗时 `25.4s`。
- 前端完整门：执行 `client\\npm.cmd run check`，类型检查、ESLint、69 项测试、覆盖率和 production build 全部通过；statement `86.95%`、branch `80.48%`、function `85.88%`、line `92.20%`。该次执行未单独采集墙钟时间，因此不补造耗时。
- 配置与工作树：`docker compose config --quiet`、`git diff --check` 和变更 diff 的凭据模式扫描均通过，匹配数为 0；没有降低覆盖率/ArchUnit 阈值、跳过测试或新增 suppression。Git 的 LF/CRLF 提示不影响检查结果。用户自有的未跟踪目录 `mynote/` 不在本次改动或提交范围内。
- 前端依赖审计：`npm audit --audit-level=high` 退出码为 0，耗时 `11.9s`；高危/严重问题为 0，仍有 11 个 moderate 开发工具链问题。自动完全修复会包含 breaking change，本里程碑不以强制降级依赖换取零告警。
- 统一安全脚本：`scripts/security-check.ps1` 先完成后端 265 项测试、JaCoCo 和 ArchUnit，随后 OWASP Dependency-Check 因 NVD API 连续 16 次请求失败且无进展而人工终止，故整条脚本明确记为未通过，不冒充成功。脚本因前序失败未走到 npm/Gitleaks；npm 审计已单独完成，本机没有 `gitleaks`，也未配置经批准的 `GITLEAKS_IMAGE`，因此 Gitleaks 未执行。后续应配置 NVD API key/可用镜像和批准的 Gitleaks 安装后重跑，不降低 fail-closed 规则。
- 退出结论：负责人已确认上述证据和限制，M2 六项退出门全部核对完成，可以进入 M3。该结论属于工程链路验收；正式质量基线仍不得使用授权未核实的 P8/P67，M3 必须补入权利明确的真实教学材料。
- 提交：本节与 T8 完成记录由同一个 Git commit 承载；具体 commit id 以仓库历史为准，避免在提交内容中写入无法自指验证的哈希。
