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

- [x] 以前进 Flyway 迁移建立最小用户/会话所需真源字段，不修改 V1；密码只保存强哈希。
- [x] 通过 identity application service 实现登录/退出及当前用户读取，安全配置统一处理未认证、CSRF 和 cookie 属性。
- [x] 用两个用户证明可信 owner 上下文不可由请求体、Header 或查询参数伪造；具体 Media/Job 资源隔离继续由 T4/T7/T9 验证。
- **验收标准**：正确凭据可登录，错误凭据返回统一错误；未认证请求被拒绝；用户 ID 只来自可信认证上下文；日志和响应不泄露密码哈希。
- **验证**：聚焦 identity/security 测试；`server\mvnw.cmd test`；ArchUnit 仍通过。
- **预计文件**：用户迁移、identity domain/application/port、security Adapter、认证合同测试。
- **依赖/规模**：T1 / M。
- **完成记录（2026-10-29）**：V2 建立账号与会话真源；交付 BCrypt 账号、摘要会话 token、HttpOnly/Strict/Secure cookie、服务端 CSRF、Redis 共享登录限流和受控本地账号初始化。双用户 `OwnerContext` 与 HTTP 合同证明请求不能选择 owner。后端 46 项快速测试、4 项真实依赖集成测试、ArchUnit、JaCoCo 和前端 `check` 通过；Media/Job 资源级 404 隔离仍按依赖任务继续验证，不在 T2 虚构尚不存在的资源服务。

## T2F：交付登录与会话前端验收面

- [x] 提供真实的登录、页面刷新会话恢复和退出操作，使用同源 HttpOnly Cookie；CSRF 值只保留在页面内存，不把密码或会话令牌写入浏览器存储。
- [x] 覆盖加载、错误凭据、限流/依赖不可用和已登录状态；错误不区分账号是否存在，失败后清空密码。
- [x] 建立 M1 响应式工作台外壳和视觉 Token，在未交付上传能力前明确显示“下一能力”，不渲染可点击的伪功能。
- **验收标准**：用户能通过页面调用真实 Session API 登录、刷新恢复和安全退出；320px 与桌面布局可用；键盘标签/焦点清晰；无 Console error/warning。
- **验证**：先写失败的 API/组件测试；`client\npm.cmd run check`；真实浏览器联调 GET/POST/DELETE session 网络请求及桌面/手机视口。
- **预计文件**：`client/src/api/session.ts`、`client/src/App.vue`、全局样式和测试。
- **依赖/规模**：T2 / S。
- **完成记录（2026-10-29）**：按 RED/GREEN 新增 Session API client 与登录/会话恢复/退出工作台；16 项前端测试和 `npm run check` 全部通过，Statements 95.77%、Branches 92.53%、Functions 100%、Lines 98.36%。在 Compose 内网依赖和全新 Flyway schema 上完成真实浏览器 POST 登录、刷新 GET 恢复、CSRF DELETE 退出；桌面及 375 x 812 手机视口的可访问 DOM、布局和零 Console error/warning 已验证。

## T3：实现 Job 领域模型、统一状态机与前进迁移

- [x] 为 `AnalysisJob`、`JobStageAttempt`、`JobEvent` 建立领域类型、稳定错误分类和合法迁移表。
- [x] 实现唯一的 `JobTransitionService`：校验前置状态、version CAS、阶段输入指纹，并在同一事务写 Job、阶段和事件/Outbox。
- [x] 用新迁移补足 V1 为 M1 所需的字段/约束/索引；不得回写或重命名 V1。
- **验收标准**：所有合法边有单测；非法边和终态推进被拒绝；并发旧 version 只有一个成功；Controller/Consumer/Mapper 不能绕过迁移入口更新状态。
- **验证**：状态机参数化测试、CAS 集成测试、ArchUnit；`server\mvnw.cmd test`；`server\mvnw.cmd verify -Pintegration`。
- **预计文件**：analysis domain/application/port、MyBatis-Plus persistence Adapter、V2+ 迁移、状态机/集成测试。
- **依赖/规模**：T1 / M。
- **完成记录（2026-10-29）**：按 ADR-0003 实现完整合法迁移表、稳定错误分类与唯一 `JobTransitionService`；MyBatis-Plus Adapter 以 `status + version` CAS 裁决并发，并在同一 Spring 事务中写 Job、可选阶段记录和事件/Outbox。V3 仅以前进方式增加状态/错误/事件游标约束、事件发生时间及 owner 查询索引。状态机条件变异会触发 34 项测试失败，证明合法/非法边断言有效；后端 90 项快速测试、全新 MySQL schema 上 8 项集成测试、ArchUnit 与 JaCoCo 门均通过。

## T4：幂等创建 Job 与事务 Outbox

- [x] 实现创建 Job application service，在单事务原子写入 Job、初始阶段/事件和待发布 Outbox。
- [x] 以数据库唯一约束裁决 `ownerId + idempotencyKey` 和逻辑执行冲突，校验规范化请求指纹。
- [x] 提供提交与查询端点，Controller 只调用 application service，响应使用 `202 Accepted` 和稳定 status/events URL。
- **验收标准**：并发同 key/同请求只产生一个 Job；同 key/不同请求稳定冲突；数据库提交失败不留下孤立 Outbox，成功提交一定有待发布消息。
- **验证**：先写并发失败测试；聚焦 application 测试；MySQL 集成测试；`server\mvnw.cmd test`。
- **预计文件**：analysis application service、web Controller/DTO、persistence Adapter、幂等集成测试。
- **依赖/规模**：T2、T3 / M。
- **完成记录（2026-10-30）**：新增 owner-scoped Job 提交、详情和分页查询；目标文本经 NFKC 与空白折叠后参与 SHA-256 请求指纹和逻辑分析键。创建事务原子写入 `CREATED` Job、`SUBMISSION` 完成阶段、首个 `JOB_CREATED` 事件及 `PENDING` Outbox；数据库唯一约束裁决同 key 和同逻辑分析的并发竞争。相同 key/相同请求返回既有 Job，相同 key/不同请求返回稳定 `409 IDEMPOTENCY_KEY_REUSED`，跨用户媒体/Job 均返回不泄露存在性的 404。后端 103 项快速测试、全新 MySQL schema 上 13 项集成测试、ArchUnit 与 JaCoCo 门均通过。

### Checkpoint A：契约与真源

- [x] M0 已正式退出。
- [x] M1 接口合同已由 T1 冻结。
- [x] Job 创建、状态迁移、阶段和 Outbox 的单事务/CAS 行为有真实 MySQL 证据。
- [x] `server\mvnw.cmd test` 与相关 integration 测试通过。

## T5：声明 RabbitMQ 拓扑并实现可靠 Outbox Relay

- [x] 按 ADR-0001 声明持久化 direct exchange、主队列、TTL retry queue 和 DLQ，配置 publisher confirm/return。
- [x] Relay 批量抢占到期 PENDING 记录，发布精简 envelope；仅在 confirm 成功后标记已发布，失败/超时/不可路由时有限退避重试。
- [x] 多实例 Relay 不会同时发布同一轮记录；进程在数据库提交后或 confirm 前退出时，记录仍可再次发布。
- **验收标准**：成功、nack、return、confirm timeout 均有确定状态；消息体不含视频/完整上下文/模型输出；Relay 重发被视为正常语义。
- **验证**：RabbitMQ + MySQL 集成测试；停止/重启 Relay 的故障场景；`server\mvnw.cmd verify -Pintegration`。
- **预计文件**：messaging 配置、Outbox relay Adapter、配置项、集成测试。
- **依赖/规模**：T4 / M。
- **完成记录（2026-10-31）**：声明持久化主 exchange/queue、TTL retry exchange/queue 与 DLX/DLQ，开启 correlated publisher confirm 和 mandatory return。V4 以前进迁移为 Outbox 增加租约及受限发布状态；Relay 使用 `FOR UPDATE SKIP LOCKED` 批量抢占，只有 ACK confirm 才写 `PUBLISHED`，nack、return、超时与发布异常进入有上限的指数退避，耗尽后记录 `DEAD_LETTERED`。真实 MySQL/RabbitMQ 测试证明多实例只有一个租约、过期租约可由重启实例回收、旧租约不能提交新持有者结果，消息体只有冻结的六字段 envelope。

## T6：实现幂等 Consumer、Redisson 锁与阶段 CAS

- [x] Consumer 手动 ACK，先按 jobId 回查 MySQL；Redisson 锁只降低同 Job 并发冲突。
- [x] 用期望状态、version CAS 和 `jobId + stage + inputFingerprint` 唯一约束取得执行资格；重复/乱序消息成为成功 no-op。
- [x] 实现不调用模型的 M1 假任务处理器，记录阶段与事件；业务事务成功后 ACK，异常按稳定分类进入有限重试或失败路径。
- **验收标准**：同一消息并发/串行重投都不会重复推进阶段；锁丢失时数据库仍防重；ACK 前进程退出后重投可安全收敛。
- **验证**：重复消息、并发 Consumer、锁失效和 ACK 边界集成测试；`server\mvnw.cmd verify -Pintegration`。
- **预计文件**：analysis handler、Rabbit consumer Adapter、Redisson 配置/Adapter、集成测试。
- **依赖/规模**：T5 / M。
- **完成记录（2026-10-31）**：新增手动 ACK Rabbit listener 与不调用模型的 `AnalysisJobExecutionHandler`。Handler 先回查 MySQL，再以 Redisson 短租约降低同 Job 并发；Redis 锁不可用时降级继续，以统一状态迁移的期望状态/version CAS 和 `jobId + CONSUMPTION + inputFingerprint` 唯一约束裁决。重复、乱序及业务提交后 ACK 前退出的重投成为成功 no-op。锁忙经 TTL retry queue 有限重试；合同错误、Job 不可执行和重试耗尽经 confirm 后进入 DLQ，并由 V5 `analysis_message_failure` 保存哈希键和安全错误摘要，不保存原始正文。真实 MySQL/Redis/RabbitMQ 测试覆盖并发及串行重投、锁竞争恢复、非法 schema 审计和 DLQ 原消息。

## T7：实现 Job 查询、持久化 SSE、取消与失败语义

- [x] 提供 owner-scoped Job 详情/分页列表和事件补拉；不存在与无权访问采用不泄露资源存在性的统一语义。
- [x] SSE 以 MySQL `job_sequence` 为 event id，先补拉缺口再订阅热事件；断线和应用重启后支持 `Last-Event-ID` 恢复。
- [x] 取消端点通过状态机处理 CREATED/QUEUED 与运行中状态；失败响应展示稳定 error code、retryable 和安全摘要。
- **验收标准**：刷新或重连不丢、不乱序、不重复展示事件；终态 SSE 正常结束；取消竞态由 CAS 裁决；跨用户无法读取 Job 或事件。
- **验证**：MockMvc 合同测试、MySQL/SSE 集成测试、取消竞态测试；`server\mvnw.cmd test`。
- **预计文件**：analysis query/application service、web Controller/SSE、事件发布 Adapter、测试。
- **依赖/规模**：T6 / M。
- **完成记录（2026-11-02）**：V6 为 Outbox/事件表增加事件发生时的稳定 Job 状态、阶段、安全公开消息和流终止标记；owner-scoped 应用服务只按认证 owner 查询 Job 与递增事件。SSE 使用 MySQL `job_sequence` 作为 `id`，无游标从 1 回放、有游标只查 `> N`，250ms 增量查询消除进程内热事件丢失和补拉/订阅间隙，15 秒 comment 心跳不落库；M1 的 `JOB_READY` 及取消/失败终态发送后正常关闭。取消接口全部经统一状态机与 version CAS，QUEUED/Consumer 竞态会重读最新 owner 快照并收敛到 `CANCELED` 或 `CANCEL_REQUESTED`。MockMvc 覆盖 CSRF、SSE 游标/终态、越权 404 和安全失败字段；真实 MySQL 覆盖事件恢复、跨用户隔离和取消竞态。完整验证在隔离端口与全新 `videopilot_t7_final` schema 上通过 148 项单元/架构测试、27 项集成测试和 JaCoCo 80% 门。

## T8：交付假任务工作台纵向闭环

- [x] 在已完成的 T4F/T7F 操作面上用测试媒体记录跑通“提交 -> 实时进度 -> 刷新补拉 -> 完成/取消/失败”。
- [x] 以桌面和手机视口检查关键布局、键盘主路径、可访问名称、Console 和 REST/SSE 网络请求。
- [x] 记录页面结果与 MySQL 持久化事件一致，证明前端没有本地计时或假数据状态。
- **验收标准**：页面不根据本地计时伪造进度；刷新后状态来自服务端；失败和取消可访问且明确；无 Console error。
- **验证**：`client\npm.cmd run check`；真实浏览器 + RabbitMQ/MySQL 冒烟验收并记录网络/Console 证据。
- **预计文件**：浏览器验收记录、必要的前端缺陷修复和测试。
- **依赖/规模**：T7F / S。
- **完成记录（2026-11-02）**：在 T4/T7 已冻结契约之上交付 Job API client 与工作台，使用服务端 links 导航、一次提交意图一个 `Idempotency-Key`、显式 `Last-Event-ID` 和 400/800/1600ms 有界重连；页面只呈现服务端 Job/事件，不用本地计时推断进度。真实浏览器连接隔离 MySQL、RabbitMQ 和新后端，跑通提交至 READY 的 7 条持久化事件、刷新全量回放、运行中取消收敛到 CANCELED，以及持久化 FAILED fixture 的安全错误展示；READY 页面和 MySQL 均为 version=6、event_sequence=7，事件顺序完全一致。桌面 1440x900、手机 375x812、键盘主路径、可访问名称/区域和 live region 通过，最终 Console error/warning 为 0。联调还修复了 Consumer 对 `CANCEL_REQUESTED` 仅 ACK 不收敛，以及 Spring Security SSE async dispatch 丢失认证两个缺陷。失败 fixture 只验收失败读取/展示契约，不宣称已实现后续真实媒体失败生产链路。完整证据见 `tasks/M1/T8_BROWSER_ACCEPTANCE.md`。

### Checkpoint B：可靠假任务

- [x] Outbox -> RabbitMQ -> Consumer -> MySQL -> SSE 全链路可运行。
- [x] 重复消息、断线补拉、取消和失败场景通过。
- [x] 后端快速/相关集成测试和前端 check 通过。

## T9：实现 MediaAsset 与 MinIO upload session

- [x] 实现 MediaAsset domain/application/port 和 MyBatis-Plus Adapter；对象 key 只能由服务端生成并绑定 owner/mediaId。
- [x] 实现 MinIO Adapter 和 upload session 的创建、完成、过期/中止语义；签名权限仅覆盖目标对象且短期有效。
- [x] 以客户端 SHA-256、服务端对象元数据和数据库唯一约束处理重复上传，不信任文件名/MIME 作为真实性依据。
- **验收标准**：视频可流式上传且应用不持有 MinIO 密钥的前端副本；完成前不能提交分析；重复完成幂等；跨用户不能读取、完成或覆盖对象。
- **验证**：MinIO + MySQL 集成测试；大于内存阈值的流式上传测试；owner 隔离测试；`server\mvnw.cmd verify -Pintegration`。
- **预计文件**：media domain/application/port、MinIO/persistence Adapter、迁移/配置、集成测试。
- **依赖/规模**：T2 / M。
- **完成记录（2026-11-02）**：新增 owner-scoped `MediaAsset`/upload session 领域与应用层、MyBatis-Plus 持久化、V7 前进迁移和 MinIO multipart Adapter。对象 key 只由服务端按 owner/mediaId 生成，预签名 URL 只覆盖目标 part 且不超过 session TTL；完成前核对服务端分片清单、总字节数和对象元数据，重复创建/完成幂等。过期 session 既在访问时释放，也由有界定时批次回收；跨 owner 统一不可见。真实 MinIO/MySQL 测试以约 9 MiB 两分片流式上传验证完成、重复、隔离、中止及未 READY 禁止分析。客户端 SHA-256 仍是候选声明，可信内容校验和进入 READY 由 T10 完成；T9F 前端上传面保持独立未提前实现。

## T9F：交付视频选择、分片上传与进度前端验收面

- [x] 实现 Media upload session API client、文件选择和分片上传；文件名/大小/MIME 仅作提示，服务端仍负责可信校验。
- [x] 显示真实字节进度、分片重试/过期/中止状态；短期预签名 URL 只在请求期间使用，不持久化 MinIO 凭据。
- [x] 用小 fixture 和超过客户端限制的文件覆盖成功、失败与取消，页面保持键盘可操作和移动端可读。
- **验收标准**：登录用户能通过页面把视频上传至服务端限定的 object key；刷新或错误不会错误宣称已完成；跨用户信息不进入请求。
- **验证**：API/组件失败测试；`client\npm.cmd run check`；真实浏览器检查 MinIO 分片网络请求和 Console。
- **预计文件**：media API client、upload feature/component、样式和测试。
- **依赖/规模**：T9 / M。
- **完成记录（2026-11-03）**：新增 owner-scoped Media upload API client、4 MiB 增量 SHA-256、XHR multipart 直传与真实字节进度。每个分片失败后重新申请短期签名并最多尝试三次，活动请求支持 AbortSignal，中止/过期/失败不会进入完成态；预签名 URL 和 MinIO 凭据不进入任何浏览器存储。上传面嵌入现有工作台，完成后只展示服务端 `UPLOADED` 和媒体 ID，并明确等待 T10，不自动接入 Job。47 项前端测试与四项覆盖率门、生产构建全部通过；真实浏览器跑通小夹具和约 82 MiB multipart 上传、哈希中止、刷新、桌面/375 x 812、键盘及零 Console 错误/警告。证据见 `tasks/M1/T9F_BROWSER_ACCEPTANCE.md`。

## T10：实现 FFprobe 校验并连接真实媒体与 Job

- [x] 用受控进程 Adapter 执行 FFprobe，限制超时、输出大小和并发，校验容器/视频流/时长并解析为供应商无关结果。
- [x] 对象读取到受控临时位置或安全流接口，所有成功/失败/取消路径都清理资源并销毁子进程。
- [x] 上传完成后把可信媒体元数据或稳定失败事实写入 MySQL；只有校验通过且归属匹配的 MediaAsset 可创建 Job，损坏/非视频文件得到稳定不可重试错误。
- **验收标准**：有效 fixture 得到 duration 等元数据；损坏文件、无视频流、超时、缺少 FFprobe 均可诊断；失败事实可在刷新后安全读取，且不遗留可分析媒体或临时文件。
- **验证**：FFprobe Adapter 单测 + 真实小视频集成测试；进程超时/清理测试；`server\mvnw.cmd test`。
- **预计文件**：media probe port/application service、FFprobe Adapter、配置、测试 fixture/测试。
- **依赖/规模**：T3、T9 / M。
- **完成记录（2026-11-03）**：新增 MySQL 驱动的有界媒体探测 worker，以 version CAS 抢占 `UPLOADED` 或陈旧 `PROBING` 资源；MinIO 对象复制到受控临时文件后先计算服务端 SHA-256，再由限时、限输出、限并发的 FFprobe Adapter 提取可信容器、视频编码、尺寸和时长。成功事实进入 `READY`，稳定失败事实进入 `FAILED`，所有路径清理临时文件并终止子进程；Job 入口继续只接受归属匹配的 `READY`。Docker/Compose 与 CI 安装 FFmpeg，默认本地配置保持显式关闭探测 worker。186 项后端测试及全新 V1-V8 schema 上的真实 MySQL/MinIO/FFprobe 集成测试通过。

## T10Q：交付 owner-scoped 媒体列表与详情查询

- [x] 实现 `GET /api/v1/media-assets?page&pageSize`，从 MySQL 分页读取当前 owner 的持久化媒体，并按 `createdAt DESC, mediaId DESC` 稳定排序。
- [x] 统一列表与 `GET /api/v1/media-assets/{mediaId}` 的响应投影，返回可信状态、时长和可选安全失败事实，不返回 ownerId、objectKey、临时路径或探测命令。
- [x] 用双用户、混合状态和分页边界测试证明资源不可枚举；跨 owner 详情继续返回不泄露存在性的 404，未完成/已中止 upload session 不进入列表。
- **验收标准**：刷新页面后可从服务端找回当前用户全部已持久化媒体；列表和详情事实一致；排序稳定且无跨用户泄露。
- **验证**：application/controller 测试；MySQL owner 隔离与分页集成测试；`server\mvnw.cmd test`。
- **预计文件**：media query application service/port、persistence Adapter、媒体响应合同及测试。
- **依赖/规模**：T10 / S。
- **完成记录（2026-11-03）**：新增独立的 owner-scoped media query Port/Application，列表和详情共用 `MediaAssetView` 投影。MySQL 查询排除 `UPLOAD_PENDING`，按 `created_at DESC, media_id DESC` 稳定分页；Controller 只从认证 principal 获取 owner，分页在 HTTP 与应用层双重限制为 1..100。响应保留可信状态、时长和安全失败事实，不公开 owner/object key/临时路径/命令。192 项后端测试和真实 MySQL 双用户、同时间戳排序、分页、未完成上传排除及跨 owner 404 集成场景通过。

## T10F：交付媒体资源列表与校验状态前端验收面

- [x] 登录或刷新后读取 owner-scoped 分页媒体列表；上传完成的资源立即进入列表，并展示 `UPLOADED/PROBING/READY/FAILED`、可信时长和安全错误摘要，不在客户端推断 READY。
- [x] 只对当前可见的 `UPLOADED/PROBING` 资源做有界轮询并在终态停止；提供加载、空列表、分页、局部刷新和服务不可用状态，刷新页面能从服务端恢复。
- [x] READY 资源接入已完成的 T4F 提交表单；FAILED 和非终态资源禁用分析操作。用有效视频、损坏文件、跨用户不可见和服务不可用覆盖页面状态及恢复行为。
- **验收标准**：用户能看到此前上传的资源并选择 READY 媒体提交 Job；页面与 MediaAsset 真源一致，READY 前不能提交，错误可理解且不泄露 FFprobe 命令/路径。
- **验证**：组件/API 测试；`client\npm.cmd run check`；真实浏览器核对媒体请求和状态转换。
- **预计文件**：media list/detail API、资源列表与状态组件、T4F 媒体选择接线、样式和测试。
- **依赖/规模**：T9F、T10Q / M。
- **完成记录（2026-11-03）**：新增 owner-scoped media list/detail client 和媒体资源面，上传完成后立即合并 `UPLOADED`，只对当前页非终态资源做 2 秒有限频率详情轮询并在 `READY/FAILED` 停止。页面提供加载、空态、分页、刷新、服务不可用保留旧数据和安全失败摘要；仅 READY 显示选择操作并接入 T4F，移除手填测试 mediaId。52 项前端测试、四项覆盖率门和生产构建通过；真实浏览器跑通有效视频 `UPLOADED -> READY -> Job READY`、损坏视频 FAILED、刷新恢复、服务中断恢复、双账号隔离、375 x 812 和零 Console error/warning。证据见 `tasks/M1/T10F_BROWSER_ACCEPTANCE.md`。

## T4F：交付 Job 提交与查询前端验收面

- [x] 对 READY 且归属当前用户的媒体提供目标/模式提交表单，按一次提交意图生成并复用 `Idempotency-Key`。
- [x] 提供 owner-scoped Job 列表/详情 API client，统一处理重复提交、同 key 异请求、媒体未就绪和认证过期。
- [x] 成功提交后使用服务端 links 导航到 Job，不由客户端拼装或推断任务状态。
- **验收标准**：用户能从已校验媒体提交 Job 并刷新读取；重复点击/网络重试不创建第二个逻辑任务；请求不携带 ownerId。
- **验证**：API/组件失败测试；`client\npm.cmd run check`；浏览器核对 202、幂等 Header 和恢复请求。
- **预计文件**：jobs API client、submit/list/detail feature 和测试。
- **依赖/规模**：T4 / S；真实媒体选择接线由 T10F 完成。
- **完成记录（2026-11-02）**：为 T8 使用 M1 测试媒体 ID 提供临时提交入口，API client 覆盖创建、列表、详情和取消；提交意图生成并在网络重试期间复用幂等键，写请求携带 CSRF 且从不携带 ownerId。成功后把服务端 `links.self` 写入 URL hash 并据此恢复详情，不在客户端拼 Job URL。T9F/T10F 完成后由真实媒体选择面替换测试媒体 ID 输入，不改变本任务 API 契约。

## T7F：交付 Job 状态、SSE、取消与失败前端验收面

- [x] Job 详情显示服务端状态、阶段、持久化事件、稳定失败摘要和合法取消操作；不以本地计时生成进度。
- [x] SSE 断线采用有界退避并携带最后 event id，先补拉再接热流；终态关闭连接并对屏幕阅读器播报关键变化。
- [x] 会话过期、越权 404、可重试/不可重试失败和取消竞态均有明确页面语义。
- **验收标准**：刷新/重连后事件不丢、不乱序、不重复展示；取消结果由服务端 CAS 裁决；跨用户 URL 不泄露存在性。
- **验证**：API/组件/SSE 测试；`client\npm.cmd run check`；真实浏览器核对 EventSource/补拉、Console 和可访问树。
- **预计文件**：job detail/timeline feature、SSE client、样式和测试。
- **依赖/规模**：T7、T4F / M。
- **完成记录（2026-11-02）**：详情页展示服务端状态、阶段、sequence 事件、稳定失败码/摘要和服务端允许的取消链接。fetch-based SSE client 支持显式 `Last-Event-ID`、跨连接去重、事件 id/sequence 校验、异常格式拒绝和有界退避；刷新终态页面仍从 sequence 0 回放持久化时间线。认证过期回到登录面，404 使用统一安全错误，取消结果以重读服务端快照为准，关键状态通过 `aria-live` 播报。组件/API 测试和真实浏览器场景均通过。

## T11：交付真实上传与进度页面纵向闭环

- [x] 组合已完成的 T2F/T9F/T10F/T4F/T7F，形成登录、视频选择、上传、媒体列表/校验、选择 READY 资源、分析提交和 Job 进度的一条连续用户路径。
- [x] 用真实浏览器和 Compose 依赖验证成功、刷新恢复、上传失败和分析失败，不在此任务集中补造尚未随薄片交付的页面。
- [x] 刷新后媒体列表与 Job 均可恢复；Job 页面展示真实媒体名、时长、状态和可补拉进度，网络请求中不存在可由用户切换的 owner。
- **验收标准**：用户可从空页面完成“登录 -> 上传 -> 在资源列表观察校验 -> 选择 READY 媒体提交 -> 查看进度”；刷新不丢媒体或 Job；上传和分析错误均可恢复或明确终止。
- **验证**：前端单测；`client\npm.cmd run check`；真实浏览器 + MinIO/RabbitMQ/MySQL 冒烟验收。
- **预计文件**：端到端验收记录、必要的跨 feature 导航和缺陷修复。
- **依赖/规模**：T8、T9F、T10F / S。
- **完成记录（2026-11-03）**：Job 工作台在创建成功和 URL 恢复时均绑定 owner-scoped 媒体详情，展示真实文件名、可信时长、状态和 ID；媒体详情暂时不可用时保留 Job 与持久化事件，不把关联查询失败误判为 Job 丢失。54 项前端测试、覆盖率门、类型检查、Lint 和生产构建通过。真实浏览器在 Compose 环境完成登录、有效视频 `UPLOADED -> READY`、选择提交、Job 事件展示和刷新恢复；损坏视频稳定进入 FAILED，分析失败 fixture 可安全展示终态与事件，375 x 812 无横向溢出且 Console 无 error/warning。详细证据与 fixture 边界见 `tasks/M1/T11_BROWSER_ACCEPTANCE.md`。

## T12：加入 Redis active key 并证明可重建

- [x] active key 只作为 owner + analysisKey 的快速命中和短期状态缓存，设置明确命名、TTL 与删除时机。
- [x] 缓存 miss/Redis 不可用时回查 MySQL；启动或按需可从非终态 Job 重建，不因缓存丢失创建重复 Job。
- [x] SSE 热事件可使用 Redis 加速，但历史补拉、顺序和终态始终以 MySQL 为准。
- **验收标准**：清空 Redis 后重复提交仍返回同一逻辑执行；Job 查询/SSE 补拉正常；Redis 故障不改变数据库事实。
- **验证**：Redis flush/restart 集成场景；重复提交并发测试；`server\mvnw.cmd verify -Pintegration`。
- **预计文件**：analysis cache port、Redis Adapter、配置、集成测试。
- **依赖/规模**：T6、T7、T10 / S。
- **完成记录（2026-11-03）**：新增 owner + analysisKey 正向 key 与 jobId 反向 key，默认 TTL 30 分钟；创建、幂等命中和状态迁移后按需写入/刷新，READY 及其他终态删除。缓存命中仍由 owner-scoped MySQL 复核；缓存 miss、脏值、Redis 清空或不可用均回查 MySQL，非终态按需重建，数据库唯一约束继续裁决并发。SSE 历史补拉、顺序与终态未改用 Redis，继续以 MySQL 持久化事件为真源。202 项单元测试、35 项真实依赖集成测试和覆盖率门通过。

### Checkpoint C：真实上传

- [x] MinIO 上传、FFprobe、媒体元数据和 Job 提交端到端通过。
- [x] Redis 清空后仍能从 MySQL 恢复正确行为。
- [x] 两个用户的媒体、对象和 Job 全链路隔离。

## T13：执行 M1 可靠性与权限验收矩阵

- [x] 自动化并发重复提交、同 key 异请求、重复/乱序 MQ、Consumer 提交前退出、提交后 ACK 前退出。
- [x] 自动化应用重启后 Job/事件找回、SSE 重连、Redis 清空、取消竞态和失败展示。
- [x] 自动化双用户枚举/读取/取消/上传完成越权测试，并检查消息和日志不含视频、凭据或完整上下文。
- **验收标准**：Roadmap M1 四项退出门全部有可重复证据；同一幂等请求的逻辑 Job 数和阶段执行次数均为 1；越权请求均不泄露资源存在性。
- **验证**：`server\mvnw.cmd verify -Pintegration`；`client\npm.cmd run check`；Compose 环境端到端验收脚本。
- **预计文件**：M1 integration/fault tests、验收脚本、必要 fixture、`tasks/M1/ACCEPTANCE.md`。
- **依赖/规模**：T8、T11、T12 / M。
- **完成记录（2026-11-03）**：补齐 Consumer 提交前异常、提交后 ACK 失败、READY 后乱序旧消息、跨 owner 上传完成和取消，以及真实 Spring 应用关闭/Redis 清空/重启恢复测试；与既有并发幂等、MQ 重投、SSE 游标、取消竞态、双用户查询和浏览器失败展示证据汇总成可复现矩阵。隔离 Docker 网络上 205 项单元/架构测试和 37 项真实依赖集成测试通过；前端 54 项测试、类型/Lint/覆盖率/构建门通过。详细映射见 `tasks/M1/T13_ACCEPTANCE_MATRIX.md`。

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
