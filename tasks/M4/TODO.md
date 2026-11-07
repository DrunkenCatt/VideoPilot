# M4 任务清单：任务可靠性发布门

> 状态：T0-T3 已完成，下一项为 T4。任务按依赖顺序执行，每项先写失败测试或可执行验收场景，再写生产代码。目标是在现有 Job/Checkpoint/Outbox/工作台上补齐 V1 发布可靠性，不新建通用工作流、策略注册表或独立运维平台。

## T0：冻结 M4 可靠性合同与故障矩阵

- [x] 在 `tasks/M4/ACCEPTANCE.md` 写出自动恢复、有限重试、重试耗尽、DLQ、UNKNOWN、不可重试、预算耗尽、Manual Replay、owner 隔离、并发操作、SSE 重连和运维视图的 Given/When/Then 场景。
- [x] 盘点 M1-M3 已有状态迁移、Checkpoint、Outbox、消费失败、模型调用和 SSE 合同，形成“直接复用 / M4 补齐 / 明确不做”矩阵；不得复制 ASR/OCR/索引恢复逻辑到第二套框架。
- [x] 冻结错误分类到状态/操作的唯一映射，以及停滞阈值、lease、重试次数/退避、Replay attempt、Checkpoint 复用与失效、deadline/预算/并发阈值和恢复率统计口径。
- [x] 若新增或修改状态迁移、消费重试或 Replay 语义，新增 ADR-0008 记录 MySQL 真源、UNKNOWN 禁止自动重发、同 Job 新 attempt 和旧消息不可重放等决策；局部字段留在迁移和验收文档。
- [x] 冻结最小 REST/TypeScript 合同：Job 恢复投影、Replay 命令/审计结果和只读运维摘要；响应不得包含 ownerId、对象 key、Prompt、模型原始响应、内部异常或凭据。
- **验收标准**：Roadmap M4 的每个交付和退出门都映射到可执行场景；状态/错误/操作没有歧义；故障样本有固定分母和直接失败条件；没有引入 M5/M6 范围。
- **验证**：合同测试先 RED；人工对照 `CONSTRAINTS.md`、Roadmap 4/5/7/11/13/14 节和 ADR-0001/0003/0005/0006；`git diff --check -- docs tasks server client`。
- **预计改动区域**：`tasks/M4`、必要的 `docs/adr/0008-*`、后端/前端合同测试。
- **依赖/规模**：无 / M。

## T1：打通自动 Recovery、有限 Retry 与 DLQ 状态闭环

- [x] 用一份前进 Flyway 迁移只补充恢复所需事实，例如 `next_retry_at`、执行/恢复 lease、失败来源和 attempt 审计；保留 `analysis_job`、`job_stage_attempt`、`job_event_outbox` 为唯一真源，不创建第二套 retry job 表。
- [x] 在一个可靠性 application service 中实现基于 MySQL 的停滞扫描和恢复决策：只领取 lease 已过期且状态允许的 Job，经 CAS/统一迁移入口进入 `RETRY_WAIT -> QUEUED` 并写新 Outbox；完成且输入指纹一致的阶段直接复用。
- [x] 将 pipeline 的稳定错误分类收敛为统一动作：可重试错误有限退避，UNKNOWN 停止自动高成本重发，不可重试错误失败，取消保持取消，重试耗尽进入 `DEAD_LETTERED`；任何路径都不能直接 SQL 改 Job 状态。
- [x] 让消费重试耗尽、消息合同错误和 Job 执行失败审计可关联；Broker DLQ 成功/失败不能成为 Job 真源，页面最终状态必须能从 MySQL 重建，RabbitMQ/Redis 恢复后待办可继续发布/消费。
- [x] 在现有 Job DTO、事件和工作台中最小增加“自动恢复中 / 等待重试 / 需人工处理 / 不可恢复”投影；刷新和 SSE 重连后从持久化事件恢复，不增加第二条实时协议。
- [x] 覆盖应用在 PROBING、CONTEXT_BUILDING、INDEXING 前后退出、扫描器/消费者竞态、旧 lease、重复/乱序消息、Redis 丢失和 RabbitMQ 短暂不可用；断言任务不丢且已完成 ASR/OCR/Embedding 调用增量为 0。
- **验收标准**：可恢复 Job 自动续跑；自动重试次数有界且时间可审计；UNKNOWN 不自动重发；消息死信与 Job 状态可关联；MySQL/Outbox 可在 Redis/RabbitMQ 短暂丢失后重建进度。
- **验证**：状态机/恢复策略纯规则测试、MySQL CAS/lease/Outbox 集成测试、RabbitMQ/Redis 故障集成测试、Job DTO/SSE/组件测试；相关测试后运行 `server\mvnw.cmd test` 与定向 `verify -Pintegration`。
- **预计改动区域**：analysis application/domain/聚合 store、analysis/messaging persistence、Flyway、Job web/client 投影及对应测试。
- **依赖/规模**：T0 / M。

### Checkpoint A：自动恢复真源

- [x] 重启和重投只推进同一 MySQL Job，不依赖内存定时器、Redis key 或旧 RabbitMQ 消息恢复事实。
- [x] 已完成且指纹一致的阶段不重复调用供应商；任何重复高成本调用都使检查点失败。
- [x] `RETRYABLE`、`UNKNOWN_OUTCOME`、`NON_RETRYABLE`、`CANCELED`、`BUDGET_EXHAUSTED` 的状态和用户语义稳定可测。

## T2：交付受控 Manual Replay 端到端操作

- [x] 在 T1 的可靠性 service 内增加 owner-scoped Replay 命令：只接受 T0 冻结的前置状态、预期 Job 版本、操作幂等键、明确确认和非空原因；使用既有 `DEAD_LETTERED -> MANUAL_REPLAY -> QUEUED` 受控迁移或 ADR-0008 冻结的新路径。
- [x] Replay 递增同一 Job 的 execution attempt，写不可变审计事件和新的 Outbox 消息；旧 attempt、失败记录和事件保持只读，禁止重新投递旧 messageId。
- [x] 依据阶段输入指纹复用视频固有的已完成 Context/索引和模型调用结果，只让失败/失效阶段重新执行；UNKNOWN 高成本调用必须先进入人工确认语义，不能被 Replay 默认当作未执行。
- [x] 提供最小 REST API 和 TypeScript client，在现有 Job 工作台为可 Replay 状态显示危险操作；确认文案说明会产生新执行尝试，提交中防双击，成功后显示 attempt/审计结果，403/409/重复提交有稳定反馈。
- [x] 覆盖跨 owner、非法状态、旧版本、重复幂等键、并发双击、Outbox 发布中断和 Replay 后刷新/SSE 恢复；断言仅一个新 attempt/消息，高成本已完成阶段调用增量为 0。
- **验收标准**：用户只能 Replay 自己且满足条件的 Job；每次操作可追溯、可幂等、不可改写历史；浏览器能区分 Replay 已接受、执行中、再次失败和完成。
- **验证**：Replay 规则/Controller/持久化集成测试、前端 API/组件测试、真实浏览器确认与刷新验收；相关测试后运行 `server\mvnw.cmd test`、`client\npm.cmd run check` 和定向集成测试。
- **预计改动区域**：analysis reliability application/store/web、Flyway（若 T1 迁移未覆盖审计字段）、jobs API/workbench 及对应测试。
- **依赖/规模**：T1 / M。

## T3：落地全链路 deadline、预算、限流与资源池隔离

- [x] 定义一份最小、版本化执行限制配置，至少包含 Job deadline、各 operation 最大调用数、输入/输出计量、已知成本上限、供应商并发/等待上限和子进程/消息池容量；配置非法时启动失败，实际值写入执行 Trace 或事件。
- [x] 用一个确定性预算策略读取现有 Job/`model_call` 聚合，在每次 ASR、OCR、Embedding、LLM 高成本调用前后检查；价格不可得时显式保留 unknown，并继续执行调用数、计量和 deadline 硬门。
- [x] 超限后不再发起下一次高成本调用：Job pipeline 经统一状态迁移和事件记录 `BUDGET_EXHAUSTED`，同步回答经既有 `answer_record/model_call` 记录稳定预算错误且不回退 READY Job；超时/中断清理 FFmpeg/Tesseract/HTTP 资源，UNKNOWN 仍遵守 T1 的禁止自动重发规则。
- [x] 复用并补齐现有 Adapter semaphore/超时，把外部模型、FFmpeg/Tesseract、Rabbit consumer 与 Web/SSE 放入彼此隔离的有界执行资源；池满时有限等待或返回稳定可重试错误，不创建通用 bulkhead/ratelimiter 接口族。
- [x] 覆盖 deadline 边界、计量/成本累加、价格不可得、并发争抢、队列满、429/Retry-After、线程中断和子进程清理；断言硬门由 Java 决定且模型响应不能修改预算。
- **验收标准**：每个高成本阶段受相同版本化限制约束；任一硬门超限都确定性停止且落入所属聚合的正确真源；同步问答失败不污染 READY Job；资源饱和不拖死健康检查、Job 查询或 SSE；限制不会通过重启清零而绕过。
- **验证**：预算纯规则/Adapter 边界/并发测试、MySQL 调用聚合集成、资源饱和故障测试；相关测试后运行 `server\mvnw.cmd test` 与定向 `verify -Pintegration`。
- **预计改动区域**：analysis/model budget policy 与聚合 store、现有模型/媒体 Adapter 配置、pipeline 接线及对应测试。
- **依赖/规模**：T1 / M。

### Checkpoint B：人工操作与资源硬门

- [x] Replay 的 CAS、幂等、owner 和历史不可变性由自动化测试与真实浏览器共同证明。
- [x] deadline、调用数、计量和已知成本至少各有一个硬门失败样本；未知价格不显示为 0。
- [x] 外部模型、子进程和消息消费饱和时，Web/SSE/健康查询仍可响应。

## T4：交付只读可靠性运维仪表

- [ ] 用一个聚合查询从 MySQL 和 RabbitMQ 管理边界读取固定窗口内的 Outbox/队列积压、Job 状态、失败分类、自动恢复/Replay 结果、阶段耗时、供应商错误、调用计量和已知/未知成本；公式、时间窗和数据更新时间明确。
- [ ] 提供一个只读 application service 和 REST DTO；Controller 不直连 JDBC/Rabbit，响应只含聚合值、稳定枚举和时间戳，不返回 Prompt、原始响应、对象 key、堆栈或其他 owner 的内容。
- [ ] 在现有工作台增加轻量“可靠性”只读区域，展示积压、失败分类、阶段耗时和供应商/成本状态；空数据、部分依赖不可用和陈旧数据均明确展示，不伪造 0 或绿色健康。
- [ ] 对积压和 P50/P95 等指标使用有界查询，避免扫描完整历史；RabbitMQ 管理信息不可用时仍返回 MySQL 指标和显式 degraded source，不影响业务 Job。
- [ ] 覆盖聚合口径、owner/敏感字段边界、空/部分失败、查询上限和前端窄视口/键盘/刷新；确认运维查询不会触发恢复、Replay 或任何模型调用。
- **验收标准**：用户可从同一工作台判断是否积压、主要失败类型、慢阶段和供应商异常；数据可追溯且脱敏；单个指标源故障不会让整个视图失真或阻断主路径。
- **验证**：聚合规则/Controller/持久化与 Rabbit 边界测试、前端 API/组件测试、真实浏览器桌面/移动验收；相关测试后运行 `server\mvnw.cmd test`、`client\npm.cmd run check` 和定向集成测试。
- **预计改动区域**：analysis/operations application 与聚合 port、infrastructure metrics query/web、现有工作台及对应测试。
- **依赖/规模**：T1、T3 / M。

## T5：完成故障注入、并发/SSE 验收与 M4 退出

- [ ] 建立可重复执行的 M4 故障脚本/测试矩阵，覆盖应用或 Worker 在关键阶段终止、RabbitMQ 重投/短暂不可用、Redis 清空/不可用、供应商 timeout/429/坏响应、Qdrant 降级、资源池饱和和 Outbox 发布中断；原视频与完整响应不入 Git。
- [ ] 并发执行相同 idempotency key 的提交、恢复扫描、消费和 Replay，断言数据库唯一约束/CAS 胜出且只产生一个逻辑 Job、一个 Replay attempt 和零重复已完成高成本调用。
- [ ] 对至少 20 次预先冻结的可恢复执行逐例记录结果，成功不少于 19 次；任何任务丢失、重复高成本阶段或非法状态迁移成功均直接判定退出失败，不用总体百分比掩盖。
- [ ] 在真实浏览器验证自动恢复、等待重试、需人工 Replay、不可恢复、预算耗尽、危险确认、运维视图、刷新和 SSE `Last-Event-ID` 重连；检查桌面/移动布局、键盘、Console 和网络错误。
- [ ] 新增 `docs/M4_RUNBOOK.md`，记录启动/诊断、错误到操作映射、恢复扫描、DLQ、Replay、预算、积压和回滚步骤；README/Roadmap/任务证据只声明实际验证的 V1 能力和遗留风险。
- [ ] 执行并记录后端快速门、完整 Docker 集成门、前端门、变更行覆盖率、架构/状态机、秘密和依赖安全检查；外部扫描不可用时如实记录，不能写成通过。
- **验收标准**：Roadmap M4 全部退出门有自动化或真实浏览器证据；恢复成功率达到至少 95%；任务零丢失、已完成高成本阶段零重复、非法迁移零成功；负责人审阅已执行/失败/未执行项后明确决定是否退出。
- **验证**：`server\mvnw.cmd test`、`server\mvnw.cmd verify -Pintegration`、`client\npm.cmd run check`、M4 故障脚本、真实浏览器矩阵和仓库安全脚本；记录命令、耗时、commit、环境、结果与未执行项。
- **预计改动区域**：M4 故障/验收脚本、`tasks/M4/ACCEPTANCE.md`、`docs/M4_RUNBOOK.md`、README/Roadmap，必要的测试修复。
- **依赖/规模**：T2-T4 / M。

### Checkpoint C：M4 / V1 MVP 退出

- [ ] 自动 Recovery、有限 Retry、DLQ 和 Manual Replay 使用同一 MySQL 状态/事件真源，并在浏览器中可区分。
- [ ] 故障矩阵达到恢复率门槛，且没有任务丢失、重复高成本调用或非法迁移成功。
- [ ] 运维仪表、预算/资源硬门、运行手册和完整质量门均基于同一候选 commit 留下可复核证据。
- [ ] 项目负责人审阅遗留风险并明确批准后，才把 M4 标为完成并进入 M5。
