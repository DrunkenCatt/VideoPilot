# VideoPilot 总体规划与核心里程碑

> 文档状态：Baseline v1.0
>
> 制定日期：2026-10-26
>
> 参考实现：`C:\Users\zzm\Desktop\com\DoVideoAI`
>
> 用途：作为后续架构决策、需求拆分、实现顺序和阶段验收的共同基线。

## 1. 项目定位

VideoPilot 首版聚焦教学长视频。它要把课程讲解稳定地转换为可检索、可引用、可继续追问的结构化知识，并保证任务不会因为刷新页面、服务重启、消息重投或单个云端 API 抖动而丢失或重复计费。

产品按两条主线推进：

1. **可靠产品闭环**：上传视频 -> 异步解析 -> ASR/OCR 时间对齐 -> 混合检索 -> 有时间戳的回答或拒答 -> 可恢复、可重试、可追踪。
2. **Agent 与反馈改进闭环**：Agent 根据环境观察动态选择工具 -> 验证结果 -> 必要时补检或澄清 -> 收集用户反馈 -> 复核并沉淀失败样本 -> 用冻结评测集验证候选改动 -> 晋级或回滚新版本。

第一版只服务“一个用户围绕一条教学视频进行理解、定位和问答”的核心场景，采用本地或单服务器部署，不开放公网。知识库、多 Agent 协作、自动剪辑、内容发布、支付和多租户都不进入首轮范围。

### 1.1 已确认的产品约束

| 决策项 | 已确认选择 |
| --- | --- |
| 建设方式 | 在当前目录从零新建，不迁移或直接复制 DoVideoAI 业务源码 |
| 核心内容 | 教学视频解读，优先覆盖课程、知识讲解和技术教学 |
| 首版模型 | 固定供应商和模型组合；MVP 完成后再规划多供应商切换 |
| 反馈目标 | 使用经过复核的用户反馈持续改善 Agent，而不是先建设完整学习管理系统 |
| 部署范围 | 本地或单服务器，不开放公网 |
| 视频来源 | 首版只支持本地文件上传；在线视频链接解析后置 |
| 评测数据 | 当前没有现成数据，从 M0 开始同步建设种子语料和标注规范 |
| 项目名称 | `VideoPilot` |

## 2. 不变的核心技术栈

本项目保留 DoVideoAI 的技术方向和主要版本，不以替换基础设施的方式简化问题。

| 层次 | 技术 | 项目职责 |
| --- | --- | --- |
| Web | Vue 3、Vite、SSE、Marked | 上传、视频工作台、进度、问答、证据跳转与学习面板 |
| Java 单体 | Java 21、Spring Boot 3.5.16、Undertow、MyBatis-Plus | API、任务编排、Agent、权限、状态机和评测入口 |
| 异步与协调 | RabbitMQ、Spring AMQP、Redis 7.x、Redisson | 异步任务、发布确认、消费确认、死信、去重、锁、限流和短期状态 |
| 业务与对象存储 | MySQL 8、MinIO | 业务真源、执行轨迹、Checkpoint、视频与派生产物 |
| 检索 | Qdrant、BGE-M3 Embedding | 视频片段向量索引与语义召回 |
| 视频与 AI | FFmpeg、Tesseract、LangChain4j、SiliconFlow 托管的 DeepSeek、TeleSpeechASR | 媒体处理、OCR、云端推理、ASR 与结构化输出；MVP 固定供应商 |
| 本地部署 | Docker Compose | MySQL、Redis、RabbitMQ、MinIO、Qdrant 编排 |

“Java 单体”表示一个 Spring Boot 部署单元承载 API、任务消费者、业务编排和 Agent Runtime；MySQL、Redis、RabbitMQ、MinIO、Qdrant 仍是独立基础设施。代码内部必须按领域模块隔离，不能形成一个由 Controller 直接调用几十个 Service 的大泥球。

RabbitMQ 的选择和可靠投递语义记录在 [ADR-0001：使用 RabbitMQ 承载异步任务](adr/0001-use-rabbitmq-for-analysis-jobs.md)。

## 3. 产品范围与成功定义

### 3.1 MVP 用户故事

1. 用户上传一条 30 分钟至 3 小时的视频，提交后可离开页面。
2. 用户回来后能看到真实进度、已完成阶段、失败原因和是否可重试。
3. 系统生成带时间范围的 ASR、OCR、关键帧、章节和检索片段。
4. 用户提出问题后得到结构化答案，每条事实结论可点击跳到原视频证据。
5. 视频中没有足够证据时，系统明确拒答或说明缺失信息。
6. 页面刷新、SSE 重连、后端重启、MQ 重投不会丢任务或重复执行昂贵阶段。
7. 同一视频可用不同目标反复分析，视频固有解析产物可以安全复用。

### 3.2 V1 暂缓范围

- 多资料知识库、知识图谱、团队空间和多租户；
- 自动剪辑、视频生成、社交平台发布；
- 本地 GPU 推理和独立 Python 推理服务；
- 在线视频链接解析与站点适配；
- 多云端模型供应商动态路由和自动故障切换；
- 面向公网的注册、开放访问与互联网安全运维；
- 完整的题库、掌握度、间隔复习等学习管理功能；
- 多 Agent 通信或无限自主循环；
- 自动微调生产模型；
- 根据未经核验的用户隐式行为直接修改 Prompt、检索权重或答案。

### 3.3 MVP 总体验收线

| 维度 | 最低验收线 |
| --- | --- |
| 产品闭环 | 60 分钟冻结测试视频可完成上传、解析、检索、问答和证据跳转 |
| 任务可靠性 | 故障注入测试中不丢任务；同一幂等请求不产生重复高成本调用 |
| 恢复能力 | Worker/应用在关键阶段被终止后，至少 95% 的可恢复任务自动续跑成功 |
| 引用有效性 | 至少 95% 的 `CitedEvidence` 能在声明时间窗的 ASR/OCR 原文中确定性定位 |
| 检索 | 冻结检索集 `Recall@3 >= 85%`，并同时报告 `Hit@1`、`MRR@3` |
| 回答 | 事实性 Claim 的严格证据支持率不低于 85%；不可回答题单独报告拒答正确率 |
| 可观测性 | 每个 Job、模型调用和 Tool Call 可通过 `requestId/jobId/runId` 串联 |
| 成本 | 每条任务记录 Token、调用次数、延迟和估算成本；预算超限能确定终止 |

这些是项目的首个发布门槛，不是对未来生产效果的承诺。数据集未达到规模或未人工复核时，只报告工程基线，不对外宣称质量提升。

## 4. 总体架构

```mermaid
flowchart LR
    U[用户] --> W[Vue 3 工作台]
    W -->|REST / SSE| A[Spring Boot 模块化单体]
    A --> M[(MySQL 8 真源)]
    A --> R[(Redis / Redisson)]
    A --> Q[RabbitMQ]
    Q --> C[单体内 Analysis Consumer]
    C --> O[(MinIO)]
    C --> F[FFmpeg / Tesseract]
    C --> X[云端 ASR / LLM / Embedding API]
    C --> V[(Qdrant)]
    C --> M
```

建议的 Java 包边界如下。第一阶段仍使用一个 Maven 模块和一个可执行 JAR，先靠包边界、接口和 ArchUnit/约定守住模块方向；只有真实部署压力出现后再拆服务。

```text
com.videopilot
├── identity/        # 用户、鉴权、资源归属
├── media/           # 上传、MinIO、媒体探测、删除
├── analysis/        # AnalysisJob、状态机、调度、恢复、事件
├── context/         # ASR、OCR、关键帧、VideoContext
├── retrieval/       # VideoChunk、Embedding、Qdrant、混合排序
├── answer/          # 问答、Claim、Citation、拒答、验证
├── agent/           # AgentRun、ToolRegistry、策略与预算
├── feedback/        # 用户反馈、复核、失败样本和候选版本
├── evaluation/      # 数据集、Runner、指标、对比与报告
└── infrastructure/  # MQ、Redis、MySQL、MinIO、模型 Adapter
```

模块依赖遵循 `controller -> application -> domain -> port`，基础设施通过 Adapter 实现 Port。业务代码不能直接散落调用模型 SDK、Redis Key 或 Qdrant HTTP 接口。

### 4.1 前端产品架构与交付原则

前端不是所有后端完成后的装饰层，而是每个用户可见薄切片的可执行验收面。默认交付顺序为“冻结合同 -> 后端规则与真实边界 -> 最小可操作页面 -> 自动化测试与真实浏览器联调证据”。状态机、Outbox、锁和 Checkpoint 等内部可靠性切片仍以单元/集成/故障测试为主，并在紧随其后的用户操作面展示其真实结果；不得为演示增加可绕过 application service 的调试接口，也不得用本地计时或假数据伪造服务端状态。

Vue 工作台按能力纵向组织：`api` 保存版本化合同、传输与统一错误；`features` 按 session、media、jobs、context、answer、evaluation、agent、feedback 分区；共享组件只承载无业务含义的视觉原语。认证使用 HttpOnly Cookie，CSRF 值只保存在页面内存；服务端状态是唯一业务事实，前端不复制状态机。只有跨多个已交付 feature 的客户端状态出现后才引入全局状态库，避免提前形成第二真源。

视觉采用克制的“教学档案工作台”语言：暖纸色背景、墨色正文、深绿主操作与少量橙色状态强调；中文衬线标题配合清晰的无衬线正文和等宽元数据。布局强调时间轴、证据和任务状态的阅读层级，少用圆角卡片与装饰性阴影，不以大面积渐变代替信息结构。所有页面必须覆盖 320px 手机、768px 平板和 1024px 以上桌面宽度，键盘可操作、焦点可见、语义标签完整、正文和控件达到 WCAG AA 对比度，并提供真实的 loading、empty、error、retryable、disabled 和 reconnect 状态。

每个用户可见薄切片的完成证据至少包括：类型/Lint/组件测试、生产构建、真实浏览器桌面与手机视口检查、Console 无错误警告、关键网络请求符合冻结合同、键盘主路径可用。浏览器验收补充 Maven 测试而不替代后端单元、集成和故障注入证据。

## 5. 核心领域模型

### 5.1 身份分层

参考项目以 `mediaId + goal + mode` 充当逻辑任务身份。VideoPilot 增加独立执行身份，以解决重跑、审计、版本比较和 Agent 轨迹归属问题：

- `analysisKey = mediaFingerprint + normalizedGoal + mode + pipelineVersion`：逻辑去重与结果复用。
- `jobId`：一次可恢复的后台任务，永不复用。
- `runId`：Job 内某次 Agent 执行，重试或实验产生新 Run。
- `idempotencyKey`：客户端重复提交保护。
- `requestId`：一次 HTTP/MQ/模型调用链的追踪标识。

`analysisKey` 相同不代表永远复用旧结果。模型版本、Prompt 版本、检索版本或管线版本改变时，必须能够创建新 `jobId/runId` 并与旧版本对比。

### 5.2 关键实体

| 实体 | 关键内容 | 真源 |
| --- | --- | --- |
| `MediaAsset` | 所有者、SHA-256、MinIO key、时长、媒体状态 | MySQL |
| `AnalysisJob` | jobId、analysisKey、状态、阶段、attempt、version、错误分类 | MySQL |
| `JobStageAttempt` | 阶段输入指纹、开始/结束、重试、产物引用 | MySQL |
| `JobEvent` | 单调 sequence、阶段、消息、payload、时间 | MySQL Outbox/事件表 |
| `VideoContext` | 与目标无关的时序多模态上下文 | MySQL + MinIO 大对象 |
| `VideoSegment` | start/end、ASR、OCR、关键帧引用 | MySQL |
| `VideoChunk` | 摘要、关键词、Embedding 引用、原始 Segment | MySQL + Qdrant |
| `EvidenceHit` | 检索命中、通道、分数和时间窗 | Answer/Run trace |
| `AnswerClaim` | 可单独核验的一条事实主张 | MySQL |
| `CitedEvidence` | Claim 到原文片段与时间戳的绑定 | MySQL |
| `ModelCall` | 操作、模型、版本、Token、成本、延迟和错误 | MySQL |
| `AgentRun/AgentStep` | 目标、Observation、动作、终止原因和预算 | MySQL |
| `ToolCall` | 工具名、参数、幂等键、结果引用和耗时 | MySQL |
| `UserFeedback` | 明确反馈、原因、纠正内容、关联版本 | MySQL |
| `EvaluationRun` | 数据集版本、候选版本、逐题结果与聚合指标 | MySQL/文件产物 |

Redis 只保存 active key、限流令牌、锁、短期缓存和 SSE 热事件。Redis 内容全部丢失后，系统仍能从 MySQL 重建任务事实和恢复决策。具体数据准入、一致性、故障语义、参考项目对比和 Key/TTL 规范见 [Redis 使用边界与演进设计](REDIS_ARCHITECTURE.md)。

### 5.3 Redis 能力演进

Redis 是可丢失的加速与协调层，不是业务状态数据库。新增能力必须同时定义权威存储、TTL/容量、版本与 owner 隔离、不可用降级和 Redis 清空验收；锁、限流、缓存命中或 Pub/Sub 通知均无权绕过 application service、数据库 CAS、权限和预算硬门。

| 里程碑 | 计划使用 | 保持在权威存储中的事实 | 退出证据 |
| --- | --- | --- | --- |
| M1 | 登录失败计数、active Job 缓存、AnalysisJob 短租约锁 | MySQL 会话/上传/Job/事件/Outbox | 已完成 Redis 清空、重启、owner 隔离和 MQ 重投验收 |
| M2 | 沿用 Job 锁；仅在真实并发证据出现时增加 Context 同指纹构建短租约 | MySQL 工作项、分片 Checkpoint、Context/Segment/通道状态；MinIO 派生物 | Redis 清空后已完成 ASR/OCR 调用增量为 0，数据库 CAS 仍单赢家 |
| M3 | 按性能证据增加 owner/version-scoped 摘要或查询缓存 | MySQL/MinIO/Qdrant/Answer Trace | 缓存启停结果等价；不缓存完整转录、Embedding 或答案真源 |
| M4 | Checkpoint 元数据热缓存、SSE Pub/Sub 热唤醒、供应商/用户/全局限流、运维热指标 | MySQL Checkpoint、JobEvent sequence、预算、ModelCall 和 Outbox | Redis flush/中断下可恢复，SSE 从 MySQL 连续补拉，高成本预算不重置 |
| M5 | 确定性评测产物和报告投影缓存 | EvaluationRun、逐题 Trace 和版本化报告 | dev/test 与全部版本指纹进入 Key，最终指标不依赖 TTL 数据 |
| M6 | Agent 实时轨迹投影、Tool/模型流量整形、短租约协调 | AgentRun/Step、ToolCall、ModelCall、预算和终止原因 | Redis 清空后仍可完整回放，越权与超预算仍由确定性硬门拒绝 |
| M7-M8 | 复核队列/候选对比只读投影与发布通知 | 反馈、复核、评测、晋级和回滚历史 | 缓存丢失不改变裁决，不触发自动晋级、回滚或在线自改 |

引入顺序遵循“先正确、再测量、后缓存”：M2 不提前建设通用 Checkpoint 缓存；M4 根据真实读取频率、延迟与数据库压力决定是否落地，并用禁用 Redis 加速路径的等价性测试证明缓存透明。

## 6. 可靠任务闭环

### 6.1 主状态机

```mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> QUEUED
    QUEUED --> CONSUMING
    CONSUMING --> PROBING
    PROBING --> CONTEXT_BUILDING
    CONTEXT_BUILDING --> INDEXING
    INDEXING --> READY
    READY --> ANSWERING
    ANSWERING --> VERIFYING
    VERIFYING --> COMPLETED
    VERIFYING --> COMPLETED_WITH_WARNINGS

    PROBING --> RETRY_WAIT
    CONTEXT_BUILDING --> RETRY_WAIT
    INDEXING --> RETRY_WAIT
    ANSWERING --> RETRY_WAIT
    RETRY_WAIT --> QUEUED

    CREATED --> CANCELED
    QUEUED --> CANCELED
    CONSUMING --> CANCEL_REQUESTED
    PROBING --> CANCEL_REQUESTED
    CONTEXT_BUILDING --> CANCEL_REQUESTED
    INDEXING --> CANCEL_REQUESTED
    ANSWERING --> CANCEL_REQUESTED
    CANCEL_REQUESTED --> CANCELED

    CONSUMING --> FAILED
    PROBING --> FAILED
    CONTEXT_BUILDING --> FAILED
    INDEXING --> FAILED
    ANSWERING --> FAILED
    FAILED --> DEAD_LETTERED
    DEAD_LETTERED --> MANUAL_REPLAY
    MANUAL_REPLAY --> QUEUED
```

状态迁移只能经过 `JobTransitionService`。每次迁移必须在同一数据库事务中完成以下动作：校验允许的前置状态和 `version`、更新 Job、写 Checkpoint/阶段产物引用、写 Outbox 事件。Controller、MQ Consumer 和定时任务都不能直接更新状态字段。

### 6.2 幂等、Checkpoint 与恢复

| 层次 | 机制 |
| --- | --- |
| 提交 | `idempotencyKey` + `analysisKey` 唯一约束；Redis active key 只做快速拦截 |
| 投递 | 数据库事务写 Job 与 Outbox；Relay 通过 Spring AMQP 投递 RabbitMQ；Publisher Confirm 成功后标记 Outbox 已发布；消息只携带 `jobId` 等定位字段 |
| 消费 | Redisson 锁 + 数据库状态/version CAS；重复消息成为 no-op |
| 阶段 | `jobId + stage + inputFingerprint` 唯一；完成产物后才推进状态 |
| 模型调用 | 先写 `ModelCall`；只对明确可重试错误有限重试；超时结果标记 `UNKNOWN` |
| 恢复 | MySQL Checkpoint 为真源；根据已完成阶段和输入指纹跳过昂贵步骤 |
| Replay | 保留与视频固有的 Context/Chunk，清除目标相关的 Plan/Answer/Critic 后新建 Run |

必须用故障注入证明这些语义：在上传合并、ASR、OCR、Embedding、Agent 首轮、结果落库前分别终止进程；恢复后检查任务数量、模型调用次数、最终产物和状态迁移是否正确。

### 6.3 外部依赖降级

| 故障 | 系统行为 |
| --- | --- |
| OCR/Tesseract 失败 | 标记视觉通道降级，保留 ASR 链路并继续 |
| ASR 永久失败 | 任务失败；不以视觉少量文本伪装完整解析 |
| Qdrant 不可用 | 使用已有 Embedding 的内存排序 + 关键词/OCR 检索 |
| Embedding 不可用 | 有限重试后退化关键词/OCR；结果标记检索降级 |
| 模式路由 LLM 失败 | 回退 `GENERAL`，不阻断主任务 |
| Critic 不可用 | 返回未验证结果或失败取决于发布策略，绝不能显示为已验证 |
| SSE 断线 | Job 继续；客户端用最后 sequence 补拉 MySQL 事件后重连 |
| RabbitMQ 重投或未确认消息重新入队 | Consumer 手动 ACK，并以数据库状态/version CAS 保证幂等，不重复烧模型费用 |

## 7. 视频理解与证据链

```text
MediaAsset
  -> FFmpeg 音频分片 + 场景关键帧
  -> TranscriptSegment + OCR/FrameObservation
  -> 时间对齐 VideoSegment（建议 60 秒）
  -> VideoChunk（建议 5 分钟索引单元）
  -> Qdrant 向量 + 关键词/OCR 混合检索
  -> EvidenceHit
  -> AnswerClaim
  -> CitedEvidence
  -> VerificationResult
```

初始检索公式沿用参考实现的方向并配置化：

```text
score = 0.60 * semantic + 0.25 * keyword + 0.15 * visual
```

权重、TopK、Query Rewrite Prompt、Embedding 模型和切片策略都必须有版本号并写入 Trace。任何调参只能使用 dev 集，test 集在版本冻结后运行一次。

回答验证包含三层：

1. **引用存在性**：片段属于当前视频，时间窗合法，引用内容能在 ASR/OCR 原文中定位。
2. **Claim 绑定**：每条事实 Claim 至少绑定一条 `CitedEvidence`，且引用指向对应 Claim。
3. **语义支持**：Critic 判断证据是否足以推出 Claim；确定性规则发现失败时，LLM 无权覆盖。

可以复用参考项目的 Citation Alignment 思路修复标点、换述或 ASR 口误造成的引用对齐问题，但搜索范围必须限制在声明时间点及相邻 Segment。全片找一句相似文本再修改时间戳，会掩盖错引。

## 8. 真正的 Agent Loop

固定执行 `Planner -> Executor -> Critic` 是有价值的 LLM 工作流，但还不足以证明 Agent 会根据环境变化决策。VideoPilot 的 Agent 必须满足以下条件：

- 下一步动作由 Goal、历史 Observation 和预算共同决定；
- 工具结果会改变下一步，例如补检、扩窗、澄清、拒答或结束；
- 每步状态持久化，服务重启后可以从最后一个安全边界恢复；
- 所有工具经过 Java Tool Registry，模型不能直接访问数据库、网络或文件；
- 有确定的步数、时间、Token、费用和修复次数上限；
- 每次结束都有明确 `terminationReason`，可离线回放。

### 8.1 初始工具集合

```text
search_transcript(query, timeRange?, topK)
search_visual_text(query, timeRange?, topK)
expand_time_window(startMs, endMs, marginMs)
get_segments(segmentIds)
draft_answer(question, evidenceIds)
verify_claims(answerDraft)
ask_user_clarification(question)
finish(reason)
```

每个 Tool 声明 JSON Schema、读写性质、超时、幂等策略、所需权限和成本等级。第一阶段工具全部只作用于当前用户和当前视频；有外部副作用的工具不进入 V2。

### 8.2 循环状态机

```mermaid
flowchart TD
    S[加载 AgentRun 与 Observation] --> B{预算是否充足}
    B -- 否 --> X[BUDGET_EXHAUSTED]
    B -- 是 --> D[策略选择下一动作]
    D --> P{Policy / Schema 校验}
    P -- 失败 --> R[记录错误观察并允许一次修复]
    R --> B
    P -- 通过 --> T[执行 ToolCall]
    T --> O[持久化 Observation]
    O --> V{已有可验证答案?}
    V -- 否 --> B
    V -- 是 --> G[确定性证据门控]
    G -- 缺证据且可补检 --> B
    G -- 缺证据且不可补检 --> N[REFUSED / WITH_WARNINGS]
    G -- 通过 --> C[COMPLETED]
```

建议初始预算：最多 6 个 Tool Step、最多 2 次答案生成/修复、单 Run 全局 deadline、Token 上限和人民币估算成本上限。具体值通过评测标定，不能只靠配置把循环放大为无限重试。

### 8.3 Agent 晋级条件

Agent 必须与“单次检索 + 单次结构化回答”的非 Agent 基线做同题对比。满足下列任一条件才默认启用：

- 答案硬门通过率提高至少 8 个百分点，且 P95 延迟和平均成本增幅均不超过 30%；
- 在硬门通过率下降不超过 2 个百分点的情况下，P95 延迟或平均成本降低至少 20%；
- 对预先定义的复杂题子集有显著收益，且系统只对该子集路由 Agent。

否则保留为实验能力或仅在复杂问题中按规则启用。

## 9. 用户反馈驱动的 Agent 改进闭环

首版“学习反馈”指系统从用户反馈中学习如何改进 Agent，而不是先建设面向学生的题库、掌握度和间隔复习产品。练习与个性化学习可在 V2 闭环稳定后作为独立版本规划。

```text
显式反馈/失败样本
  -> 去敏与去重
  -> 人工复核或规则裁决
  -> 加入 dev/challenge 数据集
  -> 形成 Prompt/检索/策略候选版本
  -> 离线回放与成本评测
  -> 达到门槛后灰度
  -> 线上监测
  -> 晋级或回滚
```

### 9.1 反馈信号分级

| 信号 | 例子 | 能否直接成为质量标签 |
| --- | --- | --- |
| 强显式 | 用户指出错误 Claim、提供纠正答案、标记错引 | 复核后可以 |
| 弱显式 | 点赞/点踩、选择“没回答问题” | 只能生成待复核样本 |
| 隐式 | 点击引用、跳转播放、重新提问、停止观看 | 只能用于产品分析，不能视为事实真值 |
| 系统 | 引用校验失败、预算耗尽、Tool Schema 错误 | 可直接成为工程质量标签 |

系统不在生产请求中在线修改 Prompt 或权重。所有“学习”都通过版本化候选、冻结评测和晋级门完成，确保结果可解释、可回滚。

### 9.2 反馈对象

每条 `UserFeedback` 至少关联 `answerId`、`runId`、Agent/Prompt/检索版本、反馈类型和创建时间。支持以下首版反馈：

- 整体回答有用或无用；
- 指定 Claim 错误、缺失或偏离问题；
- 指定 Citation 错引、证据不足或时间点不准确；
- 用户给出纠正文本或期望回答；
- 系统自动记录引用校验失败、错误拒答、预算耗尽和无效 Tool Call。

原始反馈是不可直接信任的输入。进入评测集前必须经过去敏、去重和复核，并保留“接受、驳回、待确认”的裁决状态及理由。

### 9.3 候选版本晋级

一次 Agent 改进必须表示为可追踪的候选版本，例如 `promptVersion`、`retrievalVersion`、`agentPolicyVersion` 或确定性规则版本。候选版本只能使用 dev/challenge 数据调试；通过回归硬门后才能在本地或单服务器环境手动启用。任何晋级都保留上一稳定版本，以便立即回滚。

## 10. 评测体系

评测不是最后补脚本，而是每个里程碑的交付物。目录建议：

```text
benchmark/
├── datasets/
│   ├── retrieval/
│   ├── answer/
│   ├── agent/
│   └── feedback/
├── runners/             # 调用生产 Java 代码的评测入口
├── reports/             # 可提交的聚合结果和逐题摘要
├── local/               # 原视频、完整 ASR/OCR、Embedding，不入 Git
└── README.md             # 口径、数据版本、复现命令和限制
```

### 10.1 分层指标

| 层级 | 核心指标 |
| --- | --- |
| 媒体解析 | ASR/OCR 成功率、时间戳覆盖、空片段率、处理实时倍率、单小时视频成本 |
| 检索 | Recall@K、Hit@1、MRR、视觉题/模糊题/精确题分组结果 |
| 回答 | 正确性、完整性、严格 Claim-Evidence 支持率、引用位置、拒答准确率 |
| Agent | 任务成功率、首轮/补检恢复率、无效 Tool 比例、平均步数、终止原因分布 |
| 反馈闭环 | 有效反馈率、复核一致率、失败类型覆盖率、候选修复率、回归失败率 |
| 可靠性 | 丢任务率、重复执行率、恢复成功率、非法状态迁移、DLQ/Replay 成功率 |
| 性能成本 | P50/P95 延迟、Token、模型调用数、每任务成本、线程池/连接池饱和度 |

### 10.2 数据集建设计划

当前没有可直接使用的评测数据，因此评测语料建设与产品实现并行推进：

1. M0 确定教学视频选择标准、版权/使用边界、标注 Schema 和文件组织。
2. M1-M2 选取第一条 45-90 分钟教学视频，随解析管线产出完整 ASR/OCR 和时间轴。
3. M3 前完成人工种子集，用于验证检索和证据问答；未完成种子集时不得用主观 Demo 代替质量验收。
4. M5 扩展并冻结发布集，建立 dev/test 隔离和逐题复核记录。

- 起步集：至少 3 条不同主题的教学视频，每条 45-90 分钟，合计 60 道检索题和 30 道答案题。
- 发布集：至少 5 条教学视频、120 道检索题；精确、模糊、视觉/OCR 约为 40%/40%/20%。
- 答案集必须同时包含可回答和不可回答题，并标注必需事实、禁止断言、相关时间窗和证据通道。
- dev/test 按视频或主题隔离；调 Prompt、权重和阈值只看 dev，冻结后再运行 test。
- 自动生成的问题必须人工核对后才能进入正式集合。
- LLM-as-Judge 只作为辅助；硬门由确定性校验和抽样人工复核共同确认。
- 每次报告保存数据集、模型、Prompt、检索公式、代码提交和配置指纹。

## 11. 核心里程碑

时间是单人全职开发的粗略估算。实际推进以“退出门槛”作为完成标准，不以自然周结束为完成标准。

### M0：工程骨架与契约冻结（3-5 天）

交付：

- 初始化 Java 21/Spring Boot、Vue 3、Docker Compose 和 CI；
- 建立上述领域包边界、统一错误结构和配置分层；
- 建立 Flyway、日志 Trace、秘密配置和本地一键启动；
- 写出 Job 身份、状态机、Checkpoint、Outbox 和错误分类 ADR；
- 建立 `benchmark/` 目录、教学视频筛选规范、标注 Schema 和数据版本规范；
- 建立供应商配置清单，MVP 固定 LLM、ASR、Embedding 和 OCR 方案，同时保留 Adapter 边界。
- 前端建立设计 Token、响应式应用外壳、统一 loading/empty/error 状态和 API 合同测试基线，不展示未实现能力为可用。

退出门槛：空业务环境能一键启动；Health 能区分应用存活与五项基础设施健康；CI 完成编译、单测和迁移校验；任何密钥均未进入仓库；评测标注模板可以容纳问题、相关时间窗、证据通道、必需事实和禁止断言；前端外壳通过 `npm run check` 并在桌面/手机视口无 Console 错误。

### M1：上传与异步 Job 最小闭环（1-2 周）

交付：

- 登录/资源归属、MinIO 上传、媒体元数据和 FFprobe 校验；
- `AnalysisJob`、JobEvent、统一状态迁移、Outbox -> RabbitMQ -> Consumer；
- SSE 进度、刷新后补拉、取消和失败展示；
- Redis active key、数据库幂等约束和 Redisson 消费锁。
- 前端随薄片交付登录、上传、owner-scoped 媒体资源列表/详情与校验状态、READY 媒体选择、Job 提交/列表/详情、SSE 时间线、取消和安全失败提示；内部可靠性能力只展示服务端真实事件。

退出门槛：重复提交只创建一个逻辑执行；MQ 消息重复投递不重复推进；重启应用后媒体与 Job 仍能从 MySQL 找回；其他用户不能枚举或读取视频/Job；用户可在浏览器完成“登录 -> 上传 -> 资源列表观察校验 -> 选择 READY 媒体提交 -> 查看进度 -> 刷新恢复 -> 取消/失败”验收主路径。

完成记录（2026-11-04）：项目负责人在审阅 `tasks/M1/ACCEPTANCE.md` 第 24 节记录的全部成功、失败与未执行项后确认 M1 退出。最终自动化基线为后端 205 项单元/Controller/架构测试、37 项真实依赖集成测试和前端 54 项测试；真实浏览器主路径、双用户隔离、应用重启、Redis 清空与 MQ 重投均有验收证据。本地 OWASP 因 NVD 下载失败未形成本次结论，Gitleaks 因扫描器下载失败未执行；负责人接受该退出遗留风险，但安全脚本、CVSS 7 阈值和 CI Gitleaks 门保持启用，不视为漏洞已修复或扫描已通过。

### M2：时序多模态 VideoContext（1-2 周）

交付：

- FFmpeg 音频切片、场景关键帧和保底采样；
- TeleSpeechASR、Tesseract/OCR Adapter、独立有界线程池；
- `TranscriptSegment -> VideoSegment -> VideoContext` 对齐与持久化；
- ASR/OCR 超时、有限重试、降级、子进程清理和阶段 Checkpoint。
- 前端提供视频播放器与统一时间轴，分层显示 ASR、OCR、关键帧、阶段进度和降级状态；点击任一片段可定位原视频，恢复后不伪造已完成阶段。

退出门槛：首条选定的 45-90 分钟教学视频可生成完整时间轴；OCR 失败时 ASR 结果仍可用；ASR 阶段中断重启后不重做已完成切片；每个外部调用有成本和错误记录；浏览器可检查时间对齐、通道降级和刷新恢复。

完成记录（2026-11-05）：项目负责人审阅 `tasks/M2/ACCEPTANCE.md` 的长视频、故障矩阵、调用审计、完整质量门和浏览器证据后确认 M2 工程验收。45 分钟权利明确的合成教学样本形成 45 个连续 Segment，覆盖完整时长；真实 TeleSpeechASR/Tesseract 调用、ASR completed 零重复、OCR 全通道降级、MQ 重投、Redis 丢失/不可用、owner 隔离和播放器刷新/定位均有证据。该样本只建立工程基线，不代表真实课程质量；来源不明的本机 Bilibili 视频未进入正式 benchmark，M3 种子集仍须加入权利明确的真实教学材料后才能报告检索或内容质量。

### M3：混合检索与有证据问答（1-2 周）

交付：

- 5 分钟 VideoChunk、摘要/关键词、BGE-M3 Embedding 和 Qdrant 索引；
- 语义 + 关键词 + OCR 混合排序和降级检索；
- 结构化 `AnswerClaim/CitedEvidence`、时间戳跳转、拒答；
- 确定性引用验证和受限 Citation Alignment。
- 前端形成“视频/证据 + 问答”双工作区，答案按 Claim 展示引用，时间戳一键跳转；拒答、降级检索和无证据状态具有同等清晰度。

退出门槛：端到端产品闭环可演示；每条事实 Claim 有可点击证据；伪造引用被硬门拦截；Qdrant 不可用时任务可降级完成；完成至少 1 条教学视频的人工种子标注并报告检索基线。`Recall@3 >= 80%` 作为进入扩样阶段的目标，不在样本不足时包装成正式结论；核心问答、引用跳转和拒答路径有真实浏览器证据。

### M4：任务可靠性发布门（1-2 周）

交付：

- 完整 Checkpoint/Recovery、Retry/DLQ/Manual Replay；
- 全链路 deadline、Token/成本预算、限流和资源池隔离；
- 故障注入测试、并发重复提交测试和 SSE 重连测试；
- 运维仪表：积压、失败分类、阶段耗时、供应商错误和成本。
- 前端在同一工作台提供恢复、重试/DLQ/Manual Replay 的受控操作与只读运维视图，危险操作明确确认并显示审计结果。

退出门槛：故障注入中无任务丢失、无重复高成本阶段；恢复成功率达到 95%；所有非法状态迁移被拒绝；可重试与不可重试错误具有稳定语义；浏览器可区分自动恢复、需人工操作和不可恢复状态。至此形成 V1 MVP。

退出记录（2026-11-08）：工程候选完成 20/20 冻结恢复样本、完整 Docker 集成门、前端门和真实浏览器矩阵，任务丢失/重复高成本调用/非法推进均为 0。项目负责人明确接受 OWASP Dependency-Check 因 NVD 不可用、Gitleaks 因本机工具不可用而未形成通过结论等已记录遗留风险，并批准 M4/V1 MVP 正式退出；未通过的扫描仍继续跟踪，不因批准而改写为通过。项目可按依赖顺序开始 M5。

### M5：建设数据集、冻结基线与评测流水线（1-2 周）

交付：

- 从零扩展至发布规模的检索、答案、拒答和可靠性数据集；
- Java 生产代码离线 Runner、逐题 Trace 和可复现报告；
- 非 Agent 基线：一次混合检索 + 一次结构化回答 + 确定性验证；
- Prompt、模型、Embedding、公式、阈值和代码版本固化。
- 前端提供数据集版本、评测运行、逐题 Trace、指标/成本对比和报告导出视图；dev/test 隔离和未复核状态必须可见。

退出门槛：同一配置可重复生成一致的确定性指标；随机性指标报告均值/方差；test 集未参与调参；人工抽检完成；浏览器中的报告能追溯到数据集和全部版本指纹。没有这一门槛，不进入 Agent 效果宣称。

冻结记录（2026-11-10）：最新候选 `5e71014` 已完成 `0.2.0` DEV 三轮。三轮 `Recall@3=100%`，严格 Claim-Evidence 支持率分别为 `28.57% / 46.67% / 54.55%`，引用定位率分别为 `82.35% / 77.78% / 84.62%`；后两项仍低于 `85% / 95%` 硬门。分阶段入口正确阻止 TEST 重跑，M5 未退出。项目负责人随后明确要求冻结该已知效果缺口并优先进入 M6；该流程例外登记为 `EX-2026-11-10-M6`，只允许把 M6 作为本地实验能力建设，不把 M5 改写为通过，不默认启用 Agent，不基于历史 TEST 调参或宣称 Agent 效果。

### M6：动态工具型 Agent Loop（2 周）

进入状态（2026-11-10）：按负责人明确决策，以受限实验方式开始；约束、任务顺序和验收见 `tasks/M6/PLAN.md`、`tasks/M6/TODO.md` 与 ADR-0015。M5 的失败指标继续作为未解决基线，M6 的实现或局部改善均不能替代 M5/M6 退出门。

交付：

- `AgentRun/AgentStep/ToolCall/Observation` 持久化；
- Tool Registry、Schema、Policy、幂等、权限和成本等级；
- 基于 Observation 的动作选择、补检/扩窗/澄清/拒答/结束；
- 全局预算、最多步骤、答案修复上限、恢复和轨迹回放；
- Agent 与 M5 非 Agent 基线的同题 A/B。
- 前端以可展开轨迹展示 Agent Step、Tool Call、Observation、预算与终止原因，默认突出答案和证据，不把内部思考文本当作产品输出。

退出门槛：至少存在三类因工具结果不同而产生的不同执行路径；重启能从安全 Step 恢复；越权 Tool 和超预算调用被拒绝；达到第 8.3 节至少一条晋级条件；浏览器可回放不同路径并清楚显示预算/权限拒绝。

受限推进记录（2026-11-12）：T4 正确策略 DEV 三轮均完成 90/90，检索指标稳定，但 Claim 严格支撑率
均值 36.69%、引用定位率均值 74.74%，仍低于 85%/95%；首轮另有一条 BGE-M3 HTTP 400，使成本完整性
为 false。T4 与 M6 保持未退出、不可晋级。项目负责人要求记录上述错误并优先补齐“READY 后自动目标
分析结果”产品闭环，登记 `EX-2026-11-12-M6-T5`。该例外仅改变 T4 -> T5 的实施顺序；Agent、Worker、
前端入口和目标分析继续默认关闭，不豁免 T5 自身测试、安全、预算、权限和证据硬门，也不允许宣称 M6
效果达标。

闭环补齐决策（2026-11-13）：负责人确认冻结上述 T4 效果缺口，并按退出门顺序优先实施 T6-T10。
`EX-2026-11-13-M6-T6-T10` 只允许继续默认关闭的实验实现，不把 T4/M6 改写为通过。ADR-0017 冻结
`PLAN -> RETRIEVE -> GENERATE_AND_VERIFY -> CRITIQUE -> 一次补检/扩窗 -> 再生成` 的最短闭环；
Planner/Critic 只能提出白名单候选动作，Java 继续控制预算、权限、状态和 Evidence Gate。每个任务必须
独立测试、文档和提交，上一退出门不通过时停止。

T10 阻断记录（2026-11-13）：隔离库授权后，Linux 容器中的 `verify -Pintegration` 以 478 项单元测试、
96 项集成测试和真实检索基线通过；浏览器随后发现相同 Job/问题的新 Run 会复用 Planner 模型调用指纹，
以及 Planner/Critic Prompt 对数组上限和空时间窗表达不明确。实现保留数据库唯一约束与严格 Schema，
改为按 Run 生成指纹，并前进到 `agent-planner-v3/agent-critic-v2` 明确模板。修复后的同题三轮为两轮
`COMPLETED`、一轮 `ANSWER/INVALID_RESPONSE`，因此 T10/M6 仍未退出；不得用第四轮替换失败轮，剩余
浏览器四路径、正式 Runner 与后修复完整门停止，所有 Agent 开关继续默认关闭。

T10A 诊断门（2026-11-13）：保留上述失败事实，不补跑覆盖。Answer Adapter 将 HTTP 200 后原本合并的
`INVALID_RESPONSE` 拆为 `EMPTY_CONTENT`、`MISSING_USAGE`、`TOKEN_USAGE_LIMIT` 和
`MALFORMED_JSON`，并只把 finish reason、content 长度、usage 字段存在性和 JSON 可解析性写入既有
`model_call.error_summary`；不保存模型原文。483 项后端快速测试和隔离 MySQL 持久化测试通过后，
T10A 可独立退出，下一步只能以固定 Job/问题/版本/预算运行新的 T10B series；原 `2/3` series 不变。

T10B 稳定性门（2026-11-13）：后续失败分别定位为 reasoning token 被误算为可见输出、Critic 合同字段
约束不完整、一次供应商 Critic 超时、长期测试 Job 的调用预算耗尽，以及 READY Index 复用时没有返回
数据库实际 Index ID。修复没有提高预算、清零计数或保存模型原文；Critic 前进为 `agent-critic-v4`。
使用正常 API 创建并恢复的新 READY Job 固定同题三轮，终态为 `REFUSED/COMPLETED/COMPLETED`，共
11 次生成式调用全部成功、未知成本为 0；其中两轮形成 6 Step/4 LLM 的真实补检路径。490 项快速测试、
JaCoCo 80% 和 ArchUnit 通过后，T10B 可独立退出并进入 T10C。原失败 Run 与 M5/T4 缺口继续保留。

T10C 两轮证明门（2026-11-13）：真实样本只有两个 Chunk，原首轮 Top-5 会提前选中全部 8 个 Segment，
无法证明修复新增证据。Agent 现以 Top-1 执行首次检索，只有 Critic 明确补检时扩大到 Top-5；没有改变
混合排序、Embedding、预算或 Evidence Gate。自动测试分别跑通补检与扩窗的精确 6 Step/4 LLM，并证明
第二次 Answer 收到原证据和新增证据。真实浏览器跨 Chunk 问题生成 Run
`9c49c666-584e-4d1e-bd7f-b3fa4473658c`：Critic 把下一 query 改为 Redis help 三种用法，新增后半段
3 个 Segment，最终在第二次 Evidence Gate 后稳定拒答；刷新、引用跳转、桌面/320px 和 Console 验收
通过。492 项快速测试及隔离 MySQL 6 项持久化测试通过后，T10C 可独立退出并进入 T10D；默认开关和
M5/M6 效果门不变。

T10D 完整工程门（2026-11-14）：Critic 前进为语义覆盖判定的 `agent-critic-v5-window`，策略冻结为
`agent-policy-v4-window`；正常、补检、扩窗和稳定拒答均在真实浏览器完成，刷新后可从 MySQL 回放，
桌面与 320px 无横向溢出。三轮不可变 DEV Runner 各完成 90/90，合计 252 个 `COMPLETED`、18 个
`REFUSED`，Tool 264/264 完成，生成式模型调用均值 3.44/题；首答一次通过 55.56%，进入修复的 24 题中
6 题恢复完成、18 题稳定拒答。候选三轮 Claim 支持率均值 23.132%（样本标准差 5.526%），引用定位率
均值 69.841%（标准差 1.375%），仍低于 M5 85%/95%；Java 裁决 `m5GatesMet=false`、
`promotionEligible=false`。492 项单元/架构测试、97 项集成测试、126 项前端测试、覆盖率、npm 高危门、
Gitleaks 干净快照与 Compose 配置通过；负责人明确批准跳过 OWASP Dependency-Check，NVD 数据未取得，
故该项未形成漏洞结论。T10 工程闭环完成，但 M6 继续未退出、不晋级，所有 Agent 默认开关保持 false。

T11 有界部分回答（2026-11-14）：为避免第二轮仍隐藏已通过硬证据门的教学内容，新增
`PARTIALLY_ANSWERED` 和第二次 Critic。修复路径上限前进为 7 Step/5 次生成式调用，但仍只有 2 次 Answer、
1 次工具修复；第二次 Critic 只能 `PASS/PARTIAL/REFUSE`。Answer 明确禁止使用通用知识补齐视频缺口，
所有发布 Claim 继续由原 Evidence Gate 逐条验证，缺口单独持久化并在页面以“视频证据不足以确认”展示。
版本冻结为 `agent-policy-v5-partial / agent-critic-v6-partial / answer-evidence-v3-partial`。该体验修正不改写
T10D 历史指标，不降低 M5/M6 效果门，所有 Agent 开关继续默认关闭。

### M7：用户反馈采集与复核闭环（1-2 周）

交付：

- 回答、Claim 和 Citation 三级显式反馈；
- 错误类型、纠正文本、运行版本和 Trace 自动关联；
- 反馈去敏、去重、复核队列、裁决状态和审计记录；
- 将通过复核的失败样本加入 dev/challenge 数据集。
- 前端在答案、Claim、Citation 就地收集反馈，并提供去敏确认、复核队列、裁决历史和 challenge set 去向；反馈提交不暗示已自动生效。

退出门槛：一条真实错误回答能完成“用户反馈 -> 关联 Run/版本 -> 复核 -> 进入 challenge set”的完整浏览器演示；未经复核的反馈不会直接改变 Agent 行为。

### M8：Agent 候选版本评测与晋级（1-2 周）

交付：

- 从 challenge set 聚类失败类型并选择可解释的修复目标；
- 注册 Prompt、检索、Agent Policy 或确定性规则候选版本；
- 对候选版本执行离线回放、硬门回归和成本比较；
- 在本地/单服务器环境手动启用候选版本并支持快速回滚。
- 前端提供候选版本差异、硬门回归、指标/延迟/成本对比、人工晋级确认和回滚入口，历史版本始终只读可追溯。

退出门槛：能从一条经过复核的真实失败样本生成候选改动，跑完整评测，展示指标与成本差异，并在浏览器中于不修改历史记录的前提下晋级或回滚版本。至此形成 V2 闭环。

## 12. 里程碑依赖与推荐发布点

```mermaid
flowchart LR
    M0[M0 骨架] --> M1[M1 上传与 Job]
    M1 --> M2[M2 VideoContext]
    M2 --> M3[M3 检索与问答]
    M3 --> M4[M4 可靠性门]
    M4 --> V1[V1 可发布 MVP]
    M3 --> M5[M5 冻结评测基线]
    M4 --> M5
    M5 --> M6[M6 Agent Loop]
    M6 --> M7[M7 用户反馈闭环]
    M7 --> M8[M8 候选版本晋级]
    M8 --> V2
```

推荐设置持续浏览器验收和三个跨里程碑演示检查点。每个用户可见薄切片完成时即更新对应页面并留下浏览器证据，不等到里程碑末尾集中联调：

1. **M2 后**：上传视频，展示可恢复的 ASR/OCR 时间轴。
2. **M4 后**：展示带引用问答、拒答、服务重启恢复和 MQ 重投幂等。
3. **M8 后**：展示 Agent 动态补检，以及一条用户反馈如何经过复核、候选修复、离线评测和版本回滚形成闭环。

## 13. 开发纪律与决策原则

- 每个里程碑先写验收场景和最小失败复现，再实现业务代码。
- 每个用户可见薄切片同步交付最小前端操作面和真实浏览器验收；内部可靠性薄片在下一操作面展示真实结果，不增加绕过领域规则的演示接口。
- 修改状态机、任务身份、Agent 预算、证据门或反馈晋级规则时必须新增 ADR。
- 数据库迁移只前进，不依赖手工改表；Checkpoint payload 必须带 schemaVersion。
- 云端模型调用统一经过 Adapter；业务层不出现供应商专属响应类型。
- Prompt、检索公式、Embedding 模型和 Agent Policy 都是版本化生产资产。
- 可观测 Trace 不能存 API Key、完整密码或不必要的用户隐私。
- 优先复用视频固有产物，禁止因不同问题重复执行 ASR/OCR。
- 单元测试保护纯规则，集成测试保护数据库/MQ/对象存储边界，故障注入保护恢复语义；不要用大量 mock 测试替代真实边界验证。
- 组件/API 测试保护前端状态与合同，真实浏览器保护布局、可访问性、Console 和网络联调；两者不得替代后端边界测试。

## 14. 主要风险与控制措施

| 风险 | 控制措施 |
| --- | --- |
| 一开始照搬参考项目全部复杂度 | 按 M0-M8 垂直切片建设，每阶段只引入当前验收所需能力 |
| Java 单体内部耦合失控 | 领域包边界、Port/Adapter、禁止跨模块直接访问 Mapper |
| MQ/Redis 与 MySQL 状态不一致 | MySQL 真源、事务 Outbox、版本 CAS、Redis 可重建 |
| 模型超时导致重复费用 | ModelCall 状态、调用指纹、有限重试、UNKNOWN 语义和预算 |
| Agent 只是多次 Prompt 包装 | 要求动态工具选择、Observation 改变路径、轨迹回放和基线 A/B |
| 用户反馈污染系统 | 强弱信号分级、人工/规则复核、离线晋级、禁止在线自改 |
| LLM Judge 自我偏好 | 确定性硬门、独立模型复核、人工抽样并报告限制 |
| 指标被短视频或数据泄漏抬高 | 使用长视频、按视频拆分 dev/test、冻结 test、报告分组指标 |
| OCR/ASR 单点失败 | 独立线程池、分片 Checkpoint、单分支降级和错误分类 |
| 成本随 Agent 循环失控 | 步数/时间/Token/金额四重预算和复杂问题条件路由 |

## 15. 下一步启动顺序

后续真正开始构建时，严格按以下顺序进入 M0：

1. 在已更名的 `E:\VideoPilot` 工作区初始化 Maven/Vue 工程和 Compose；不复制参考项目的业务源码。
2. 先提交 4 份 ADR：任务身份、状态机、Outbox/Checkpoint、云端模型调用与预算。
3. 建立最小 MySQL 表：`media_asset`、`analysis_job`、`job_stage_attempt`、`job_event_outbox`、`model_call`。
4. 实现一条不调用模型的假任务链，验证提交、MQ、状态迁移、SSE、重启和幂等。
5. 假任务可靠后再接入 MinIO/FFmpeg 和云端 ASR；不要把任务可靠性问题与模型效果问题同时调试。

这份规划是后续里程碑拆解的上层基线。每开始一个里程碑，应另建该阶段的实施清单，列出数据库迁移、API、领域接口、测试、评测数据和退出证据；完成后把实际指标回填到本文件，而不是只把任务标记为“完成”。
