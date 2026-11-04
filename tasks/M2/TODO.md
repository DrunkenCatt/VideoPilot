# M2 任务清单：时序多模态 VideoContext

> 状态：M1 已于 2026-11-04 获负责人确认退出；M2 的 T0-T3 已完成，下一任务为 T4。任务按依赖顺序执行，每项先写失败测试或可执行验收场景，再写生产代码。目标是最小可用 VideoContext，不提前实现 M3。

## T0：冻结 M2 验收合同、真实样本与状态语义

- [x] 在 `tasks/M2/ACCEPTANCE.md` 写出 45-90 分钟完整时间轴、OCR 降级、ASR 中断续跑、MQ 重投、刷新恢复、跨 owner 访问和调用审计的 Given/When/Then 场景。
- [x] 选定一条权利明确的教学视频，只在 `benchmark/local/` 记录 SHA-256、时长、语言、预期视觉内容和授权；补一个可提交的小型合成 fixture 用于自动化测试。
- [x] 冻结 Context/Segment 查询合同、短期播放/关键帧 URL 合同、分页/时间窗和稳定错误语义；合同不得返回 ownerId、MinIO object key、临时路径或供应商原始响应。
- [x] 新增 ADR：M2 context-only pipeline 允许 `CONTEXT_BUILDING -> READY`，M3 pipeline 才经过真实 `INDEXING`；移除 M1 假推进，保持其他状态语义不变。
- [x] 用外置凭据执行 TeleSpeechASR 最小探针，确认认证、请求限制、时间戳单位、错误分类、计量/成本字段和幂等能力；凭据和原始响应不得进入 Git/日志。
- **验收标准**：M2 六项退出门均映射到可执行场景；所有影响指纹的参数都有版本；状态机不再要求伪造 INDEXING；真实供应商未打通时明确阻塞而不是用 fake 宣称完成。
- **验证**：合同/状态机测试先 RED；人工对照 ADR-0001/0002/0003/0005、Roadmap 5-7/11/13 节；`git diff --check -- docs tasks server client benchmark`。
- **预计改动区域**：`tasks/M2`、`docs/adr`、后端/前端合同测试、benchmark 小 fixture。
- **依赖/规模**：无 / M。
- **完成记录（2026-11-04）**：验收场景、REST/TypeScript 合同、ADR-0006、状态机合法边和 SiliconFlow 合同均已冻结。Git 忽略目录中生成了权利明确的 45 分钟合成长样本，并记录 SHA-256、时长、语言和视觉预期；可提交的 3 秒 Context fixture 已由合同测试读取。外置凭据探针确认 200/400、秒单位 duration/usage、无词级时间戳、无价格字段且无公开幂等能力。T0 完成，T1 可开始；合成长样本只证明长时切片/恢复输入，M2 最终退出仍需真实开放许可视频证明实际质量。

## T1：建立最小 VideoContext 持久化与分片 Checkpoint 底座

- [x] 用一份前进 Flyway 迁移建立最小 `VideoContext`、派生分片/帧工作项、`VideoSegment` 和通道状态；复用现有 `job_stage_attempt` 与 `model_call`，不为每种产物创建独立状态体系。
- [x] 在 `context` 模块定义少量领域 record、一个聚合 Store Port 和一个 application 编排入口；MyBatis Adapter 在事务内完成分片 claim/CAS、结果/摘要落库、Checkpoint 完成和最终 Context 发布。
- [x] 固定输入指纹：媒体服务端 SHA-256 + extraction/alignment/provider version + 时间窗/参数；相同已完成指纹成为 no-op，不同版本不得静默复用。
- [x] 定义陈旧 `STARTED`、`FAILED`、`UNKNOWN`、取消和明确可重试的恢复选择；Redis、内存队列和线程池均不得成为完成事实。
- **验收标准**：并发 claim 只有一个成功；结果未提交时不能标 completed；已完成同指纹不可重开；owner-scoped 查询和恢复均以 MySQL 为真源；migration 可从全新 V1 顺序执行。
- **验证**：纯规则单测 + MySQL 事务/CAS/唯一约束集成测试；`server\mvnw.cmd test`；相关 `verify -Pintegration`。
- **预计改动区域**：`context` domain/application/port、context persistence Adapter、Flyway migration、对应测试。
- **依赖/规模**：T0 / M。
- **完成记录（2026-11-04）**：V9 以三张表建立最小 Context 真源；单一 `VideoContextStore` 和 application 入口完成 owner-scoped plan/reuse、CAS claim、产物与 Checkpoint 完成及 Context 原子发布。输入指纹和恢复选择由纯规则测试冻结；真实 MySQL 测试证明并发单赢家、完成不可空提交、同指纹不重开、版本变化不复用、跨 owner 隐藏，以及 Segment 冲突时 Context/Checkpoint 整体回滚。Job/Outbox 最终状态迁移仍按计划留在 T5 的统一入口中。

### Checkpoint A1：契约与真源

- [x] 状态边、Context API、指纹和真实样本已冻结。
- [x] 分片 Checkpoint 与最终 Segment 的 MySQL 原子性、CAS 和 owner 隔离有自动化证据。
- [x] 未引入通用工作流引擎、供应商路由或每表一套 service/repository。

## T2：生成可复用音频切片与关键帧

- [x] 从 MinIO 把 READY 媒体复制到受控临时目录，以受控 FFmpeg 子进程一次派生版本化音频切片和场景关键帧，并按固定间隔补足无场景变化区间。
- [x] 为命令、时长、并发、stdout/stderr 和输出数量设置上限；成功后将派生物写回 MinIO，并把时间窗、摘要、对象引用和输入指纹写入 T1 工作项。
- [x] 相同媒体/提取版本复用已完成清单；失败、超时、取消和进程退出路径强制销毁子进程、关闭流并清理本地临时文件，不发布半成品清单。
- [x] 对场景帧做摘要去重并限制每分钟数量；空场景检测仍由保底采样生成可供 OCR/时间轴使用的帧。
- **验收标准**：合成视频得到覆盖完整时长的音频清单和有界关键帧清单；重复执行不重复派生；超时/损坏输入无泄漏；消息体不含媒体或清单正文。
- **验证**：FFmpeg Adapter 单测 + 真实小视频/MinIO/MySQL 集成测试 + 子进程清理测试；`server\mvnw.cmd test`。
- **预计改动区域**：media/context application、FFmpeg/MinIO Adapter、配置、测试 fixture 与集成测试。
- **依赖/规模**：T1 / M。
- **完成记录（2026-11-04）**：新增一个受限 FFmpeg Adapter，在单进程中生成 16kHz 单声道 WAV 分片与“场景变化 + 固定间隔”关键帧，并对命令参数、超时、日志、并发、文件数、总字节数和每分钟帧数设硬门。派生编排复用 T1 指纹/CAS 工作项，从 MinIO 流式复制 READY 媒体、回写带摘要的版本化产物，并在关键帧清单最后完成后才发布 coordinator；同版本再次执行直接 REUSED。单测证明超时进程被销毁、失败/恢复时本地目录清理、已完成音频片不重写；真实 4 秒 fixture + MinIO/MySQL 集成测试证明完整音频窗、关键帧清单、对象可读和重复执行 attempt 不增加。T2 不调用 ASR/OCR，供应商调用从 T3/T4 开始。

## T3：完成 TeleSpeechASR 分片执行、调用审计与断点恢复

- [x] 实现现有 `SpeechRecognitionPort` 的 TeleSpeechASR Adapter，把分片相对时间统一换算为全片毫秒并校验非空、边界、顺序和响应大小；供应商类型不得越过 Adapter。
- [x] 使用 ASR 独立有界执行器逐片执行；调用前写 `model_call`，调用后记录 provider/model version、状态、尝试、耗时、音频计量、估算成本/币种/价格版本或明确的不可得事实。
- [x] 只对 T0 冻结的临时错误做有限重试且不在线程中 sleep；超时/连接中断标为 `UNKNOWN_OUTCOME`，永久失败使 Job 稳定失败，禁止用 OCR 冒充完整转录。
- [x] 每片转录与 completed Checkpoint 原子提交；应用重启、MQ 重投或同 Job 并发时跳过全部已完成且指纹一致的音频片，只处理剩余片。
- [x] Redis 清空、重启或暂时不可用后，仍从 MySQL 恢复分片与调用审计；已完成 ASR 分片调用增量为 0，现有 Job 锁丢失时由数据库 CAS/唯一约束保持单赢家。
- **验收标准**：已完成片的供应商调用次数在重启后不增加；重复/乱序消息不重复调用；时间戳正确偏移到全片；每次真实调用都有可审计记录且不含凭据/完整原始响应。
- **验证**：Adapter fixture 测试、重试/UNKNOWN 测试、调用审计集成测试、进程/应用中断恢复测试；`server\mvnw.cmd test` 和相关 `verify -Pintegration`。
- **预计改动区域**：ASR Adapter/configuration、VideoContext 编排、model call persistence、恢复测试。
- **依赖/规模**：T2 / M。
- **完成记录（2026-11-04）**：新增 SiliconFlow `TeleAI/TeleSpeechASR` multipart Adapter 与独立有界执行池，供应商只返回整片文本时使用可信音频工作项窗口生成全片毫秒范围。新增 `ASR_SLICE`、V10 调用审计字段及唯一 attempt 约束；调用前持久化 STARTED，成功时把 transcript artifact、Checkpoint 和审计原子收敛。429/503/504 仅落为有限可重试失败，不在执行线程重发；超时、连接中断、输出落盘失败和陈旧 STARTED 均进入 UNKNOWN/复核，避免不确定情况下重复付费。真实 MySQL 集成测试证明重复执行调用增量为 0、UNKNOWN 不自动重发、陈旧 STARTED 不重新调用。业务 Job handler 的最终串联仍按计划在 T5 完成。

## T4：完成 Tesseract OCR、调用审计与视觉通道降级

- [ ] 实现现有 `OcrPort` 的 Tesseract Adapter，使用 OCR 独立有界执行器处理关键帧，限制超时、输出、语言包和并发，并校验置信度/文本大小。
- [ ] 为每帧写 `model_call` 审计，记录 Tesseract/version、状态、耗时、图片计量和零成本事实；失败信息使用稳定分类，不保存命令行内部路径或整份原始输出。
- [ ] 逐帧结果与 Checkpoint 原子提交；局部失败继续剩余帧，达到冻结的通道失败条件后把 OCR 标为 `DEGRADED` 并完成 ASR-only Context，不回滚已完成 ASR。
- [ ] 超时、取消和异常必须销毁子进程并清理临时图片；已完成同指纹帧在重启后不得再次 OCR。
- [ ] Redis 清空、重启或暂时不可用不得重置 OCR 工作项、attempt 或调用计数；不得把完整 OCR、帧或 Checkpoint payload 放入 Redis。
- **验收标准**：正常帧得到带时间点的 OCR；强制 Tesseract 缺失/超时/坏图时 Job 仍可凭 ASR 进入 READY 且降级事实可查询；ASR 与 OCR 不共享会互相饿死的执行池。
- **验证**：Tesseract Adapter/清理单测、OCR 部分/整通道失败集成测试、重启跳过测试；`server\mvnw.cmd test`。
- **预计改动区域**：OCR Adapter/configuration、VideoContext 编排、model call persistence、降级测试。
- **依赖/规模**：T2 / M；可与 T3 并行。

### Checkpoint B：可恢复的双通道处理

- [x] ASR completed 分片在重启/MQ 重投后零重复调用。
- [ ] OCR 故障可稳定降级，ASR 硬失败可稳定终止，二者错误语义不混淆。
- [ ] FFmpeg、TeleSpeechASR、Tesseract 均有独立资源上限、超时、清理和审计证据。
- [ ] Redis 清空/中断后，MySQL 中 Context/Checkpoint/调用事实不变，已完成 ASR/OCR 的外部调用增量为 0。

## T5：对齐并发布可查询 VideoContext

- [ ] 用确定性对齐规则把 TranscriptSegment、OCR 观察和关键帧合并为有序 `VideoSegment`；覆盖 `[0, durationMs]`，裁剪越界时间，保留来源引用和通道状态，不在 M2 生成 `VideoChunk`。
- [ ] 在同一完成事务中持久化最终 Segment、Context 版本/摘要/降级状态、completed Checkpoint、JobEvent，并经统一 `JobTransitionService` 从 `CONTEXT_BUILDING` 进入 `READY`。
- [ ] 提供 owner-scoped Context 摘要和按时间窗/游标读取 Segment 的 application service + REST API；提供 owner 校验后的原视频/关键帧短期 GET URL，链接过期可重新申请。
- [ ] 替换 M1 fake handler：真实编排只执行尚未完成的步骤；取消、失败、CAS 冲突和重复消息继续沿现有 MQ ACK/retry/DLQ 语义收敛。继续把 Redisson Job 锁视为减冲突优化，`UNAVAILABLE` 时回到 MySQL 裁决；M2 不增加 Checkpoint 缓存或 Redis Pub/Sub。
- **验收标准**：Segment 时间窗合法、单调且无重复 ordinal；Context 只发布一次；跨 owner 一律不泄露存在性；READY 事件不声称 INDEXING；完整转录/模型输出不进入 MQ。
- **验证**：对齐规则单测、Context 完成事务/状态机/owner API 集成测试、重复消息测试；`server\mvnw.cmd test` 和相关 `verify -Pintegration`。
- **预计改动区域**：context application/domain/persistence、analysis handler/transition、web contract/controller、对应测试。
- **依赖/规模**：T3、T4 / M。

## T6：交付视频播放器与统一时间轴

- [ ] 扩展前端合同/API client，读取 Context 摘要、分页/时间窗 Segment 和短期播放/关键帧 URL；认证过期、404、处理中、降级和链接过期均有稳定处理。
- [ ] 在现有 Job 工作台内交付一个原生视频播放器和一条统一时间轴，按 ASR、OCR、关键帧分层显示真实内容与通道状态；不另建全局 store 或第二套 Job 状态机。
- [ ] 点击 Segment/OCR/关键帧将播放器定位到对应时间并保持键盘可操作；时间更新只高亮当前 Segment，不触发无界请求或重渲染整条长时间轴。
- [ ] 刷新后从 Job/Context API 恢复；未完成阶段显示服务端进度，OCR 降级有明确提示，任何未完成分片都不显示为完成。
- **验收标准**：桌面、平板和 320/375px 手机可读；键盘可选片段并 seek；刷新与短期 URL 续签正常；Console 无错误/警告；页面不接触 MinIO 长期凭据/object key。
- **验证**：API/组件失败测试；`client\npm.cmd run check`；真实浏览器核对 Range 请求、seek、刷新、降级、可访问树和 Console。
- **预计改动区域**：client context API/contracts、timeline/player feature、Job 工作台接线、样式与测试。
- **依赖/规模**：T5 / M。

## T7：执行真实视频、恢复与降级验收矩阵

- [ ] 在 Compose 环境用 T0 冻结的 45-90 分钟视频跑通上传、校验、派生、ASR、OCR、对齐、READY、时间轴和点击跳转，记录阶段耗时、分片数、调用数、成本和降级状态。
- [ ] 自动化/故障注入：ASR 部分分片完成后终止应用并重启、MQ 重投、Redis `FLUSHDB`/容器重建/短时不可达、OCR 全通道失败、Tesseract 超时、取消竞态和双用户越权；核对 MySQL 事实不变、已完成 ASR/OCR 调用增量为 0、数据库 CAS 仍单赢家。
- [ ] 抽查开头/中间/结尾及 ASR/OCR 重叠区域的时间对齐；浏览器验证刷新恢复、短期 URL 过期续签、手机布局、键盘路径、网络请求和零 Console 错误。
- **验收标准**：Roadmap M2 五项退出门都有可复现证据；完整时间轴覆盖首条样本；已完成 ASR 分片重启后调用增量为 0；OCR 故障仍可用 ASR；每次外部/模型调用均可审计。
- **验证**：`server\mvnw.cmd verify -Pintegration`；`client\npm.cmd run check`；Compose + 真实浏览器验收；结果写入 `tasks/M2/ACCEPTANCE.md`，不提交视频/完整转录/模型原始响应。
- **预计改动区域**：故障/集成测试、浏览器验收记录、必要的窄范围缺陷修复。
- **依赖/规模**：T6 / M。

## T8：更新文档、运行质量门并完成 M2 退出记录

- [ ] 更新 README、`.env.example`、Compose、本地运行手册和供应商配置说明，写清 FFmpeg/Tesseract/TeleSpeechASR 配置、资源上限、降级和排障；只记录实际验证能力。
- [ ] 记录每条命令的结果、耗时、环境、失败/未执行项和对应 commit；复核变更行覆盖率不少于 80%、ArchUnit、秘密/依赖扫描及无 suppression/Floor 违规。
- [ ] 逐项核对 M2 退出门并由负责人确认；未打通真实 TeleSpeechASR、未完成 45-90 分钟视频或未证明恢复时不得以 fake/小 fixture 替代退出。
- **验收标准**：文档不宣称 M3/M4 能力；所有退出项有真实证据；安全遗留风险如实延续且未降低门槛；工作树只含预期变更。
- **验证**：`server\mvnw.cmd test`；`server\mvnw.cmd verify -Pintegration`；`client\npm.cmd run check`；`client\npm.cmd audit --audit-level=high`；`docker compose config --quiet`；`powershell -ExecutionPolicy Bypass -File .\scripts\security-check.ps1`；`git diff --check`。
- **预计改动区域**：README、配置示例、运行手册、`tasks/M2/ACCEPTANCE.md`、本文件。
- **依赖/规模**：T7 / S。

## M2 退出门

- [ ] 首条选定的 45-90 分钟教学视频生成覆盖完整时长的 ASR/OCR/关键帧统一时间轴。
- [ ] OCR 失败时 Context 明确标记视觉通道降级，ASR 结果和播放器时间轴仍可用。
- [ ] ASR 阶段中断并重启后，所有已完成且指纹一致的分片不被重新调用。
- [ ] TeleSpeechASR/Tesseract 调用均有版本、状态、耗时、计量、成本或零成本/不可得事实和稳定错误记录。
- [ ] 浏览器可检查时间对齐、点击定位、通道降级、服务端阶段进度和刷新恢复，且不暴露对象 key/长期凭据。
- [ ] 本次实际质量门结果和未执行项已记录，项目负责人确认可进入 M3。
