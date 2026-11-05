# M3 任务清单：混合检索与有证据问答

> 状态：待负责人确认后开始。任务按依赖顺序执行，每项先写失败测试或可执行验收场景，再写生产代码。目标是最小可演示 RAG 闭环，并控制在一个检索 application service、一个回答 application service 和必要的外部 Adapter 内。

## T0：冻结 M3 验收合同与人工种子集

- [x] 在 `tasks/M3/ACCEPTANCE.md` 写出 Chunk/索引、混合检索、Embedding/Qdrant 降级、结构化回答、伪造引用、拒答、owner 隔离、刷新恢复和引用 seek 的 Given/When/Then 场景。
- [x] 选定至少 1 条权利明确的真实教学视频，在 `benchmark/local/` 记录授权、SHA-256、时长、语言和主题；原视频、完整转录、OCR、Embedding 与模型原始响应不得提交。
- [x] 建立可提交的人工种子标注 schema，逐题记录 query、可回答性、相关时间窗、证据通道、必需事实和禁止断言；M3 复用 M0 的 retrieval/answer schema，不创建第二套格式。建议不少于 12 道检索题、6 道回答题，并包含 OCR/模糊/不可回答样本。
- [x] 冻结 `VideoChunk`、检索、问答历史和错误 REST 合同及 TypeScript 合同；不得返回 ownerId、对象 key、向量、完整 Prompt、模型原始响应或内部路径。
- [x] 用外置凭据执行 BGE-M3 与 SiliconFlow DeepSeek 最小探针，确认 endpoint、模型名、Embedding 维度/最小批量、结构化输出、超时、计量、成本不可得、错误分类和应用侧幂等边界；供应商最大 batch/限流阈值留给 T2 有界 Adapter 验证。
- [x] 新增 ADR-0007，冻结 MVP LLM 为 SiliconFlow 托管的 `deepseek-ai/DeepSeek-V4-Flash`；其余状态机、模型审计和 context-only 路径继续沿用 ADR-0003/0005/0006。
- **验收标准**：M3 全部退出门映射到可执行场景；种子题可由人工在原视频定位；前后端合同、供应商合同和所有版本字段明确；真实探针未完成时不以 fake 宣称能力完成。
- **验证**：合同测试先 RED；人工对照 Roadmap 5/6/7/10/11/13 节与 ADR-0002/0003/0005/0006；`git diff --check -- docs tasks server client benchmark`。
- **预计改动区域**：`tasks/M3`、`benchmark` schema/小型脱敏 fixture、后端/前端合同测试，必要时 `docs/adr`。
- **依赖/规模**：无 / M。

## T1：建立最小 VideoChunk、索引真源与真实 INDEXING 状态

- [x] 用一份前进 Flyway 迁移建立最小 `video_chunk` 与索引版本/状态字段；Chunk 保存 Context/Segment 归属、时间窗、抽取式摘要、关键词、ASR/OCR 检索文本和输入指纹，不保存向量正文到业务响应。
- [x] 在 `retrieval` 模块只定义必要的 `VideoChunk`、一个聚合 Store Port 和一个索引 application 编排入口；不按 Chunk/Keyword/Index 拆多套 repository/service。
- [x] 以约 5 分钟确定性窗口聚合连续 `VideoSegment`，裁剪到媒体时长并保留 Segment ID；摘要使用有界原文抽取，关键词使用确定性归一化词频，空 OCR 不阻止 ASR Chunk。
- [x] 固定指纹：Context/version + Chunk strategy/version + normalized source digest + embedding/index schema version；同指纹为 no-op，版本变化生成新版本，旧版本保留到新版本成功发布。
- [x] 让 M3 pipeline 在 Context 完成后通过统一 `JobTransitionService` 进入真实 `INDEXING`，索引成功后才进入 `READY`；M2 context-only pipeline 继续遵循 ADR-0006，不回归伪 INDEXING。
- **验收标准**：Chunk 时间窗合法、单调并覆盖 Context；重复执行不重复创建；失败不发布半成品索引；状态事件与 MySQL 事实一致；跨 owner 不可读取 Chunk。
- **验证**：Chunk 纯规则测试、MySQL 唯一约束/CAS/发布事务集成测试、M2/M3 状态机回归测试；`server\mvnw.cmd test` 和相关 `verify -Pintegration`。
- **预计改动区域**：`retrieval` domain/application/port、retrieval persistence Adapter、Flyway migration、analysis pipeline 接线及测试。
- **依赖/规模**：T0 / M。

### Checkpoint A1：Chunk 与索引真源

- [x] 真实 Context 可重复生成同一组 5 分钟 Chunk，版本变化不静默复用。
- [x] M3 Job 真实记录 INDEXING，M2 context-only Job 仍可直接 READY。
- [x] 未引入通用 RAG 框架、工作流 DSL、第二套 Job 状态机或每表 CRUD 层。

## T2：接通 BGE-M3、Qdrant 与可重建降级索引

- [ ] 实现现有 `EmbeddingPort` 的 BGE-M3 Adapter，限制 batch、输入字符/Token、响应大小、向量维度、超时和并发；供应商类型在 Adapter 内完成校验与错误归一化。
- [ ] 调用前写 `model_call`，完成后记录 provider/model/embedding version、状态、输入 Token、耗时、成本或不可得事实；UNKNOWN 与明确可重试错误沿用 ADR-0005，不在线程内无界重试。
- [ ] 实现一个窄 Qdrant Adapter 完成版本化 collection 建立、批量 upsert、owner/media/context payload 过滤和查询；不建立通用 VectorStore/Repository 层级。
- [ ] 只有 Embedding 结果、索引发布事实和 Checkpoint 原子收敛后才完成 INDEXING；同指纹 MQ 重投/重启不重复调用 BGE-M3，Qdrant upsert 使用稳定 point ID 保持幂等。
- [ ] 保存重建与有界内存余弦排序所需的向量事实或受控对象引用；Qdrant collection 删除后可从 MySQL 真源重建，不重新调用已有同指纹 Embedding。
- [ ] Qdrant 不可用时标记 `VECTOR_STORE_DEGRADED` 并允许已有向量内存排序；Embedding 不可用且重试耗尽时标记 `SEMANTIC_DEGRADED`，为 T3 保留关键词/OCR 路径。
- **验收标准**：正常索引可查询；重复/乱序执行 BGE 调用增量为 0；Qdrant 删除可重建；Qdrant 或 Embedding 故障不会伪装成完整语义检索，也不会删除 Chunk。
- **验证**：BGE fixture/边界测试、Qdrant Testcontainer 集成、调用审计/UNKNOWN/重投测试、collection 删除重建和不可用降级测试；`server\mvnw.cmd test` 与相关 `verify -Pintegration`。
- **预计改动区域**：Embedding/Qdrant Adapter 和配置、retrieval application/persistence、model call 审计扩展、集成测试。
- **依赖/规模**：T1 / M。

## T3：交付三路混合检索、EvidenceHit 与基线 Runner

- [ ] 用一个 `RetrievalApplicationService` 完成 owner-scoped 查询、semantic/keyword/OCR 三路候选、每通道分数归一化、Chunk 去重和 `0.60/0.25/0.15` 加权 TopK；公式与阈值有版本并进入 Trace。
- [ ] 关键词和 OCR 先采用 MySQL 上的有界候选检索与 Java 确定性计分，不为 MVP 引入 Elasticsearch、全文检索集群、分词服务或第二套索引存储。
- [ ] 返回项目统一名称 `EvidenceHit`，包含 Chunk/Segment、合法时间窗、证据通道、原始/归一化/加权分数、检索/Embedding/Chunk 版本和降级状态；不得返回向量或未授权文本。
- [ ] Qdrant 不可用时使用 T2 的有界内存语义排序并保留关键词/OCR；Embedding 不可用时只使用关键词/OCR。所有路径使用相同融合与排序代码并显式返回 degraded channels。
- [ ] 提供 owner-scoped 检索 REST API，限制 query 长度、TopK 和候选数量；空 query、无 Context、未完成索引、跨 owner 和无命中使用稳定错误/空结果语义。
- [ ] 在 `benchmark` 提供调用生产检索代码的最小 Runner，读取 T0 种子集并报告总量、`Recall@3`、`Hit@1`、`MRR@3`、精确/模糊/OCR 分组、配置版本和 commit；不复制一份排序算法。
- **验收标准**：同一输入和版本排序稳定；三路分数可解释；Qdrant/Embedding 降级仍能返回关键词/OCR 命中；跨 owner 无泄露；基线报告可复现且不含完整转录。
- **验证**：计分/排序纯规则测试、MySQL/Qdrant/降级集成测试、REST owner 合同测试、种子 Runner；`server\mvnw.cmd test` 和相关 `verify -Pintegration`。
- **预计改动区域**：retrieval application/domain/web、benchmark runner/report、配置与对应测试。
- **依赖/规模**：T2 / M。

### Checkpoint B：可度量混合检索

- [ ] 正常、Qdrant 不可用、Embedding 不可用三种路径均使用同一 `EvidenceHit` 合同并准确标记降级。
- [ ] 种子集生成首份版本化检索报告；只报告工程基线和样本限制，不把小样本达标包装成产品结论。
- [ ] `Recall@3 >= 80%` 未达到时记录逐题失败和后续假设，不通过临时提高 TopK 或改标注让报告通过。

## T4：交付结构化证据问答、受限对齐与确定性硬门

- [ ] 用一份前进迁移建立最小问答记录、`answer_claim` 和 `cited_evidence`；保存 query、状态、拒答原因、版本 Trace 与发布时间，不创建 Conversation/AgentRun/ToolCall 表。
- [ ] 实现 SiliconFlow DeepSeek `LanguageModelPort` Adapter 和有界结构化 JSON schema；Prompt 只提供 T3 的候选证据 ID、时间窗和必要原文，限制 Claim 数、输入/输出 Token、超时、并发和响应大小。
- [ ] `AnswerApplicationService` 在一次纵切中完成检索、调用审计、候选解析、确定性验证和发布；客户端 idempotency key + 输入指纹避免同一请求重复高成本调用，UNKNOWN 不自动冒险重发。
- [ ] Java 硬门逐条验证 owner/media/context/Chunk/Segment 归属、时间窗合法、引用 ID 来自当前候选、引用文本可在 ASR/OCR 原文定位、每条事实 Claim 至少绑定一条证据；LLM 结果不能覆盖失败。
- [ ] Citation Alignment 只在声明 Segment 与前后相邻 Segment 内进行规范化空白/标点和有界近似匹配；记录原候选、对齐结果类型和最终证据位置，禁止全片搜索改时间戳。
- [ ] 无检索命中、证据低于阈值、全部 Claim 硬门失败或不可回答题均发布明确拒答；部分 Claim 失败时采用 T0 冻结策略，不能静默展示无引用事实。
- [ ] 提供 owner-scoped 提问和已发布记录查询 API；响应只包含已通过硬门的 `AnswerClaim/CitedEvidence`、降级状态和稳定拒答/失败原因。
- **验收标准**：每条发布 Claim 有可定位引用；伪造、跨视频、越界、引用错绑和全片“漂移修复”均被拦截；无证据明确拒答；模型/Prompt/Embedding/检索公式版本及 Token/成本/延迟可追踪。
- **验证**：候选解析与证据门纯规则测试、DeepSeek fixture 测试、MySQL 发布事务/idempotency/owner 集成测试、伪造引用与不可回答种子题；`server\mvnw.cmd test` 及相关 `verify -Pintegration`。
- **预计改动区域**：answer domain/application/port、DeepSeek/persistence/web Adapter、Flyway migration、对应测试。
- **依赖/规模**：T3 / M。

## T5：交付“视频/证据 + 问答”双工作区

- [ ] 在现有 Job/Context 工作台内增加问答区和前端 API 合同，不新建全局 store、路由级聊天系统或第二套媒体播放器状态。
- [ ] 提交问题时展示真实 pending/success/refused/failed 状态并防重复提交；刷新或切换 Job 后从后端读取已发布结果，迟到响应不得污染当前视频。
- [ ] 按 `AnswerClaim` 展示答案，每条 Claim 紧邻列出 `CitedEvidence` 的通道、时间戳和短摘录；点击或键盘激活引用复用 M2 播放器 seek，并高亮相应 Segment。
- [ ] 对拒答、无证据、Qdrant/Embedding 降级、OCR 降级、认证过期、超时和后端失败提供明确且互不混淆的状态，不用通用“出错了”掩盖证据事实。
- [ ] 保持桌面双栏、窄屏顺序布局和原生可访问控件；长 Claim/引用不产生横向溢出，焦点顺序和状态提示可被辅助技术读取。
- **验收标准**：用户可完成提问、按 Claim 核对引用、点击跳转、拒答识别和刷新恢复；320/375/768px 可读；键盘路径完整；Console 无 error/warning；前端不接触向量、对象 key、Prompt 或供应商原始响应。
- **验证**：组件/API 失败测试覆盖成功、拒答、降级、迟到响应、seek 和刷新；`client\npm.cmd run check`；真实浏览器检查布局、可访问树、Console、网络和视频跳转。
- **预计改动区域**：client answer/retrieval API contracts、现有工作台/时间轴组件、样式与测试。
- **依赖/规模**：T4 / M。

## T6：完成真实端到端验收、文档与 M3 退出

- [ ] 在 Compose 环境用 T0 真实教学视频跑通上传、Context、Chunk、Embedding、Qdrant、混合检索、问答、引用发布和时间戳跳转，记录阶段耗时、调用数、Token、成本/不可得事实及版本 Trace。
- [ ] 执行故障/对抗矩阵：Qdrant 不可用与 collection 删除、Embedding 不可用、Embedding 完成后重投、DeepSeek 超时/坏 JSON、伪造/跨视频/越界引用、无证据题、重复提问和双 owner 越权。
- [ ] 运行 T0 全部种子题并提交聚合基线报告和逐题脱敏摘要；报告 `Recall@3`、`Hit@1`、`MRR@3`、分组结果、引用定位率、拒答结果、数据量和限制。
- [ ] 真实浏览器验证正常回答、拒答、Qdrant 降级、引用 seek、刷新恢复、窄屏、键盘、可访问树、网络请求与零 Console error/warning，并把证据记录到 `tasks/M3/ACCEPTANCE.md`。
- [ ] 更新 README、Compose/环境示例、供应商目录和 `docs/M3_RUNBOOK.md`，写清 BGE-M3、DeepSeek、Qdrant schema/重建、版本、资源上限、降级和排障；只记录实际验证能力。
- [ ] 记录实际执行命令、耗时、环境、失败/未执行项和对应 commit；复核变更行覆盖率不少于 80%、ArchUnit、秘密/依赖扫描及无 suppression/Floor 违规。
- **验收标准**：Roadmap M3 退出门均有可复现证据；每条事实 Claim 有可点击证据；伪造引用被硬门拦截；Qdrant 不可用仍可降级完成；至少 1 条真实教学视频有人工种子标注与检索报告；负责人确认后才能进入 M4/M5。
- **验证**：`server\mvnw.cmd test`；`server\mvnw.cmd verify -Pintegration`；`client\npm.cmd run check`；`client\npm.cmd audit --audit-level=high`；`docker compose config --quiet`；`powershell -ExecutionPolicy Bypass -File .\scripts\security-check.ps1`；`git diff --check`；Compose + 真实浏览器验收。
- **预计改动区域**：验收/故障测试、benchmark report、README/config/runbook、`tasks/M3/ACCEPTANCE.md` 与本文件。
- **依赖/规模**：T5 / M。

## M3 退出门

- [ ] 一条真实教学视频生成版本化 5 分钟 `VideoChunk`、BGE-M3 Embedding 和可重建 Qdrant 索引。
- [ ] 混合检索在正常、Qdrant 不可用和 Embedding 不可用路径均有明确结果/降级事实，并生成首份人工种子基线。
- [ ] 每条发布的事实 `AnswerClaim` 至少绑定一个可在声明时间窗原文中确定性定位的 `CitedEvidence`。
- [ ] 伪造、越界、跨视频和错绑引用被 Java 硬门拦截；无足够证据时系统明确拒答。
- [ ] 浏览器可完成提问、按 Claim 阅读证据、点击时间戳定位、识别拒答/降级并在刷新后恢复。
- [ ] 本次实际质量门结果和未执行项已记录，项目负责人确认可进入下一里程碑。
