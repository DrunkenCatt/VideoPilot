# M1 任务清单：上传与异步 Job 最小闭环

> 状态：已获准进入 M1（2026-10-29），T0 已完成。任务必须按依赖顺序执行；每项先写失败测试或可执行验收场景，再写生产代码。

## T0：关闭 M0 外部门禁并确认进入 M1

- [x] 确认远端 CI run `40837414790` 的 backend、frontend、integration 与 Gitleaks 通过。
- [x] 确认 OWASP Dependency-Check 仍失败，报告已保留，安全 Job 和 CVSS 7 阻断阈值未被降低或绕过。
- [x] 项目负责人明确接受 OWASP 遗留风险，M0 验收记录和退出门已更新，并批准进入 M1。
- **验收标准**：`tasks/M0/TODO.md` 与 `tasks/M0/ACCEPTANCE.md` 已记录 M0 完成及负责人风险接受；通过项和未通过的 OWASP 检查被准确区分，未把风险接受表述为扫描成功。
- **验证**：已复核提交 `852d27e`、远端 CI run `40837414790`、`tasks/M0/TODO.md` 和 `tasks/M0/ACCEPTANCE.md`；当前分支保留安全门与遗留风险记录。
- **预计文件**：`tasks/M0/TODO.md`、`tasks/M0/ACCEPTANCE.md`。
- **依赖/规模**：无 / S。
- **完成记录（2026-10-29）**：M0 线程已在提交 `852d27e` 记录负责人决定并关闭 M0。M1 接受该决定作为 T0 的完成依据；OWASP 风险仅被接受、尚未修复，后续扫描仍应如实失败直至依赖问题解决。

## T1：冻结 M1 验收场景与接口契约

- [x] 先写重复提交、同 key 异请求、MQ 重投、SSE 断线补拉、取消、失败、重启找回和跨用户越权的失败验收场景。
- [x] 定义版本化 REST 输入/输出、分页、统一错误码、认证/CSRF、`Idempotency-Key` 和 owner 语义；定义 SSE event id/类型/payload/重连规则。
- [x] 定义最小 MQ envelope，仅含 `messageId/jobId/eventType/schemaVersion/requestId` 等定位与追踪字段，禁止视频和完整上下文进入消息体。
- **验收标准**：每个端点和事件都有类型化合同；同 key/同指纹返回既有 Job，同 key/不同指纹返回稳定冲突；SSE 可用 `Last-Event-ID` 从 MySQL 补拉。
- **验证**：合同测试先失败；人工对照 ADR-0001/0002/0003、Roadmap 5/6/11/13 节；`git diff --check -- tasks server client`。
- **预计文件**：`tasks/M1/ACCEPTANCE.md`、后端 API DTO/合同测试、前端 API 类型、消息 envelope 合同。
- **依赖/规模**：T0 / M。
- **完成记录（2026-10-29）**：新增 12 个 Given/When/Then 验收场景，冻结 `/api/v1` 会话、multipart 上传、媒体、Job、取消和 SSE 资源合同；固定 5 GiB 上限、服务端会话/CSRF、owner 隐式归属、`Idempotency-Key` 冲突语义、MySQL sequence 补拉和六字段 MQ envelope。Java/TypeScript 合同与稳定错误码已落地。RED 阶段分别因合同类型缺失、前端模块缺失和认证状态不匹配失败；GREEN 阶段后端 28 项测试及 JaCoCo 门、前端 5 项测试及完整 `check` 均通过。

## T2：实现本地账号登录与资源归属

- [ ] 以前进 Flyway 迁移建立最小用户/会话所需真源字段，不修改 V1；密码只保存强哈希。
- [ ] 通过 identity application service 实现登录/退出及当前用户读取，安全配置统一处理未认证、CSRF 和 cookie 属性。
- [ ] 用两个用户证明媒体/Job application service 的 owner 上下文不可由请求体伪造。
- **验收标准**：正确凭据可登录，错误凭据返回统一错误；未认证请求被拒绝；用户 ID 只来自可信认证上下文；日志和响应不泄露密码哈希。
- **验证**：聚焦 identity/security 测试；`server\mvnw.cmd test`；ArchUnit 仍通过。
- **预计文件**：用户迁移、identity domain/application/port、security Adapter、认证合同测试。
- **依赖/规模**：T1 / M。

## T3：实现 Job 领域模型、统一状态机与前进迁移

- [ ] 为 `AnalysisJob`、`JobStageAttempt`、`JobEvent` 建立领域类型、稳定错误分类和合法迁移表。
- [ ] 实现唯一的 `JobTransitionService`：校验前置状态、version CAS、阶段输入指纹，并在同一事务写 Job、阶段和事件/Outbox。
- [ ] 用新迁移补足 V1 为 M1 所需的字段/约束/索引；不得回写或重命名 V1。
- **验收标准**：所有合法边有单测；非法边和终态推进被拒绝；并发旧 version 只有一个成功；Controller/Consumer/Mapper 不能绕过迁移入口更新状态。
- **验证**：状态机参数化测试、CAS 集成测试、ArchUnit；`server\mvnw.cmd test`；`server\mvnw.cmd verify -Pintegration`。
- **预计文件**：analysis domain/application/port、MyBatis-Plus persistence Adapter、V2+ 迁移、状态机/集成测试。
- **依赖/规模**：T1 / M。

## T4：幂等创建 Job 与事务 Outbox

- [ ] 实现创建 Job application service，在单事务原子写入 Job、初始阶段/事件和待发布 Outbox。
- [ ] 以数据库唯一约束裁决 `ownerId + idempotencyKey` 和逻辑执行冲突，校验规范化请求指纹。
- [ ] 提供提交与查询端点，Controller 只调用 application service，响应使用 `202 Accepted` 和稳定 status/events URL。
- **验收标准**：并发同 key/同请求只产生一个 Job；同 key/不同请求稳定冲突；数据库提交失败不留下孤立 Outbox，成功提交一定有待发布消息。
- **验证**：先写并发失败测试；聚焦 application 测试；MySQL 集成测试；`server\mvnw.cmd test`。
- **预计文件**：analysis application service、web Controller/DTO、persistence Adapter、幂等集成测试。
- **依赖/规模**：T2、T3 / M。

### Checkpoint A：契约与真源

- [x] M0 已正式退出。
- [x] M1 接口合同已由 T1 冻结。
- [ ] Job 创建、状态迁移、阶段和 Outbox 的单事务/CAS 行为有真实 MySQL 证据。
- [ ] `server\mvnw.cmd test` 与相关 integration 测试通过。

## T5：声明 RabbitMQ 拓扑并实现可靠 Outbox Relay

- [ ] 按 ADR-0001 声明持久化 direct exchange、主队列、TTL retry queue 和 DLQ，配置 publisher confirm/return。
- [ ] Relay 批量抢占到期 PENDING 记录，发布精简 envelope；仅在 confirm 成功后标记已发布，失败/超时/不可路由时有限退避重试。
- [ ] 多实例 Relay 不会同时发布同一轮记录；进程在数据库提交后或 confirm 前退出时，记录仍可再次发布。
- **验收标准**：成功、nack、return、confirm timeout 均有确定状态；消息体不含视频/完整上下文/模型输出；Relay 重发被视为正常语义。
- **验证**：RabbitMQ + MySQL 集成测试；停止/重启 Relay 的故障场景；`server\mvnw.cmd verify -Pintegration`。
- **预计文件**：messaging 配置、Outbox relay Adapter、配置项、集成测试。
- **依赖/规模**：T4 / M。

## T6：实现幂等 Consumer、Redisson 锁与阶段 CAS

- [ ] Consumer 手动 ACK，先按 jobId 回查 MySQL；Redisson 锁只降低同 Job 并发冲突。
- [ ] 用期望状态、version CAS 和 `jobId + stage + inputFingerprint` 唯一约束取得执行资格；重复/乱序消息成为成功 no-op。
- [ ] 实现不调用模型的 M1 假任务处理器，记录阶段与事件；业务事务成功后 ACK，异常按稳定分类进入有限重试或失败路径。
- **验收标准**：同一消息并发/串行重投都不会重复推进阶段；锁丢失时数据库仍防重；ACK 前进程退出后重投可安全收敛。
- **验证**：重复消息、并发 Consumer、锁失效和 ACK 边界集成测试；`server\mvnw.cmd verify -Pintegration`。
- **预计文件**：analysis handler、Rabbit consumer Adapter、Redisson 配置/Adapter、集成测试。
- **依赖/规模**：T5 / M。

## T7：实现 Job 查询、持久化 SSE、取消与失败语义

- [ ] 提供 owner-scoped Job 详情/分页列表和事件补拉；不存在与无权访问采用不泄露资源存在性的统一语义。
- [ ] SSE 以 MySQL `job_sequence` 为 event id，先补拉缺口再订阅热事件；断线和应用重启后支持 `Last-Event-ID` 恢复。
- [ ] 取消端点通过状态机处理 CREATED/QUEUED 与运行中状态；失败响应展示稳定 error code、retryable 和安全摘要。
- **验收标准**：刷新或重连不丢、不乱序、不重复展示事件；终态 SSE 正常结束；取消竞态由 CAS 裁决；跨用户无法读取 Job 或事件。
- **验证**：MockMvc 合同测试、MySQL/SSE 集成测试、取消竞态测试；`server\mvnw.cmd test`。
- **预计文件**：analysis query/application service、web Controller/SSE、事件发布 Adapter、测试。
- **依赖/规模**：T6 / M。

## T8：交付假任务工作台纵向闭环

- [ ] 前端建立认证状态、Job 提交/列表/详情 API client 和类型化错误处理。
- [ ] 工作台显示真实状态、阶段、事件、失败原因和取消按钮；SSE 断开采用有界退避并携带最后 event id。
- [ ] 用测试媒体记录跑通“提交 -> 实时进度 -> 刷新补拉 -> 完成/取消/失败”页面流程。
- **验收标准**：页面不根据本地计时伪造进度；刷新后状态来自服务端；失败和取消可访问且明确；无 Console error。
- **验证**：Vitest 组件/API 测试；`client\npm.cmd run check`；浏览器手工验收核心流程。
- **预计文件**：前端 API/types、认证与 Job views/components、样式、测试。
- **依赖/规模**：T7 / M。

### Checkpoint B：可靠假任务

- [ ] Outbox -> RabbitMQ -> Consumer -> MySQL -> SSE 全链路可运行。
- [ ] 重复消息、断线补拉、取消和失败场景通过。
- [ ] 后端快速/相关集成测试和前端 check 通过。

## T9：实现 MediaAsset 与 MinIO upload session

- [ ] 实现 MediaAsset domain/application/port 和 MyBatis-Plus Adapter；对象 key 只能由服务端生成并绑定 owner/mediaId。
- [ ] 实现 MinIO Adapter 和 upload session 的创建、完成、过期/中止语义；签名权限仅覆盖目标对象且短期有效。
- [ ] 以客户端 SHA-256、服务端对象元数据和数据库唯一约束处理重复上传，不信任文件名/MIME 作为真实性依据。
- **验收标准**：视频可流式上传且应用不持有 MinIO 密钥的前端副本；完成前不能提交分析；重复完成幂等；跨用户不能读取、完成或覆盖对象。
- **验证**：MinIO + MySQL 集成测试；大于内存阈值的流式上传测试；owner 隔离测试；`server\mvnw.cmd verify -Pintegration`。
- **预计文件**：media domain/application/port、MinIO/persistence Adapter、迁移/配置、集成测试。
- **依赖/规模**：T2 / M。

## T10：实现 FFprobe 校验并连接真实媒体与 Job

- [ ] 用受控进程 Adapter 执行 FFprobe，限制超时、输出大小和并发，校验容器/视频流/时长并解析为供应商无关结果。
- [ ] 对象读取到受控临时位置或安全流接口，所有成功/失败/取消路径都清理资源并销毁子进程。
- [ ] 上传完成后写可信媒体元数据；只有校验通过且归属匹配的 MediaAsset 可创建 Job，损坏/非视频文件得到稳定不可重试错误。
- **验收标准**：有效 fixture 得到 duration 等元数据；损坏文件、无视频流、超时、缺少 FFprobe 均可诊断；失败不遗留可分析媒体或临时文件。
- **验证**：FFprobe Adapter 单测 + 真实小视频集成测试；进程超时/清理测试；`server\mvnw.cmd test`。
- **预计文件**：media probe port/application service、FFprobe Adapter、配置、测试 fixture/测试。
- **依赖/规模**：T3、T9 / M。

## T11：交付真实上传与进度页面纵向闭环

- [ ] 登录页/会话恢复和视频选择、上传进度、校验状态、分析目标/模式提交形成一条用户路径。
- [ ] 客户端只持有 upload session 返回的短期凭据，重试同一意图复用 idempotency key；取消上传和错误恢复语义明确。
- [ ] Job 页面展示真实媒体名、时长、状态和可补拉进度，用户不能在 URL/请求体切换 owner。
- **验收标准**：用户可从空页面完成“登录 -> 上传 -> 校验 -> 提交 -> 查看进度”；刷新不丢 Job；上传和分析错误均可恢复或明确终止。
- **验证**：前端单测；`client\npm.cmd run check`；真实浏览器 + MinIO/RabbitMQ/MySQL 冒烟验收。
- **预计文件**：前端 auth/upload/workspace views、API/types、路由/样式、测试。
- **依赖/规模**：T8、T10 / M。

## T12：加入 Redis active key 并证明可重建

- [ ] active key 只作为 owner + analysisKey 的快速命中和短期状态缓存，设置明确命名、TTL 与删除时机。
- [ ] 缓存 miss/Redis 不可用时回查 MySQL；启动或按需可从非终态 Job 重建，不因缓存丢失创建重复 Job。
- [ ] SSE 热事件可使用 Redis 加速，但历史补拉、顺序和终态始终以 MySQL 为准。
- **验收标准**：清空 Redis 后重复提交仍返回同一逻辑执行；Job 查询/SSE 补拉正常；Redis 故障不改变数据库事实。
- **验证**：Redis flush/restart 集成场景；重复提交并发测试；`server\mvnw.cmd verify -Pintegration`。
- **预计文件**：analysis cache port、Redis Adapter、配置、集成测试。
- **依赖/规模**：T6、T7、T10 / S。

### Checkpoint C：真实上传

- [ ] MinIO 上传、FFprobe、媒体元数据和 Job 提交端到端通过。
- [ ] Redis 清空后仍能从 MySQL 恢复正确行为。
- [ ] 两个用户的媒体、对象和 Job 全链路隔离。

## T13：执行 M1 可靠性与权限验收矩阵

- [ ] 自动化并发重复提交、同 key 异请求、重复/乱序 MQ、Consumer 提交前退出、提交后 ACK 前退出。
- [ ] 自动化应用重启后 Job/事件找回、SSE 重连、Redis 清空、取消竞态和失败展示。
- [ ] 自动化双用户枚举/读取/取消/上传完成越权测试，并检查消息和日志不含视频、凭据或完整上下文。
- **验收标准**：Roadmap M1 四项退出门全部有可重复证据；同一幂等请求的逻辑 Job 数和阶段执行次数均为 1；越权请求均不泄露资源存在性。
- **验证**：`server\mvnw.cmd verify -Pintegration`；`client\npm.cmd run check`；Compose 环境端到端验收脚本。
- **预计文件**：M1 integration/fault tests、验收脚本、必要 fixture、`tasks/M1/ACCEPTANCE.md`。
- **依赖/规模**：T8、T11、T12 / M。

## T14：更新文档、运行质量门并完成 M1 退出记录

- [ ] 更新 README、配置示例、API/SSE/MQ 使用说明和本地演示步骤；只记录实际已验证能力。
- [ ] 记录每条命令的结果、耗时、环境、失败/未执行项和对应 commit，不用“命令存在”代替成功证据。
- [ ] 复核变更行覆盖率不少于 80%、ArchUnit、秘密/依赖扫描和无 suppression/Floor 违规；由负责人确认 M1 退出。
- **验收标准**：M1 退出门全部勾选且证据可复现；文档不宣称 M2 的 ASR/OCR/Checkpoint 恢复能力；工作树只含预期变更。
- **验证**：`server\mvnw.cmd test`；`server\mvnw.cmd verify -Pintegration`；`client\npm.cmd run check`；`client\npm.cmd audit --audit-level=high`；`docker compose config --quiet`；`powershell -ExecutionPolicy Bypass -File .\scripts\security-check.ps1`；`git diff --check`。
- **预计文件**：`README.md`、`.env.example`、`tasks/M1/ACCEPTANCE.md`、`tasks/M1/TODO.md`。
- **依赖/规模**：T13 / S。

## M1 退出门

- [ ] 重复提交只创建一个逻辑执行；同 key 不同请求被明确拒绝。
- [ ] MQ 消息重复或乱序投递不重复推进阶段。
- [ ] 应用重启后 Job 和事件仍能从 MySQL 找回，SSE 可补拉。
- [ ] 其他用户不能读取、枚举、完成上传、取消或订阅不属于自己的媒体/Job。
- [ ] MinIO 上传、媒体元数据和 FFprobe 校验形成真实闭环。
- [ ] Redis active key 丢失后可从 MySQL 重建，不成为事实真源。
- [ ] 本次实际质量门结果和未执行项已记录，项目负责人确认可进入 M2。
