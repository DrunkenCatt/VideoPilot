# M3 验收合同：混合检索与有证据问答

## 1. 文档状态

- 状态：T0 已完成；真实中文媒体、18 道人工复核种子题、BGE-M3 与 SiliconFlow DeepSeek 探针及 ADR-0007 均已冻结。
- 冻结日期：2026-11-05。
- 适用 pipeline version：`m3-evidence-qa-v1`（T1 实现时接入配置，T0 不提前修改运行时默认值）。
- 继承约束：ADR-0002、ADR-0003、ADR-0005、ADR-0006，`AGENTS.md`、`CONSTRAINTS.md` 和 Roadmap 5/6/7/10/11/13 节。
- 目标：冻结 M3 的真实样本选择、标注口径、REST/TypeScript 形态、外部供应商事实和退出场景，不在 T0 实现检索、索引或回答业务逻辑。

## 2. 最小产品边界

M3 只支持围绕一个 owner 可见、使用 M3 pipeline 建成索引的 `AnalysisJob` 进行检索和一次性问答。M3 不创建会话、聊天线程、AgentRun、ToolCall、Query Rewrite 或流式 token 协议。

后端公共入口固定为：

| Method | Path | 语义 |
| --- | --- | --- |
| `GET` | `/api/v1/analysis-jobs/{jobId}/retrieval?query=...&topK=...` | 对当前 Job 的视频执行有界混合检索 |
| `POST` | `/api/v1/analysis-jobs/{jobId}/answers` | 使用 `Idempotency-Key` 提交一次有证据问答 |
| `GET` | `/api/v1/analysis-jobs/{jobId}/answers?cursor=...&pageSize=...` | 游标读取已发布回答或拒答 |
| `GET` | `/api/v1/analysis-jobs/{jobId}/answers/{answerId}` | 读取一条已发布回答或拒答 |

所有入口先按当前登录 owner 隐藏资源存在性。Job 不属于 owner、Job 不存在和关联 Context 不可见统一返回 `404 RESOURCE_NOT_FOUND`。尚未完成 M3 索引的 Job 返回 `409 CONFLICT`，不会把 M2 的 `READY` 自动解释成可问答。

## 3. 冻结限制

| 参数 | T0 合同 |
| --- | ---: |
| query/question 最大长度 | 1,000 字符 |
| 默认 / 最大 retrieval TopK | 5 / 20 |
| 默认 / 最大回答历史页大小 | 20 / 100 |
| 单答案最大 Claim 数 | 8 |
| 关键词/OCR 候选数 | T1-T3 在不超过 200 的范围内冻结到 retrieval version |
| Citation Alignment 邻域 | 声明 Segment 本身及前后各 1 个 Segment |

越界、空白 query、非法 cursor/pageSize/topK 在 Controller 边界返回 `400 INVALID_REQUEST`。响应不包含 ownerId、MinIO object key、向量、完整 Prompt、供应商原始响应、内部路径或 API Key。

## 4. 检索合同

`EvidenceHit` 是一次查询的候选证据，不是新的持久化业务实体。响应至少包含：

- `chunkId`、组成它的 `segmentIds`、合法 `startMs/endMs`；
- 有界 `excerpt` 和来源通道 `ASR/OCR/KEYFRAME`；
- semantic、keyword、visual 的 raw/normalized score 及最终 total；
- response 级 `retrievalFormulaVersion`、`embeddingVersion`、`chunkVersion`；
- response 级 `degradedChannels`：`VECTOR_STORE`、`SEMANTIC`、`OCR`。

初始公式为 `0.60 * semantic + 0.25 * keyword + 0.15 * visual`。某通道不可用时，不把剩余权重悄悄重新归一化成“完整检索”；结果保留原公式和降级事实。T3 可以在版本化规则中冻结空通道为 0 的计算细节，但不得改变字段含义。

## 5. 回答合同

`POST answers` 必须携带客户端稳定生成的 `Idempotency-Key`。同 key + 同 owner/job/question 返回同一结果；同 key + 不同请求指纹返回 `409 IDEMPOTENCY_KEY_REUSED`；并发 in-flight 重复请求返回稳定 `409 CONFLICT`，不能再次调用模型。

已发布响应只有两个判别状态：

- `ANSWERED`：`claims` 非空，`refusalReason=null`，每个 `AnswerClaim` 的 `citations` 非空。
- `REFUSED`：`claims=[]`，`refusalReason` 为 `NO_SUFFICIENT_EVIDENCE`、`EVIDENCE_BELOW_THRESHOLD`、`CITATION_VALIDATION_FAILED` 或 `QUESTION_OUT_OF_SCOPE`。

传输失败、超时、鉴权失败或未收敛的模型调用不是“拒答”，使用统一 `ApiError` 返回并保留服务端调用审计。回答携带 `promptVersion`、`modelVersion`、`embeddingVersion`、`retrievalFormulaVersion` 和 `chunkVersion`，缺任一版本不得发布为 `ANSWERED`。

## 6. 确定性证据门

Java 发布前逐条验证：

1. Job、Context、Chunk、Segment 和引用都属于当前 owner 与当前视频。
2. 引用 ID 必须来自本次检索候选；模型不能自行发明证据 ID。
3. `0 <= startMs < endMs <= media.durationMs`，并与引用 Segment/Chunk 时间窗相交。
4. 引用 quote 能在声明 Segment 的 ASR/OCR 原文中确定性定位。
5. 每条事实 Claim 至少绑定一条通过验证的 `CitedEvidence`。
6. 确定性失败不可被 LLM 评分、解释或“我确认正确”覆盖。

Citation Alignment 只允许在声明 Segment 与相邻各一个 Segment 内执行 Unicode/空白/标点规范化及 T1-T4 冻结的有界近似匹配。它必须保留原候选 quote、对齐类型和最终位置；禁止全片搜索相似文本后移动时间戳。

T0 冻结部分 Claim 失败策略为：只要候选答案中任一事实 Claim 无法通过硬门，整条候选发布为 `REFUSED/CITATION_VALIDATION_FAILED`。M3 不静默删除坏 Claim 后把残缺答案标成完整成功，也不增加第二次 LLM 修复调用。

## 7. 人工种子标注合同

M3 复用 M0 已提交的 `benchmark/schemas/retrieval-case.schema.json` 和 `benchmark/schemas/answer-case.schema.json`，不再创建第二套字段名。每题必须记录：

- `caseId`、`dataVersion`、`split=dev`、`sourceRights`；
- `question`、`answerability`、相关 `timeWindow`、`evidenceChannels`；
- `requiredFacts`、`forbiddenClaims`；
- 检索题的 `expectedHits` 或回答题的 `referenceAnswer/requiredCitations`；
- `review.status`、`reviewerCount` 和必要说明。

M3 工程种子只使用 `dev`；M5 才按视频/主题建立隔离的冻结 test。正式 M3 基线只纳入 `review.status=approved` 的题；当前单人项目可报告一人复核的工程基线，但不得把它描述成 README 中“双人复核”的正式发布集。

建议最小组成是 12 道检索题和 6 道回答题，其中至少包含：2 道精确 ASR、2 道模糊改写、2 道 OCR/板书主导、2 道跨 Segment、2 道不可回答；其余用于课程概览和复杂度比较。若真实材料无法支持某分组，记录缺口，不伪造样本凑数。

## 8. 正式种子来源选择

项目负责人于 2026-11-05 指定本地文件 `bilibili-BV1cr4y1671t-p08-redis-data-structures.mp4` 作为 M3 单视频 `dev` 工程种子，并授权用于本项目本地开发评测。该授权不代表拥有原作者版权，也不允许重新分发原视频、完整转录或未脱敏证据。

| 字段 | 记录 |
| --- | --- |
| 来源页面 | `https://www.bilibili.com/video/BV1cr4y1671t/?p=8` |
| Source ID | `BV1cr4y1671t_p8` |
| 主题 | Redis 数据结构入门 |
| 语言 | 中文，画面可能混有英文技术术语 |
| 本地使用边界 | 项目负责人授权本地开发评测；不提交或再分发媒体、完整转录、OCR 与原始模型响应 |
| SHA-256 | `A80B60825E04B95C6647EC8107B72E21FBA3B32019F702FDBB96C83D92812D23` |
| 媒体时长 | `434.746` 秒（`00:07:14.75`） |
| 媒体规格 | `1920x1080`、H.264、30 fps；AAC 44.1 kHz 立体声 |
| 文件大小 | `17,331,783` bytes |
| 本地状态 | 2026-11-05 已重新计算 SHA-256，并用 FFmpeg 9.0.2 读取媒体信息；文件保持在 Git 忽略的 `benchmark/local/` |

该视频取代未成功落盘的 MIT OCW 候选，不再需要下载 YouTube 或 Archive.org 媒体。其本地来源清单是 `benchmark/local/bilibili-BV1cr4y1671t-p08-redis-data-structures.source.md`。

中文 OCR 前置核对发现旧镜像只有 `eng` 和 `osd`。M3 已在镜像中加入 `tesseract-ocr-chi-sim`，并把 Compose 默认值设为 `chi_sim+eng`。重建后的运行容器为 Tesseract 5.3.4，`tesseract --list-langs` 实测包含 `chi_sim`、`eng`、`osd`；对本视频 60 秒画面执行双语 OCR，成功生成 5,843 bytes TSV。该结果只证明运行依赖和单帧调用可用，整片 OCR 质量仍由种子题人工复核。

该视频已通过真实上传与服务端探测，媒体 ID 为 `b0a85c84-d1df-4ef8-8757-129f248cd274`；服务端记录时长 `434747 ms`。标准分析 Job `a1fcc4a1-aa5c-4635-9c45-39688aebc10d` 已达到 `READY`，形成 8 个连续 Segment，ASR/OCR 均为 `AVAILABLE`。基于真实 Segment 已在 Git 忽略目录生成 12 道检索题和 6 道回答题，均通过既有 JSON Schema 2020-12 校验。项目负责人已逐题核对原视频，18 道题现为 `approved`、`reviewerCount=1`；原始标注继续留在 Git 忽略目录，不提交完整转录、OCR 或答案素材。

## 9. 供应商探针事实

### 9.1 SiliconFlow 托管的 DeepSeek

2026-11-05 从项目根目录 `.env` 加载外置 `SILICONFLOW_API_KEY`，用不含业务数据的合成输入执行：

- Endpoint 为 `POST https://api.siliconflow.cn/v1/chat/completions`，固定模型 ID 为 `deepseek-ai/DeepSeek-V4-Flash`。
- `response_format={"type":"json_object"}` 返回 HTTP 200 和可解析 JSON，`finish_reason=stop`；usage 为 input 103、output 31、total 134 tokens，本次耗时 1045 ms。
- 非法模型返回 HTTP 400 与稳定错误码 `20012`；响应没有成本字段，成本必须记录为供应商未返回，不能伪造为 0。
- SiliconFlow 官方 JSON mode 文档要求客户端处理不完整 JSON并设置足够的 `max_tokens`；因此 T4 仍由 Java 执行响应大小、完整 JSON、Schema 和证据硬门校验。
- API 未提供 exactly-once 合同；幂等由项目输入指纹、`model_call` 状态和 UNKNOWN 规则保证。探针未输出或保存 Key、完整响应、Prompt 或候选文本。

结论：ADR-0007 将 MVP LLM 冻结为 SiliconFlow 托管的 `deepseek-ai/DeepSeek-V4-Flash`；直连 DeepSeek 不进入 MVP 主路径。

### 9.2 BGE-M3

2026-11-05 从项目根目录 `.env` 加载外置 `SILICONFLOW_API_KEY`，向 `https://api.siliconflow.cn/v1/embeddings` 发送不含业务数据的最小探针：

- 模型 ID 为 `BAAI/bge-m3`；单条和双条合成输入均成功，返回顺序一致的 1024 维向量。
- 单条 usage 的 input/total tokens 均为 12、耗时 388 ms；双条 usage 均为 17、耗时 408 ms。
- 响应未提供成本字段，因此成本记录必须允许明确的“供应商未返回”，不能伪造为 0。
- 非法模型返回 HTTP 400 与稳定错误码 `20012`。供应商最大 batch 和限流阈值未在 T0 做破坏性探测，由 T2 Adapter 设置项目侧硬上限并做边界验证。
- 探针只读取 Key 是否可用，不输出或保存 Key、完整响应、输入文本或向量。
- API 未提供 exactly-once 合同；T2 必须用稳定输入指纹、持久化调用状态和稳定 Qdrant point ID 保证应用侧幂等。

结论：BGE-M3 endpoint、模型 ID、1024 维、最小 batch、Token usage、成本不可得和非法模型错误边界已完成真实验证；项目最大 batch、限流和重试阈值留给 T2。

## 10. ADR 判断

T0 新增 ADR-0007：

- M3 pipeline version 和真实 `INDEXING` 路径已由 ADR-0006 决定。
- 模型/Embedding Adapter、调用审计、UNKNOWN、有限重试和 Java 预算权威已由 ADR-0005 决定。
- MVP LLM 的实际托管方从未冻结变为 SiliconFlow，且默认模型与配置名发生跨模块变化，因此用 ADR-0007 记录，不改写 ADR-0005 的历史边界。
- owner/Job 身份、统一状态迁移分别继承 ADR-0002/0003。
- 确定性引用硬门、受限 Alignment 和每 Claim 必须引用已是 `AGENTS.md`、`CONSTRAINTS.md` 与 Roadmap 的既定架构规则。

除 ADR-0007 外，T0 只冻结局部 API 形态、限制、枚举和“任一 Claim 失败则整答拒答”的 MVP 发布策略。

## 11. Given/When/Then 验收场景

### M3-A01：确定性生成版本化 VideoChunk

- Given 一个 READY、时间窗连续的 M3 VideoContext。
- When 相同 Context/version 两次执行 Chunk 构建。
- Then 产生相同约 5 分钟 Chunk、Segment 归属和输入指纹，第二次成为 no-op。

### M3-A02：版本变化不静默覆盖索引

- Given 已发布 `chunk-5m-v1/bge-m3-v1` 索引。
- When Chunk/Embedding/index schema 任一版本变化。
- Then 创建新索引版本；旧版本在新版本成功前仍可审计，不把旧向量标成新版本。

### M3-A03：M3 使用真实 INDEXING 状态

- Given 一个 `m3-evidence-qa-v1` Job 完成 Context。
- When Chunk 与允许的完整/降级索引路径执行。
- Then Job 经统一入口记录 `CONTEXT_BUILDING -> INDEXING -> READY`；M2 context-only Job 仍遵循 ADR-0006。

### M3-A04：Embedding 重投不重复高成本调用

- Given 一批 Chunk 的 Embedding 与调用审计已经原子完成。
- When 应用重启、MQ 重投或同 Job 并发消费。
- Then 同指纹 BGE-M3 调用增量为 0，稳定 point ID 不产生重复向量。

### M3-A05：Qdrant 删除可重建

- Given MySQL 保存已完成 Chunk、索引状态和重建所需向量事实。
- When 对应 Qdrant collection 被删除。
- Then 系统不重新调用 BGE-M3 即可重建 collection，MySQL 完成事实不被 Qdrant 反向覆盖。

### M3-A06：Qdrant 不可用时明确降级

- Given Chunk 和 Embedding 已存在但 Qdrant 不可用。
- When 用户检索或提问。
- Then 使用有界内存 semantic + keyword/OCR 路径，并返回 `VECTOR_STORE` 降级事实。

### M3-A07：Embedding 不可用时保留关键词/OCR

- Given BGE-M3 明确失败且有限重试耗尽。
- When 用户检索或提问。
- Then semantic 得分为 0，关键词/OCR 仍可召回，响应标记 `SEMANTIC`，不宣称完整混合检索。

### M3-A08：三路分数与版本可解释

- Given semantic、keyword、visual 各自产生候选。
- When 融合排序完成。
- Then 每个 `EvidenceHit` 带 raw/normalized/total score，响应带固定公式、Embedding 和 Chunk 版本。

### M3-A09：每条事实 Claim 有可定位引用

- Given DeepSeek 返回结构化候选且引用当前检索命中。
- When Java 硬门验证通过。
- Then 每个发布 Claim 至少有一个属于当前视频、时间窗合法且原文可定位的 `CitedEvidence`。

### M3-A10：伪造与错绑引用被硬门拦截

- Given 候选引用不存在的 evidence ID、其他视频 Segment、越界时间窗或与 Claim 无关的 quote。
- When 执行确定性验证。
- Then 不发布任何事实 Claim，结果为 `REFUSED/CITATION_VALIDATION_FAILED`，LLM 无权覆盖。

### M3-A11：Citation Alignment 不得全片漂移

- Given quote 仅能在远离声明时间窗的 Segment 找到相似文本。
- When Alignment 执行。
- Then 对齐失败；只有声明 Segment 与前后各一个 Segment 可参与修复。

### M3-A12：无证据问题明确拒答

- Given 人工标注为 unanswerable 的问题或所有命中低于阈值。
- When 用户提交问题。
- Then 返回 `REFUSED` 和稳定原因、空 claims，不生成“可能”“根据常识”等无证据事实。

### M3-A13：Idempotency-Key 防止重复 LLM 调用

- Given 同 owner/job/question 和同一 idempotency key 的请求已经完成或仍在执行。
- When 客户端因超时重试。
- Then 返回既有结果或稳定冲突，不产生第二次 DeepSeek 调用；key 绑定不同请求时明确拒绝。

### M3-A14：跨 owner 不泄露检索与回答

- Given owner B 猜到 owner A 的 jobId/answerId/chunkId。
- When B 调用检索或回答 API。
- Then 返回与资源不存在一致的 404，不泄露文本、时间窗、版本或降级状态。

### M3-A15：浏览器引用跳转与刷新恢复

- Given 一个 `ANSWERED` 结果含多个 Claim/Citation。
- When 用户键盘或鼠标激活时间戳并刷新页面。
- Then 复用 M2 播放器定位并高亮对应 Segment，刷新后从 MySQL 恢复已发布结果。

### M3-A16：拒答与降级具有一等展示状态

- Given 正常回答、拒答、Qdrant 降级、Embedding 降级或请求失败。
- When 用户查看双工作区。
- Then 每种状态有明确文本和可访问状态提示，不把降级/拒答显示为普通成功，也不产生 Console error/warning。

### M3-A17：种子基线不夸大结论

- Given 至少 1 条真实教学视频的 approved 工程种子题。
- When Runner 使用生产检索代码生成报告。
- Then 报告 `Recall@3`、`Hit@1`、`MRR@3`、分组、版本、commit、样本数和限制；`Recall@3 >= 80%` 只作为扩样目标。

## 12. T0 自动化证据

| 检查 | RED | GREEN |
| --- | --- | --- |
| 后端 M3 合同 | `M3ApiContracts` 不存在导致 test compile 失败 | `server\mvnw.cmd -q -Dtest=M3ContractTest test` 通过，4 tests |
| 前端 M3 合同 | `jobRetrievalPath is not a function` | `npm.cmd exec vitest run src/api/contracts.test.ts -- --coverage.enabled=false` 通过，8 tests |

本轮任务结束检查：

| 命令 | 结果 |
| --- | --- |
| `server\mvnw.cmd test` | 通过；270 tests，0 failures，0 errors，1 skipped；JaCoCo 门通过；76 秒 |
| `client\npm.cmd run check` | 通过；typecheck、lint、72 tests、build 均通过；branch coverage 80.48% |
| `git diff --check` | 通过；仅有工作区 LF/CRLF 提示，无 whitespace error |
| `server\mvnw.cmd verify -Pintegration` | 未执行；T0 合同没有数据库/MQ/对象存储行为，留待 T1 首个持久化切片 |

合同质量门只是 T0 的一部分；本文件第 9、10、13 节补齐真实探针、ADR 和人工复核后，T0 才正式完成。

## 13. T0 未完成项

- [x] 本地中文 Redis 教学视频已获项目负责人本地评测授权，并记录 SHA-256、可信时长和媒体探测结果。
- [x] 在本地原视频逐题复核 12 道检索题和 6 道回答题的毫秒时间窗，形成 approved 工程种子。
- [x] 使用 SiliconFlow 可调用账户完成 DeepSeek 结构化 JSON、usage、错误和成本不可得探针，并冻结实际模型版本。
- [x] BGE-M3 完成单条/双条、1024 维、usage、非法模型、成本不可得和应用侧幂等边界探针；项目最大 batch/限流阈值明确留给 T2。

T0 明示硬门已解除，可以进入 T1。

## 14. T1 自动化证据

T1 保持最小实现面：`retrieval` 只新增一个 `VideoChunk`、一个确定性 builder、一个聚合 `VideoIndexStore` 和一个 `VideoIndexApplicationService`。持久化 Adapter 直接以参数化 JDBC 在一个事务内发布 index、chunks 与 INDEXING checkpoint，没有为两张表各建 CRUD service/repository。

| 检查 | RED | GREEN |
| --- | --- | --- |
| Chunk/索引 application | `VideoChunkBuilder`、`VideoIndexStore`、`VideoIndexApplicationService` 不存在，测试编译失败 | 5 分钟窗口、尾窗裁剪、Segment 归属、确定性指纹、发布入口单测通过 |
| M2/M3 状态路径 | 旧 pipeline 只支持 `CONTEXT_BUILDING -> READY`，`INDEXING` 不执行 | M2 context-only 仍直接 READY；M3 记录 INDEXING stage/checkpoint 后才 READY |
| MySQL 真源 | V12 与持久化 Adapter 不存在 | Flyway v12、同指纹复用、owner 隔离、两条 Chunk 与 checkpoint 完成集成测试通过 |

T1 集成验证使用本机 Compose 的宿主机端口：MySQL `13306`、Redis `16379`、RabbitMQ `15672`、MinIO `19000`、Qdrant `16333`。第一次直接运行失败是因为 integration profile 默认连接 MySQL `3306`；显式映射到 Compose 端口后发现事务 Adapter 被 `final` 阻止代理，移除 `final` 后测试通过。两次失败均未通过跳过测试或降低断言处理。

| T1 命令 | 结果 |
| --- | --- |
| `server\mvnw.cmd test` | 通过；275 tests，0 failures，0 errors，1 skipped；JaCoCo 与 ArchUnit 通过；70 秒 |
| `server\mvnw.cmd -q -Pintegration "-Dtest=DatabaseMigrationIT,VideoIndexPersistenceAdapterIT" test`（显式 Compose 宿主机端口，server 容器停止） | 通过；Flyway 12 个迁移校验、索引发布/复用、owner 隔离和 checkpoint 完成通过 |
| `server\mvnw.cmd verify -Pintegration` | 已执行但未全绿：运行中的旧 server 容器消费测试 Job，造成恢复/Outbox 污染；宿主机还缺少 `ffmpeg`，既有媒体集成测试无法生成 fixture。T1 相关定向集成测试已在停止 server 后独立通过 |
| `docker compose up -d --build server` | 通过；使用当前 T1 jar 重建并恢复 server，健康检查为 `healthy` |

## 15. T2 自动化证据

T2 沿用 T1 的一个 `VideoIndexApplicationService` 和一个聚合 `VideoIndexStore`，只增加现有 `EmbeddingPort` 的 SiliconFlow BGE-M3 Adapter 与一个窄 `VectorIndexPort`/Qdrant Adapter。没有引入通用 RAG、VectorStore、Provider Registry 或每表 Repository。MySQL 保存每个 Chunk 的版本化向量事实，Qdrant 只作为可删除、可重建的查询副本。

| 检查 | RED | GREEN |
| --- | --- | --- |
| BGE-M3 边界 | Adapter 不存在 | fixture 验证请求模型/维度/usage；batch、字符、Token、响应大小、超时、并发、维度与 HTTP 错误均有界并归一化 |
| 调用审计与重投 | T1 在 Chunk 后直接完成 INDEXING | 调用前写 `model_call`；向量与成功审计同事务；重复执行调用增量为 0；UNKNOWN 保持 INDEXING 且不重发 |
| Qdrant 发布与查询 | 无向量 collection | 版本化 collection、schema 校验、稳定 UUID point、批量 upsert 和 owner/media/context filter fixture 通过 |
| 重建与降级 | Qdrant 是单点且无向量真源 | 真实 Qdrant collection 删除后由同一持久化向量重建；连接失败发布 `VECTOR_STORE_DEGRADED`；Embedding 明确失败发布 `SEMANTIC_DEGRADED` |

本机既有 `videopilot` 数据库存在 T2 之前的 Flyway V3 checksum 漂移。验证未执行 `flyway repair`，而是在专用 `videopilot_t2_validation` 数据库从 V1 到 V13 全量迁移，避免修改既有 schema history。Qdrant 使用本机 Compose 容器的真实 HTTP 边界；为保持 MVP 依赖最少，没有为一个已有 Compose 服务额外引入 Testcontainers 依赖。

| T2 命令 | 结果 |
| --- | --- |
| `server\mvnw.cmd clean test` | 通过；287 tests，0 failures，0 errors，1 skipped；包含 BGE/Qdrant fixture、应用编排、ArchUnit，JaCoCo 行覆盖率门恢复到不少于 80% |
| T2 定向 MySQL/Qdrant 集成测试（Compose 宿主机端口，专用数据库） | 通过；V1-V13 全量迁移、成功/降级/UNKNOWN 审计、重投零增量、owner 隔离、真实 Qdrant 删除重建均通过 |
| BGE 维度条件 mutation | 临时反转维度条件后 `SiliconFlowEmbeddingAdapterTest` 按预期失败；恢复生产条件后测试通过 |
| `server\mvnw.cmd verify -Pintegration` | T2 相关边界已定向执行；全套仍受宿主机缺少 FFmpeg 和既有 `videopilot` Flyway checksum 漂移阻塞，不通过跳过测试或 repair 掩盖 |

验证结束后已删除本轮创建的 `videopilot_t2_validation` 临时数据库及对应临时授权，并恢复原有 server 容器到 `healthy`；该容器仍运行提交前的 T1 镜像，未用 checksum repair 强行部署 T2。

## 16. T3 自动化证据

T3 只增加一个 `RetrievalApplicationService`：它直接完成 semantic/keyword/OCR 三路候选、按通道最大值归一化、固定 `0.60/0.25/0.15` 加权、Chunk 去重和稳定 TopK。关键词与 OCR 从 MySQL 的最多 50 个 owner-scoped Chunk 在 Java 中确定性计分；没有新增 Elasticsearch、分词服务、Retriever 策略族、检索 DSL 或第二套索引。REST 与 benchmark Runner 使用同一个 application service 和 `EvidenceHit`。

| 检查 | RED | GREEN |
| --- | --- | --- |
| 三路融合与降级 | 无生产检索入口 | 正常、Qdrant 连接失败的内存余弦回退、Embedding 不可用的 keyword/OCR 路径使用同一融合代码，并返回明确 degraded channels |
| owner/复用边界 | 新 Job 复用视频固有 Index 时按 `source_job_id` 查询会误报 404 | 按当前 Job 的 owner/media/pipeline 解析可复用 READY Context/Index；跨 owner 仍返回 NOT_FOUND |
| BGE-M3 真实中文请求 | Adapter 发送可选 `dimensions=1024` 时真实 API 返回 HTTP 400/code 20015 | 请求省略供应商不接受的可选字段，响应仍强制校验 1024 维；真实中文索引和 12 次 query embedding 均成功 |
| 零分候选 mutation | 临时把 `total > 0` 改为 `>= 0` | `returnsAnEmptyResultInsteadOfLeakingZeroScoreCandidates` 按预期失败；恢复条件后通过 |

版本化报告为 `benchmark/reports/m3-t3-baseline.json`，生产代码 commit 为 `46f0ae7`。单视频工程种子共 12 道检索题，其中 11 道 answerable 进入检索指标，1 道 unanswerable 不进入 Recall 分母：

| 分组 | 可评分题 | Recall@3 | Hit@1 | MRR@3 |
| --- | ---: | ---: | ---: | ---: |
| 总体 | 11 | 100.00% | 81.82% | 90.91% |
| 精确 | 4 | 100.00% | 75.00% | 87.50% |
| 模糊 | 3 | 100.00% | 100.00% | 100.00% |
| OCR/关键帧 | 4 | 100.00% | 75.00% | 87.50% |

分组沿用冻结 schema，不增加第二套标注字段：包含 OCR/keyframe 的题归入 OCR；多 expected hit 或证据窗超过 60 秒归入模糊；其余归入精确。该结果只有一条 7 分钟中文教学视频、11 道可评分题且仅一名复核者，只是工程基线，不能外推为发布集质量或正式达到产品 `Recall@3` 门槛。

| T3 命令 | 结果 |
| --- | --- |
| `server\mvnw.cmd clean test` | 通过；298 tests，0 failures，0 errors，1 skipped；ArchUnit 与 JaCoCo 80% 门通过 |
| T3 定向 MySQL/Qdrant 集成测试（V1-V13 专用数据库） | 通过；真实 Qdrant 1 test，索引/owner/审计/复用/内存回退 4 tests |
| `server\mvnw.cmd -Dtest=RetrievalBenchmarkLocalIT test -Pintegration` | 通过；真实 SiliconFlow BGE-M3、真实 Qdrant、12 道本地种子，生成版本化脱敏报告 |

本机既有 `videopilot` 数据库仍存在历史 Flyway checksum 漂移，未执行 repair。T3 使用一次性 `videopilot_t3_validation` 从 V1-V13 全量迁移；验证完成后删除临时数据库和授权，并查询 `information_schema` 确认该库数量为 0。当前 server 容器仍是提交前镜像，未把验证库或本地 benchmark 数据带入运行容器。
