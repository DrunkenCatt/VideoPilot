# M2 实施计划：时序多模态 VideoContext

## 目标与当前基线

M2 在已退出的 M1 可靠上传与异步 Job 闭环上，把一条已校验视频转换为可恢复、可查询、可在浏览器核对的时序多模态 `VideoContext`。最小 MVP 只覆盖 FFmpeg 音频切片/关键帧、TeleSpeechASR、Tesseract OCR、时间对齐、持久化、分片恢复和统一时间轴；不提前实现 M3 的 `VideoChunk`、Embedding、Qdrant、检索、问答或章节摘要。

M1 当前的 `AnalysisJobExecutionHandler` 会用确定性假处理依次经过 `PROBING -> CONTEXT_BUILDING -> INDEXING -> READY`。M2 必须移除这段假推进：context-only 管线完成后直接从 `CONTEXT_BUILDING` 进入 `READY`，不能为了沿用旧路径而伪造 `INDEXING` 已完成。该状态边变化先通过 ADR 固化；M3 使用新的 pipeline version 再启用真实 `INDEXING`。

## 最小实现原则

- 沿用现有 `SpeechRecognitionPort` 和 `OcrPort`，分别只实现一个 TeleSpeechASR Adapter 和一个 Tesseract Adapter；M2 不做供应商注册表、动态路由、fallback provider 或通用模型框架。
- application 层只增加一个清晰的 VideoContext 编排入口和一个聚合持久化 Port。不要为音频、帧、转录、OCR、Segment 各建一套 repository/service/facade。
- 复用 `job_stage_attempt` 的输入指纹、状态和 Checkpoint 语义；仅为必须查询和恢复的媒体派生物/分片结果增加最小表，不另造通用 DAG、工作流 DSL 或事件编排器。
- 音频片和关键帧是视频固有产物，以 `media fingerprint + extraction version + time window` 形成指纹，可跨目标复用；Job 只引用这些产物，消息体不携带视频或完整转录。
- ASR 是成功硬门；OCR 是可降级通道。OCR 单帧或整通道失败必须持久化降级事实并继续生成 ASR 时间轴，不能把降级伪装成完整成功。
- 分片级提交遵循“产物和完成 Checkpoint 原子落库后才算完成”。恢复时只扫描未完成或明确可重试的分片；已完成且指纹一致的分片不再调用供应商。
- Redis 继续只承载 M1 的 active Job 加速和有限租约锁；M2 的 Context、工作项、Checkpoint、通道状态和调用审计全部以 MySQL 为真源。没有真实并发测量前不增加 Context 构建锁，也不提前建设 Checkpoint 缓存或 Pub/Sub。
- 原视频和关键帧通过 owner 校验后的短期 MinIO GET URL 提供给浏览器，利用对象存储 Range 请求播放；不让 Spring Boot 代理整段长视频，也不暴露对象 key 或长期凭据。
- 前端只呈现 MySQL/SSE/API 的真实阶段、通道和 Segment 事实；不根据本地计时推断进度，不在刷新后把未完成分片画成已完成。

## 依赖图

```text
T0 契约、验收样本与状态语义
  -> T1 最小持久化与恢复底座
      -> T2 FFmpeg 派生物与分片清单
          -> T3 分片 ASR、调用审计与断点恢复
          -> T4 关键帧 OCR、调用审计与通道降级
              -> T5 时间对齐、VideoContext 完成与查询 API
                  -> T6 播放器与统一时间轴
                      -> T7 真实视频、恢复和降级验收
                          -> T8 文档、全量质量门与 M2 退出
```

T3 与 T4 可在 T2 的分片/指纹合同冻结后并行，但二者都不得各自发明重试、错误或审计模型。

## 任务表

| ID | 任务 | 关联内容合并理由 | 依赖 | 规模 |
| --- | --- | --- | --- | --- |
| T0 | 冻结 M2 验收合同、真实样本与状态语义 | API、状态边、版本和验收样本共同决定后续所有指纹与恢复行为，应一次冻结 | 无 | M |
| T1 | 建立最小 VideoContext 持久化与分片 Checkpoint 底座 | schema、CAS、完成原子性和查询投影是同一真源边界，避免多套 Repository | T0 | M |
| T2 | 生成可复用音频切片与关键帧 | FFmpeg 命令、保底采样、MinIO 产物、清理和分片清单属于一个媒体派生纵切 | T1 | M |
| T3 | 完成 TeleSpeechASR 分片执行与恢复 | Adapter、有限重试、`model_call` 审计和已完成分片跳过共同证明“不重复高成本调用” | T2 | M |
| T4 | 完成 Tesseract OCR 与视觉通道降级 | OCR Adapter、独立资源池、逐帧审计和降级收敛必须一起验证 | T2 | M |
| T5 | 对齐并发布可查询 VideoContext | 对齐规则、`VideoSegment` 持久化、完成事务、owner 查询 API 是一条可用后端纵切 | T3、T4 | M |
| T6 | 交付播放器与统一时间轴 | 播放授权、Segment/帧读取、点击 seek、刷新恢复共同构成最小用户验收面 | T5 | M |
| T7 | 执行真实视频、恢复与降级验收矩阵 | 45-90 分钟样本、ASR 中断、OCR 故障、重启和浏览器事实需要统一核对 | T6 | M |
| T8 | 更新运行文档并完成 M2 质量门 | 配置、实测命令、风险和退出记录必须基于同一最终提交 | T7 | S |

详细验收标准、验证命令和预计改动区域见 `tasks/M2/TODO.md`。

## 检查点

### Checkpoint A：真源与派生物（T0-T2）

- context-only Job 不再伪造 `INDEXING`，状态边与 pipeline version 已由 ADR/测试固定。
- 数据库能够区分派生分片、调用尝试、最终 `VideoSegment` 和通道降级；Checkpoint payload 带 schema version。
- 同一媒体和提取版本重复执行只得到一份指纹一致的音频/关键帧清单；FFmpeg 超时、取消和失败不遗留子进程或临时文件。

### Checkpoint B：真实多模态上下文（T3-T5）

- TeleSpeechASR 已完成分片在应用重启或 MQ 重投后不会再次调用；每次调用都有状态、版本、耗时、计量、成本或成本不可得事实。
- Tesseract 使用独立有界资源池；OCR 故障产生持久化降级事实但不删除或阻断 ASR 结果。
- `VideoSegment` 时间窗单调、合法且覆盖完整媒体时长；API 只能读取当前 owner 的 Context，不返回对象 key、供应商原始响应或内部路径。
- Redis 清空、重启或暂时不可用后，已完成 ASR/OCR 分片的供应商调用增量为 0；锁丢失时仍由数据库指纹、唯一约束和 version CAS 保持单赢家。

### Checkpoint C：用户闭环与退出（T6-T8）

- 浏览器可播放原视频，点击 ASR/OCR/关键帧 Segment 可定位对应时间，刷新后从服务端恢复一致时间轴。
- 45-90 分钟冻结样本形成完整时间轴；OCR 强制失败时 ASR 仍可检查；ASR 阶段中断重启后已完成分片调用数不增加。
- 后端、集成、前端、安全和真实浏览器检查均记录实际结果与未执行项，负责人确认后才退出 M2。

## 明确不做

- 不创建 `VideoChunk`，不生成摘要/关键词，不接 BGE-M3、Qdrant 或混合检索。
- 不实现问答、Claim/Citation、章节、Agent Loop、Manual Replay UI 或多供应商切换。
- 不建设通用任务 DAG、通用重试框架、通用 artifact service、每实体一套 CRUD service 或独立微服务。
- 不承诺 M4 的完整 Recovery/DLQ/Manual Replay；M2 只证明本里程碑要求的分片 Checkpoint、有限重试和重启续跑。

## 主要风险与控制

| 风险 | 影响 | 控制 |
| --- | --- | --- |
| TeleSpeechASR 实际协议、配额或凭据尚未在仓库验证 | 核心硬门可能晚期阻塞 | T0 首先完成凭据外置、最小探针和响应 fixture；探针失败不得用假 Adapter 冒充 M2 完成 |
| M1 假管线继续写入 `INDEXING` | 页面和恢复事实失真 | T0 新 ADR + 状态机失败测试；T1/T5 替换假 handler 路径 |
| 45-90 分钟视频产生大量帧/OCR 调用 | 工期、CPU 和存储失控 | 场景帧去重 + 固定保底采样上限；配置有界并发、输出大小和每分钟最大帧数 |
| ASR 返回时间戳相对分片而非全片 | Segment 错位、点击跳转错误 | Adapter 边界统一转为全片毫秒；对分片偏移、边界裁剪和单调性写规则测试 |
| 进程在供应商成功后、结果提交前退出 | 调用结果未知并可能重复计费 | 先写 `model_call`，稳定幂等键/输入指纹；陈旧 STARTED 标记 UNKNOWN，不把未知结果当作肯定未执行 |
| OCR 文本重复或关键帧爆炸 | 时间轴不可读 | 相邻帧按内容摘要去重；最终 Segment 只保留有意义的 OCR/帧引用，原始中间物不直接铺满 UI |
| Spring Boot 代理长视频 | Undertow 线程、带宽和内存压力 | owner 校验后签发短期 GET URL，浏览器直接向 MinIO 发 Range 请求 |
| 任务切得过细导致层级膨胀 | 大量胶水代码、难以追踪事务 | 每个任务交付一个纵向结果；一个编排入口、一个聚合 Store Port，Adapter 只隔离真实外部边界 |

## 待 T0 实测后冻结的参数

- 首条 45-90 分钟教学视频的来源权利、SHA-256、时长和本地保存位置（只进 `benchmark/local/`，不提交 Git）。
- 音频分片长度/重叠、TeleSpeechASR 单请求限制、明确可重试状态和计费字段。
- 场景检测阈值、保底采样间隔、每分钟最大关键帧数、Tesseract 语言包与 OCR 最低置信度。
- `VideoSegment` 初始窗口（Roadmap 建议 60 秒）及边界合并规则；参数全部纳入 extraction/alignment version 和输入指纹。
