# M3 实施计划：混合检索与有证据问答

## 目标与当前基线

M3 在已退出的 M2 `VideoContext` 时间轴上完成首个可演示 MVP：把 `VideoSegment` 聚合为约 5 分钟的 `VideoChunk`，用 BGE-M3 与 Qdrant 建立语义索引，结合关键词/OCR 召回形成 `EvidenceHit`，再由 SiliconFlow 托管的 DeepSeek 生成候选 `AnswerClaim`，经 Java 确定性证据门后发布 `CitedEvidence` 或明确拒答。前端在现有播放器/时间轴旁增加问答与证据区，点击引用直接定位原视频。

当前 `retrieval` 和 `answer` 模块只有 `EmbeddingPort`、`LanguageModelPort` 两个预留边界，尚无 M3 业务实现。M2 已证明 owner-scoped Context 查询、统一 Job 状态迁移、Checkpoint、模型调用审计和播放器 seek；M3 应复用这些事实，不再创建第二套 Context、任务、状态机、媒体读取或 Trace 框架。

## 最小 MVP 决策

- 只增加真实外部边界所需的两个 Adapter：BGE-M3 Embedding 和 Qdrant；SiliconFlow 托管的 DeepSeek 实现既有 `LanguageModelPort`。不建设 Provider Registry、通用 RAG Pipeline、Retriever 接口族或可插拔重排器。
- 一个 `RetrievalApplicationService` 直接完成三路候选生成、归一化和固定公式融合；一个 `AnswerApplicationService` 直接完成检索、候选生成、确定性验证和持久化。Controller 仍只调用 application service。
- `VideoChunk` 固定按约 5 分钟窗口从已发布 `VideoSegment` 确定性聚合。MVP 的摘要采用有界抽取式摘要，关键词采用确定性词频/归一化提取；不为摘要额外调用 LLM，也不创建摘要工作流。
- 索引属于视频固有产物，指纹包含 Context 版本、Chunk 策略、Embedding 模型和索引 schema 版本；同指纹复用，版本变化新建索引版本，不静默覆盖。
- MySQL 保存 Chunk、索引状态、Embedding 引用/必要的降级向量和答案证据真源；Qdrant 只保存可重建向量索引。Qdrant 丢失后可从 MySQL 重建，不能成为唯一完成事实。
- Qdrant 正常时使用 Qdrant 语义召回；Qdrant 不可用时用已有 Embedding 做有界内存余弦排序，并继续关键词/OCR；Embedding 不可用时只做关键词/OCR并显式标记降级。
- 初始融合公式固定为 `0.60 * semantic + 0.25 * keyword + 0.15 * visual`。只实现各通道分数归一化、去重与 TopK，不提前引入 RRF、Cross Encoder、Query Rewrite、动态权重或多轮补检。
- 问答是一次有界请求，不创建 Conversation、AgentRun、ToolCall 或 Agent Loop。SiliconFlow 托管的 DeepSeek 只返回结构化候选；Java 校验 owner、Chunk/Segment 归属、时间窗、原文定位和 Claim 引用完备性，模型不能越过硬门。
- Citation Alignment 只允许在声明 Segment 及相邻 Segment 内修复标点、空白和 ASR 轻微差异；禁止全片搜索后改写时间戳。候选无法通过时发布明确拒答，不伪造引用。
- 问答 API 先采用普通 REST 请求/响应并设置有界超时；M3 不为流式 token 新增 SSE 协议。刷新后从 MySQL 读取已发布问答记录。
- 只建设 1 条权利明确真实教学视频的人工种子集和工程基线；M5 才扩展、冻结 dev/test 发布集。样本不足时不得宣称达到正式产品指标。

## 依赖图

```text
T0 验收合同、真实教学视频与人工种子集
  -> T1 VideoChunk、索引真源与真实 INDEXING 状态
      -> T2 BGE-M3 + Qdrant 可重建索引和降级
          -> T3 混合检索、EvidenceHit 与基线 Runner
              -> T4 SiliconFlow DeepSeek 结构化问答、引用硬门与拒答
                  -> T5 视频/证据 + 问答双工作区
                      -> T6 端到端验收、文档与 M3 退出
```

该顺序刻意优先打通一条完整后端路径。T5 只在 T4 合同稳定后开始，避免前后端并行发明两套 Claim/Citation 结构。

## 任务表

| ID | 任务 | 高相关内容合并理由 | 依赖 | 规模 |
| --- | --- | --- | --- | --- |
| T0 | 冻结 M3 验收合同与人工种子集 | 真实视频、问题、证据窗、拒答题和指标口径共同决定 Chunk、检索与回答合同，必须先冻结 | 无 | M |
| T1 | 建立最小 VideoChunk 与索引真源 | Chunk 规则、schema、指纹、Checkpoint 和 `INDEXING -> READY` 是同一个持久化状态边界 | T0 | M |
| T2 | 接通 BGE-M3、Qdrant 与可重建降级索引 | Embedding 审计、向量写入、重建和 Qdrant 故障回退共同证明索引不依赖单点 | T1 | M |
| T3 | 交付三路混合检索与基线 Runner | semantic/keyword/OCR 的生成、融合、`EvidenceHit` 和 Recall 指标必须使用同一生产排序代码 | T2 | M |
| T4 | 交付结构化证据问答与确定性硬门 | SiliconFlow DeepSeek 候选、Claim/Citation 持久化、受限对齐和拒答是一条不可拆开的发布边界 | T3 | M |
| T5 | 交付视频/证据 + 问答双工作区 | 问答状态、Claim 展示、引用 seek、降级和拒答共同构成一个用户可见纵切 | T4 | M |
| T6 | 完成真实端到端验收与 M3 退出 | 检索基线、伪造引用、Qdrant 故障、浏览器证据、运行文档和质量门都基于同一候选版本 | T5 | M |

详细验收标准、验证命令和预计改动区域见 `tasks/M3/TODO.md`。

## 检查点

### Checkpoint A：可重建索引（T0-T2）

- 人工种子集的每题都有可核对时间窗、证据通道和可回答性标签，原视频及完整转录不进入 Git。
- M3 Job 真实经过 `CONTEXT_BUILDING -> INDEXING -> READY`；索引完成事实、版本和输入指纹来自 MySQL，不由 Qdrant 或 Redis 单独决定。
- 同指纹重投不重复调用 BGE-M3；Qdrant collection 删除或不可用后能重建或降级，不能把缺失索引显示为完整成功。

### Checkpoint B：可验证后端闭环（T3-T4）

- 生产检索代码和 benchmark Runner 使用同一融合公式与版本，报告 `Recall@3`、`Hit@1`、`MRR@3` 及分组结果。
- 每条发布的事实 Claim 至少有一个能在当前视频声明时间窗原文中定位的 `CitedEvidence`；跨视频、越界或伪造引用被 Java 硬门拒绝。
- 无证据、Embedding/Qdrant 降级和模型候选无效均产生稳定、可区分的结果，不由 LLM 宣称自己已验证。

### Checkpoint C：用户闭环与退出（T5-T6）

- 用户可围绕一条 READY 视频提问，按 Claim 阅读引用，点击时间戳定位视频；刷新后仍能读取已发布结果。
- 拒答、检索降级、无证据、请求失败与正常答案具有同等清晰的前端状态；移动端、键盘、Console 和网络行为有真实浏览器证据。
- 至少一条真实教学视频完成人工种子标注并生成可复现基线；`Recall@3 >= 80%` 只作为扩样目标，样本不足时仅报告工程结果。

## 明确不做

- 不实现 Query Rewrite、Cross Encoder、RRF、多供应商路由、动态权重、知识库、多视频联合检索或缓存体系。
- 不实现流式 token、Conversation/Chat Session、AgentRun、ToolRegistry、Critic Agent、多轮自动补检或问题规划。
- 不创建通用 `BaseRepository`、通用向量库接口族、检索 DSL、工作流引擎、事件溯源系统或每张表一套 CRUD service。
- 不建设 M4 的完整 Recovery/DLQ/Manual Replay UI，也不把 M3 局部故障验收包装成 M4 可靠性发布门。
- 不在 M3 扩成 M5 的 3 条/5 条视频正式数据集，不因小种子集偶然达标宣称产品检索质量已达发布线。

## 主要风险与控制

| 风险 | 影响 | 控制 |
| --- | --- | --- |
| M2 退出使用合成长样本，真实教学质量尚未证明 | 检索/回答 Demo 失真 | T0 首先补权利明确真实教学视频与人工证据窗，未完成不得退出 M3 |
| 为“混合检索”过早抽象多个策略层 | 文件和胶水代码快速膨胀 | 一个 application service + 一个 Store Port + 两个真实外部 Adapter，权重先固定版本化 |
| Qdrant 成为隐性真源 | collection 丢失后无法恢复 | MySQL 保存 Chunk、索引状态和重建所需事实；加入删除/不可用验收 |
| Embedding 调用成功但提交前退出 | 可能重复费用或索引不一致 | 调用前审计、输入指纹、UNKNOWN 语义；完成事务后才推进索引阶段 |
| 关键词/OCR 与向量分数量纲不同 | 融合排名不可解释 | 每通道独立归一化、保存 raw/normalized/weighted score 和公式版本 |
| LLM 输出合法 JSON 但引用伪造 | 发布不可核验答案 | Java 只接受检索候选中的证据 ID，并重新核对原文、时间窗、owner 和 Claim 绑定 |
| Alignment 在全片寻找相似句 | 错引被掩盖 | 仅声明 Segment 与相邻 Segment，修复规则和失败路径写纯规则测试 |
| 同步问答超时影响体验 | 请求失败或重复调用 | 有界超时、客户端 idempotency key、模型调用审计；M3 不用流式协议掩盖可靠性问题 |

## T0 需要冻结的参数

- 真实教学视频的授权、SHA-256、时长、语言、主题和本地路径；视频及完整转录只放 `benchmark/local/`。
- 种子集最小题量与分组。建议起步为不少于 12 道检索题和 6 道回答题，其中至少 2 道 OCR/视觉题、2 道模糊题和 2 道不可回答题；若材料不支持，应记录实际数量和局限，不凑数。
- Chunk 窗口/边界规则、摘要长度、关键词数量、Embedding batch/维度/距离度量、Qdrant collection schema 与版本。
- 三路归一化、TopK、候选上限、最低证据阈值、Alignment 邻域和答案最大 Claim 数；全部进入 retrieval/prompt/index version 或输入指纹。
- SiliconFlow DeepSeek 结构化输出 schema、超时、最大 Token、明确可重试错误、idempotency 能力与成本字段。
