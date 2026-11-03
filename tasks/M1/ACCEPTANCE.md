# M1 验收基线：上传与异步 Job 最小闭环

状态：T1 合同冻结（2026-10-29）。本文定义 M1 后续实现必须满足的外部行为，不代表运行时能力已经交付。

## 1. 冻结范围

M1 交付登录与资源归属、MinIO multipart 上传、FFprobe 校验、可靠异步 `AnalysisJob`、可恢复 SSE 进度、取消和失败展示。M1 不调用 LLM、ASR、OCR 或 Embedding，不生成 `VideoContext`、`VideoSegment` 或 `VideoChunk`。

本阶段冻结以下选择：

- API 基路径为 `/api/v1`，JSON 字段使用 camelCase，枚举值使用 `UPPER_SNAKE_CASE`。
- 本地/单服务器账号不开放自助注册。账号由受控初始化流程提供，验收至少创建两个隔离用户。
- 登录使用服务端会话。会话 cookie 为 HttpOnly、SameSite=Strict；非本地环境必须使用 Secure。除创建会话外，所有改变状态的 API 都要求 `X-CSRF-Token`。
- 单个视频最大 5 GiB，使用 MinIO multipart upload session；对象 key 和 upload id 由服务端生成，前端只获得短期、单分片范围的预签名 URL。
- M1 假任务走 `CREATED -> QUEUED -> CONSUMING -> PROBING -> CONTEXT_BUILDING -> INDEXING -> READY`，在 `READY` 停止。该路径只验证可靠调度，不伪造后续阶段产物。
- Job 事件以 MySQL 中从 1 开始、按 Job 单调递增的 `sequence` 为唯一可恢复游标。Redis 和进程内广播均不是补拉真源。

## 2. 通用 HTTP 合同

### 2.1 请求、认证与归属

- 除 `POST /api/v1/sessions` 外，所有端点必须有有效会话。
- `ownerId` 不出现在创建、查询或取消请求中，只能来自服务端认证上下文。
- 单资源端点对于“不存在”和“不属于当前用户”统一返回 `404 RESOURCE_NOT_FOUND`，不得泄露资源是否存在。
- 列表只返回当前用户资源，默认按 `createdAt DESC`；`page` 从 1 开始，默认 `pageSize=20`，最大 100。
- 请求体、路径参数、查询参数、Header 和 MinIO/FFprobe 响应都在边界校验。内部 application/domain 调用使用已校验类型。

### 2.2 统一错误

所有 REST 错误沿用既有结构：

```json
{
  "code": "IDEMPOTENCY_KEY_REUSED",
  "message": "The idempotency key was reused with a different request.",
  "retryable": false,
  "requestId": "request-123"
}
```

| HTTP | 稳定错误码 | 语义 |
| ---: | --- | --- |
| 400 | `INVALID_REQUEST` | JSON、参数、Header、文件元数据或游标不合法 |
| 401 | `AUTHENTICATION_REQUIRED` | 缺少或失效会话 |
| 401 | `INVALID_CREDENTIALS` | 登录凭据错误；不区分用户名不存在和密码错误 |
| 403 | `CSRF_REJECTED` | 状态变更请求缺少或使用错误 CSRF Token |
| 429 | `RATE_LIMITED` | 登录失败次数超过共享窗口限制，可在窗口结束后重试 |
| 404 | `RESOURCE_NOT_FOUND` | 资源不存在或不属于当前用户 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 同一幂等键绑定了不同请求指纹 |
| 409 | `UPLOAD_EXPIRED` | upload session 已过期或不能继续 |
| 409 | `MEDIA_NOT_READY` | 媒体尚未通过 FFprobe，不能创建 Job |
| 409 | `JOB_NOT_CANCELABLE` | Job 当前状态不能取消 |
| 409 | `CONFLICT` | 其他版本/CAS/资源状态冲突 |
| 503 | `DEPENDENCY_UNAVAILABLE` | 必需依赖暂时不可用，可按 `retryable` 决定重试 |
| 500 | `INTERNAL_ERROR` | 未分类服务端失败，不返回内部异常详情 |

### 2.3 分页响应

```json
{
  "data": [],
  "pagination": {
    "page": 1,
    "pageSize": 20,
    "totalItems": 0,
    "totalPages": 0
  }
}
```

## 3. REST 资源合同

### 3.1 会话

| Method | Path | 输入 | 成功 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/sessions` | `LoginRequest` | `201` + `SessionResponse` + 会话 cookie |
| `GET` | `/api/v1/sessions/current` | 无 | `200` + `SessionResponse` |
| `DELETE` | `/api/v1/sessions/current` | `X-CSRF-Token` | `204`，重复退出仍成功 |

`LoginRequest` 只包含 `username/password`。`SessionResponse` 只暴露 `userId/username/csrfToken/expiresAt`，不暴露密码哈希、角色内部字段或 cookie 值。

### 3.2 上传与媒体

| Method | Path | 输入 | 成功 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/media-uploads` | 文件名、字节数、SHA-256、声明 MIME | `201` + upload session |
| `POST` | `/api/v1/media-uploads/{uploadId}/parts` | `partNumber`（1..10000） | `201` + 该分片短期预签名 URL |
| `POST` | `/api/v1/media-uploads/{uploadId}/completion` | 有序 `partNumber/etag` 列表 | `202` + `MediaAssetResponse` |
| `DELETE` | `/api/v1/media-uploads/{uploadId}` | 无 | `204`，重复中止仍成功 |
| `GET` | `/api/v1/media-assets` | `page/pageSize` | `200` + owner-scoped `PageResponse<MediaAssetResponse>` |
| `GET` | `/api/v1/media-assets/{mediaId}` | 无 | `200` + `MediaAssetResponse` |

约束：

- 服务端不信任扩展名和 MIME；只有 FFprobe 确认存在视频流且元数据合法后，媒体才进入 `READY`。
- multipart 完成请求必须覆盖已上传分片，分片号唯一且升序；重复完成同一 upload session 返回同一媒体事实，不重复创建对象或媒体。
- 预签名 URL 只能写当前用户 session 对应 object key 的指定 part，过期时间不超过 upload session。
- `sha256` 为 64 位十六进制；对象完成后必须与受信校验结果一致。失败对象不能创建分析 Job。
- 媒体列表只返回当前登录用户已经完成 upload session 并持久化的 `MediaAsset`，按 `createdAt DESC, mediaId DESC` 稳定排序；未完成/已中止的 upload session 不混入资源列表，客户端不能通过 owner 参数切换范围。
- `MediaAssetResponse` 在 T10 以可选 `failure` 字段做加法扩展；结构为 `code/retryable/summary`。校验失败事实与可信元数据必须写入 MySQL，使列表、详情和刷新恢复得到同一结果，且不得暴露对象 key、临时路径或 FFprobe 命令。

> 2026-11-03 补充说明：集合查询和可选 `failure` 是 T1 合同的向后兼容增补，用于关闭“上传后刷新无法找回资源”的验收缺口；既有路径、字段和错误语义均不改变。实现任务为 T10Q，前端消费任务为 T10F。

### 3.3 AnalysisJob

| Method | Path | 输入 | 成功 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/analysis-jobs` | `Idempotency-Key` + `CreateAnalysisJobRequest` | `202` + `AnalysisJobResponse` + `Location` |
| `GET` | `/api/v1/analysis-jobs` | `page/pageSize/status?` | `200` + 分页 Job |
| `GET` | `/api/v1/analysis-jobs/{jobId}` | 无 | `200` + `AnalysisJobResponse` |
| `POST` | `/api/v1/analysis-jobs/{jobId}/cancellations` | CSRF Token | `202` + 最新 `AnalysisJobResponse` |
| `GET` | `/api/v1/analysis-jobs/{jobId}/events` | `Accept: text/event-stream`、可选 `Last-Event-ID` | `200` SSE |

`CreateAnalysisJobRequest` 只包含 `mediaId/goal/mode`。`ownerId`、`pipelineVersion`、`analysisKey`、`jobId` 和请求指纹均由服务端确定。M1 的 `mode` 只有 `STANDARD`。

## 4. Idempotency-Key 合同

- `POST /analysis-jobs` 强制要求 `Idempotency-Key`，格式为 1..128 个 ASCII 字符 `[A-Za-z0-9._:-]`。
- 幂等作用域为当前 owner；客户端为一次提交意图生成一次，并在网络/超时重试时复用，服务端不得为重试重新生成。
- 服务端规范化请求后计算指纹，并通过 MySQL 唯一约束原子占有 key，禁止 `SELECT` 后 `INSERT` 的竞态实现。
- 同 owner、同 key、同请求指纹始终返回既有 Job，HTTP 仍为 `202`；不创建新 Job、不新增高成本阶段、不重复写初始事件。
- 同 owner、同 key、不同请求指纹返回 `409 IDEMPOTENCY_KEY_REUSED`。
- 不同 key 但相同 `analysisKey` 返回既有逻辑执行，仍不创建第二个 Job。版本变化是否产生新逻辑执行继续遵守 ADR-0002。
- 幂等记录至少保留到 Job 和所有可能的 Retry/DLQ/Replay 路径不再能重投；M1 不使用短于消息寿命的 TTL 删除数据库事实。

## 5. SSE 合同

每条持久化事件编码为：

```text
id: 7
event: job-event
data: {"jobId":"...","sequence":7,"eventType":"STAGE_COMPLETED",...}

```

- `id` 是十进制 `job_sequence`，与 JSON `sequence` 完全一致；不使用 Redis stream id 或进程时间戳。
- 未提供 `Last-Event-ID` 时，从 sequence 1 回放；提供 N 时，只发送 sequence `> N` 的事件。
- 非数字、负数或大于当前 Job sequence 的游标返回 `400 INVALID_REQUEST`。
- 连接建立时必须消除“数据库补拉”和“热订阅”之间的竞态，客户端观察到的 sequence 严格递增且无缺口；已见事件不得重复渲染。
- 心跳使用 SSE comment，不分配 sequence，也不写 MySQL；建议间隔 15 秒。
- 终态事件发送后服务端正常结束连接。断线、刷新或应用重启后，客户端使用最后已处理 id 重连。
- SSE 鉴权和 owner 隔离与 REST 查询相同；不得通过 jobId 订阅其他用户事件。

## 6. RabbitMQ 消息合同

M1 只定义一个业务消息：`EXECUTE_JOB`，schema version 为 1。JSON envelope 精确包含：

```json
{
  "messageId": "uuid",
  "jobId": "uuid",
  "eventType": "EXECUTE_JOB",
  "schemaVersion": 1,
  "requestId": "request-123",
  "occurredAt": "2026-10-29T08:00:00Z"
}
```

- 禁止加入视频字节、对象内容、完整上下文、Checkpoint payload、模型输入/输出或 owner 凭据；Consumer 必须按 `jobId` 回查 MySQL。
- `messageId` 标识一次 Outbox 事件；重复 publish 可以重复出现同一 messageId，业务正确性仍由 Job 状态、version CAS 和阶段输入指纹保证。
- 未知 schemaVersion、缺字段或非法字段值属于不可重试的消息合同错误，不进入无限 Retry；原始消息按受控方式进入 DLQ，MySQL 记录安全错误摘要。
- `requestId` 只用于 Trace，不参与业务去重；跨 Relay/Consumer 日志继续携带同一值。

## 7. 验收场景

| ID | Given | When | Then |
| --- | --- | --- | --- |
| M1-A00 | 浏览器无有效会话且本地账号已初始化 | 页面登录、刷新并退出 | POST 创建 HttpOnly 会话；刷新通过 GET 恢复；DELETE 携带 CSRF 后回到登录页；客户端不持久化密码或会话令牌 |
| M1-A01 | 用户 A 有已通过 FFprobe 的媒体 | 同一 key、同一请求串行提交两次 | 两次返回同一 jobId；数据库只有一个逻辑 Job 和一个初始阶段 |
| M1-A02 | 用户 A 有已通过 FFprobe 的媒体 | 同一 key、同一请求并发提交 | 唯一约束只允许一个创建者；另一请求返回同一 Job |
| M1-A03 | key 已绑定请求指纹 X | 同一用户用该 key 提交指纹 Y | 返回 `409 IDEMPOTENCY_KEY_REUSED`，既有 Job 不变 |
| M1-A04 | Outbox 已提交但尚未 confirm | Relay 重启并再次发布 | Consumer 收敛到一个阶段推进，Outbox 最终可标记发布 |
| M1-A05 | Consumer 已提交业务事务但尚未 ACK | 消息被 RabbitMQ 重投 | 重投为成功 no-op，不重复阶段和昂贵调用 |
| M1-A06 | sequence 1..7 已持久化，客户端处理到 4 后断线 | 使用 `Last-Event-ID: 4` 重连 | 只按序收到 5..7，随后继续热事件，无缺口和重复展示 |
| M1-A07 | Job 处于 QUEUED | 用户请求取消且消息同时到达 Consumer | CAS 只允许一个合法分支；最终为 CANCELED 或受控运行分支，无非法迁移 |
| M1-A08 | Job 发生不可重试媒体错误 | 用户查询或接收 SSE | 显示稳定 code、retryable=false 和安全摘要，不泄露内部路径/异常 |
| M1-A09 | Job 与事件已写 MySQL | 应用和 Redis 重启 | Job 详情与历史事件仍可查询；active key 可重建 |
| M1-A10 | 用户 A 拥有 media/job，用户 B 已登录 | B 查询、取消、完成上传或订阅 A 的资源 | 全部返回不泄露存在性的 404；A 的状态不变 |
| M1-A11 | 未登录或 CSRF Token 无效 | 调用受保护/状态变更 API | 分别返回 401 或 403 统一错误结构 |
| M1-A12 | 消息含未知 schemaVersion 或缺少定位字段 | Consumer 收到消息 | 不推进 Job、不无限重试，进入受控 DLQ/失败记录 |
| M1-A13 | 用户已有多个处于 UPLOADED/PROBING/READY/FAILED 的媒体 | 登录后打开媒体区或刷新页面 | 只按稳定顺序返回当前用户的持久化资源；READY 可提交，FAILED 显示安全失败摘要，其余状态不可提交 |
| M1-A14 | 用户 A 与用户 B 均有媒体 | A 翻页查询列表并尝试读取 B 的 mediaId | 列表绝不混入 B 的资源，详情返回不泄露存在性的 404，响应不暴露 ownerId/objectKey |

## 8. T1 合同证据

- Java：`M1ApiContracts` 固定 REST DTO、枚举、限制和路径；Jakarta Validation 保护不可信输入。
- Java：`AnalysisMessageEnvelope` 固定六字段 envelope，并拒绝未知 schema version。
- Java：`ErrorCode` 与 `GlobalExceptionHandler` 固定 M1 错误码和 HTTP 状态。
- TypeScript：`client/src/api/contracts.ts` 镜像前端可消费的 branded ID、DTO、枚举、分页和错误合同。
- 自动测试：后端合同测试验证路径、限制、输入校验、MQ 最小字段和错误状态；前端测试验证共享路径、Header、限制及 Job version/SSE sequence 分离。

后续任务可以新增可选响应字段，但删除字段、改变字段类型、复用错误码表达不同语义、改变幂等或 SSE 游标规则，必须先更新本文、合同测试并评估迁移影响。

## 9. T1 实际验证

日期：2026-10-29。

| 阶段/命令 | 结果 | 证据摘要 |
| --- | --- | --- |
| RED：`server\\mvnw.cmd -Dtest=M1ContractTest test` | 预期失败 | API/MQ 合同与 Jakarta Validation 尚不存在，测试编译失败 |
| RED：`npm.cmd exec -- vitest run src/api/contracts.test.ts --coverage=false` | 预期失败 | `./contracts` 尚不存在，Vitest 无法解析模块 |
| RED：`server\\mvnw.cmd -Dtest=WebBoundaryContractTest test` | 预期失败 | 认证错误尚未映射，期望 401、实际 400 |
| GREEN：`server\\mvnw.cmd test` | 通过，约 24.8 秒 | 28 项测试通过，包含合同、错误状态、MQ Trace 安全校验和 ArchUnit；JaCoCo 80% 门通过 |
| GREEN：`client\\npm.cmd run check` | 通过，约 20 秒 | 类型、Lint、5 项 Vitest、100% 当前可执行行覆盖率和生产构建通过 |

T1 没有修改数据库、RabbitMQ、MinIO、Redis 或运行时 Controller，因此未运行 `verify -Pintegration`；这些真实边界从 T2/T3/T5/T9 的对应薄切片开始验证。

## 10. T2 身份与归属证据

日期：2026-10-29。

- Flyway V2 新建 `identity_user` 与 `user_session`；账号密码只保存 BCrypt cost 12 哈希，会话 cookie 原值只返回客户端，MySQL 仅保存 SHA-256 摘要。
- `SessionApplicationService` 统一处理登录、当前会话和精确会话退出；未知用户仍执行 dummy BCrypt，用户名不存在与密码错误统一为 `401 INVALID_CREDENTIALS`。
- Redis 以用户名和来源地址摘要作为共享失败计数 key，Lua 原子设置计数与窗口 TTL；Redis 不可用时登录限流 fail closed 为可重试 `503 DEPENDENCY_UNAVAILABLE`。
- `VP_SESSION` 使用 HttpOnly、SameSite=Strict，默认 Secure；仅 local profile 默认允许非 Secure。状态变更使用服务端会话绑定的 `X-CSRF-Token`，重复退出保持 `204`。
- `OwnerContext` 只能由 `AuthenticatedSession` 构造。双用户测试证明 A/B 上下文互不拥有对方资源；HTTP 合同拒绝请求体 `ownerId`，Header/查询参数也不能覆盖会话 owner。Media/Job 具体资源的 404 隔离仍须在 T4/T7/T9 随资源服务实现继续验证。
- 本地账号无自助注册。仅当 `IDENTITY_BOOTSTRAP_ENABLED=true` 时，启动流程读取环境变量创建首个账号；用户名/密码不写日志，弱于 12 字符的初始化密码拒绝启动。

| 阶段/命令 | 结果 | 证据摘要 |
| --- | --- | --- |
| RED：identity/application、persistence、security 合同测试 | 预期失败 | 先后因身份端口、持久化 Adapter 和安全 Controller/Filter 尚不存在而编译失败 |
| 变异检查：反转 `OwnerContext.owns` | 预期失败 | 双用户隔离测试在 owner 相等断言处失败，随后恢复生产逻辑 |
| `server\mvnw.cmd test` | 通过，约 29.0 秒 | 46 项测试、ArchUnit 和 JaCoCo 80% 门通过 |
| `server\mvnw.cmd clean verify -Pintegration` | 通过，约 55.7 秒 | 使用隔离端口 MySQL 13306、Redis 16379、MinIO 19000、Qdrant 16333；46 项单测和 4 项真实依赖/Flyway/身份持久化集成测试通过 |
| `client\npm.cmd run check` | 通过，约 23.7 秒 | 类型、Lint、5 项 Vitest、100% 当前可执行行覆盖率和构建通过 |

集成验证前两次运行分别暴露并修复了条件装配时序与 final Repository 代理问题；随后宿主机默认端口服务与 Compose 配置不一致，因此最终成功运行使用上述隔离端口。测试容器不构成产品运行环境。

## 11. T2F 登录前端验收面证据

日期：2026-10-29。

- `client/src/api/session.ts` 只调用冻结的 `/api/v1/sessions` 与 `/current` 资源；请求使用同源 Cookie，退出携带内存中的 `X-CSRF-Token`，未引入 localStorage/sessionStorage 会话状态。
- 页面覆盖会话恢复、登录、统一错误、密码失败后清空和退出；已登录页只显示当前已交付能力，并明确把上传标成“下一能力”，没有伪造 M1 后续状态。
- 视觉采用 Roadmap 4.1 的教学档案工作台方向；桌面与 375 x 812 手机视口均完成截图/DOM 检查，标题层级、表单标签、按钮可访问名称存在，Console error/warning 为 0。
- 浏览器在 Compose 内网依赖与全新 `videopilot_browser` schema 上完成真实 POST 登录、刷新 GET 恢复和带 CSRF 的 DELETE 退出；Flyway 从空库应用 V1-V3，登录成功后会话到期时间来自服务端。

| 阶段/命令 | 结果 | 证据摘要 |
| --- | --- | --- |
| RED：`client\npm.cmd run test -- --run` | 预期失败 | Session API 模块不存在，旧 M0 页面没有账号、密码、登录与退出操作；4 个页面场景失败 |
| GREEN：`client\npm.cmd run check` | 通过，约 9.2 秒 | 类型与零 warning Lint 通过；16 项测试通过；Statements 95.77%、Branches 92.53%、Functions 100%、Lines 98.36%；生产构建成功 |
| 真实浏览器：桌面与 375 x 812 | 通过 | 登录、刷新恢复、退出均连接真实后端；桌面/手机布局可用，可访问 DOM 完整，Console error/warning 为 0 |

## 12. T4 幂等创建与事务 Outbox 证据

日期：2026-10-30。

- `AnalysisJobApplicationService` 先按当前 owner 与 `Idempotency-Key` 查找既有请求，再校验媒体归属和 READY 状态；因此已成功请求的网络重试不会因媒体后续状态变化而改变结果。
- 目标文本先经 NFKC 规范化并折叠空白，再与 mediaId、模式共同生成请求指纹；逻辑分析键额外绑定媒体内容指纹与 pipeline version，避免显示文本差异或供应链升级意外复用执行。
- MySQL V1 已具备 `owner_id + idempotency_key` 与 `owner_id + analysis_key` 唯一约束，T4 直接以这两项约束裁决并发，无需篡改旧迁移或新增重复索引。
- 持久化 Adapter 在一个 Spring 事务中写入 `CREATED` Job、已完成 `SUBMISSION` 阶段、sequence=1 的 `JOB_CREATED` 事件和 `PENDING` Outbox。后续写入故障会回滚整个事务，不留下孤立 Job 或 Outbox。
- `POST /api/v1/jobs` 返回 `202 Accepted`、`Location` 与服务端生成的稳定 links；详情和分页查询始终从认证会话取得 owner。跨用户读取与不存在资源统一为 404。

| 阶段/命令 | 结果 | 证据摘要 |
| --- | --- | --- |
| RED：application service 测试 | 预期失败 | 创建命令、Port 与服务尚不存在，测试编译失败 |
| RED：`AnalysisJobCreationIT` | 预期失败 | MySQL 场景因创建服务未装配而失败 |
| RED：幂等重试回归测试 | 预期失败 | 初版先检查媒体 READY，导致已成功请求在媒体状态变化后错误返回 `MEDIA_NOT_READY`；调整为先查既有幂等请求后通过 |
| 变异检查：反转媒体 READY 条件 | 预期失败 | 5 项 application 测试中 2 项断言失败、3 项报错，恢复正确条件后通过 |
| `server\mvnw.cmd clean test` | 通过，约 36.9 秒 | 103 项测试通过，包含 application、Controller、持久化 Adapter、ArchUnit；JaCoCo 80% 门通过 |
| `server\mvnw.cmd clean verify -Pintegration` | 通过，约 57.7 秒 | 隔离端口与全新 `videopilot_t4` schema 上 103 项单测、13 项 Flyway/真实依赖/MySQL 集成测试通过 |

T4 不改动 `client`，且按已冻结依赖需等待 T10F 提供真实 READY 媒体操作面后再实施 T4F，因此本任务未运行前端 `check` 或浏览器联调。RabbitMQ 发布仍属于 T5；T4 只保证待发布 Outbox 与 Job 事实同事务落库。

## 13. T5 RabbitMQ 拓扑与可靠 Outbox Relay 证据

日期：2026-10-31。

- Spring AMQP 声明持久化主 direct exchange/queue、TTL retry exchange/queue 和 DLX/DLQ；队列、routing key、TTL、确认超时与 Relay 参数均可由环境变量覆盖。
- publisher 使用 mandatory return 与 correlated confirm。只有 Broker ACK 且消息未 return 时才返回 `CONFIRMED`；nack、不可路由、confirm timeout 和客户端异常都不会把 Outbox 标为成功。
- V4 以前进迁移增加 `lease_id`、`lease_until`、租约索引和发布状态约束。Relay 通过 `FOR UPDATE SKIP LOCKED` 原子抢占到期 `PENDING/RETRY` 或过期 `PUBLISHING` 记录；状态更新同时校验当前 lease，旧实例不能覆盖新实例结果。
- 失败发布按已执行次数指数退避并受最大间隔和最大次数限制；耗尽后写 `DEAD_LETTERED`。MySQL 仍是发布事实真源，租约过期允许至少一次重发，不承诺恰好一次。
- RabbitMQ 消息由内部 `AnalysisDispatchMessage` 映射为冻结的 `AnalysisMessageEnvelope`，只包含 `messageId/jobId/eventType/schemaVersion/requestId/occurredAt`，不携带目标文本、媒体、上下文、模型输出或 owner 凭据。

| 阶段/命令 | 结果 | 证据摘要 |
| --- | --- | --- |
| RED：真实迁移/上下文测试 | 预期失败 | `JdbcOutboxRelayStore` 为 final，Spring 无法创建事务代理；移除 final 后 V4 与上下文通过 |
| RED：真实 RabbitMQ 发布测试 | 预期失败 | 未启用 correlated publisher confirm 时 Outbox 留在 `RETRY`；启用 confirm/return 后消息确认并写 `PUBLISHED` |
| RED：完整集成测试 | 预期失败 | 0ms receive 用于清队列会在 RabbitMQ consumer-ok 前超时；改用 RabbitAdmin 同步 purge 后稳定通过 |
| 变异检查：反转 confirm 成功条件 | 预期失败 | 5 项 Relay application 测试中 4 项失败，恢复正确分支后通过 |
| `server\mvnw.cmd clean test` | 通过 | 114 项测试、ArchUnit 与 JaCoCo 80% 门通过 |
| `server\mvnw.cmd clean verify -Pintegration` | 通过 | 隔离端口与 `videopilot_t5` schema 上 114 项单测、19 项真实依赖/Flyway/MySQL/RabbitMQ 集成测试通过 |

T5 是内部可靠性薄片，没有修改 `client`，因此未运行前端 `check` 或浏览器联调。Consumer 手动 ACK、消息重投幂等、Retry Queue/DLQ 消费路径和 Job 状态推进属于 T6；本任务只完成 Outbox 到 Broker 的可靠发布边界。

### T5 后续修正：批量异步 Publisher Confirm

日期：2026-10-31。

- 修正 Relay 在批内逐条阻塞等待 Confirm 的问题：先发送本次抢占的全部 Outbox，再收集每条消息的有界异步 Confirm 并按原租约逐条结算；批次最坏 Confirm 等待由各条超时之和收敛为一个并行超时窗口。
- `AnalysisMessagePublisher` 返回 `CompletionStage<PublishResult>`；Rabbit Adapter 将 ACK、NACK、Return、超时、同步发送异常和异步异常映射为稳定结果，不新增线程池或依赖。
- 配置拒绝 `lease-duration <= confirm-timeout`，避免 Relay 在等待 Broker Confirm 前租约已经确定过期；默认 30 秒租约和 5 秒 Confirm 超时保持不变。
- 确定性单元测试证明第一条 Confirm 未完成时整个批次已经发出，并覆盖乱序 Confirm 与混合结果；真实 RabbitMQ 集成测试证明同一批 10 条消息均收到 Confirm、进入队列并落为 `PUBLISHED`。

本修正不改变消息 envelope、至少一次投递语义、数据库 Schema 或 Consumer 幂等规则；租约过期后的重复发布仍由 T6 数据库状态/version CAS 和阶段输入指纹安全收敛。

| 阶段/命令 | 结果 | 证据摘要 |
| --- | --- | --- |
| RED：批次并行发布回归测试 | 预期失败 | 第一条发布阻塞时，200ms 内后续两条没有进入 publisher，复现批内串行等待 Confirm |
| 变异检查：反转租约/Confirm 超时条件 | 预期失败 | 合法配置被拒绝且非法配置被接受，2 项配置测试分别报错/失败 |
| 聚焦单元测试 | 通过 | `OutboxRelayTest`、`RabbitAnalysisMessagePublisherTest`、`MessagingConfigurationTest` 共 15 项通过 |
| 聚焦真实 RabbitMQ 集成测试 | 通过 | `OutboxRelayIT` 6 项通过，包含单批 10 条消息发布与持久化确认 |
| `server\mvnw.cmd clean verify -Pintegration` | 通过，约 71 秒 | 136 项单元/架构测试、24 项真实依赖集成测试及 JaCoCo 80% 门通过 |

## 14. T6 幂等 Consumer、Redisson 锁与阶段 CAS 证据

日期：2026-10-31。

- Rabbit listener 使用手动 ACK；只有 handler 完成数据库事务或识别为确定 no-op 后才 ACK。锁忙时 reject 到主队列 DLX，经 TTL retry queue 延迟返回，不在消费者线程 sleep。
- `AnalysisJobExecutionHandler` 先按 jobId 回查 MySQL，再尝试 Redisson 短租约。Redis 不可用时锁返回 `UNAVAILABLE` 并继续执行，MySQL 状态、version CAS 和阶段唯一约束仍是唯一并发裁决。
- M1 假处理器把 `CREATED -> QUEUED -> CONSUMING` 分成两个可恢复事务，并原子写入 `JOB_QUEUED`、`STAGE_STARTED` Outbox 事件和确定性 `CONSUMPTION` 输入指纹；提交后重投不会新增阶段或再次推进 version。
- Consumer 直接读取原始 Rabbit `Message` 后执行严格 JSON/schema/字段和 4096 字节上限校验。消息正文不进入日志或 MySQL；失败审计只保存 SHA-256、可解析标识、稳定错误码、受限摘要和投递次数。
- 合同错误和重试耗尽由 confirm/return 保护的 publisher 转发原消息到 DLQ，成功后才 ACK 原消息；DLQ 发布未确认时原消息保持可重投。`x-death` 只按主队列 rejected 计数读取并钳制到配置上限。

| 阶段/命令 | 结果 | 证据摘要 |
| --- | --- | --- |
| RED：handler application 测试 | 预期失败 | `AnalysisJobExecutionHandler` 与 `JobExecutionLock` 尚不存在，5 项行为测试编译失败 |
| RED：consumer Adapter 测试 | 预期失败 | 手动 ACK Consumer、失败审计 Port 与 DLQ publisher 尚不存在，测试编译失败 |
| 变异检查：反转 `QUEUED` 状态条件 | 预期失败 | 5 项 handler 测试中 3 项失败，证明阶段抢占和重投收敛断言有效 |
| `server\mvnw.cmd clean test` | 通过，约 47.3 秒 | 132 项测试、ArchUnit 与 JaCoCo 80% 门通过 |
| `server\mvnw.cmd clean verify -Pintegration` | 通过，约 79 秒 | 隔离端口和 `videopilot_t6` schema 上 132 项单元/架构测试、23 项真实 MySQL/Redis/RabbitMQ/Flyway 集成测试及 JaCoCo 80% 门通过 |

T6 是内部可靠性薄片，没有修改 `client`，因此不运行前端 `check` 或浏览器联调。页面可见的持久化进度、失败与取消验收面从 T7/T7F 开始。

## 15. T7 Job 查询、持久化 SSE、取消与失败语义证据

日期：2026-11-02。

- V6 以前进迁移为 `job_event_outbox` 增加 `job_status`、`job_stage`、`public_message` 和 `terminal`，并回填既有 T4-T6 事件；事件历史与 Outbox 继续在 Job 状态迁移事务内原子写入，MySQL 是唯一顺序真源。
- `AnalysisJobEventApplicationService` 在读取事件和每次取消 CAS 前都按认证 owner 回查 Job；不存在与跨用户统一为 `RESOURCE_NOT_FOUND`，事件 SQL 再以 owner join 做纵深约束。
- SSE 未带游标时从 sequence 1 回放，`Last-Event-ID: N` 只读取 `> N`；负数、非数字和未来游标返回 `400 INVALID_REQUEST`。连接通过 MySQL 增量查询继续追踪新事件，不依赖 Redis 或进程内通知，因此没有补拉/订阅空窗，应用重启后仍可恢复。
- 每条 SSE 的 `id` 与 JSON `sequence` 一致，Controller 丢弃不大于当前游标的记录；15 秒心跳使用 comment，不占 sequence、不写数据库。M1 假任务的 `JOB_READY`、取消和失败事件发送完毕后连接正常结束。
- `POST /api/v1/analysis-jobs/{jobId}/cancellations` 受会话与 CSRF 保护。CREATED/QUEUED 经状态机进入 CANCELED，运行中状态进入 CANCEL_REQUESTED；CAS 冲突后最多重读三次最新 owner 快照，已取消请求幂等返回，其他不可取消状态返回稳定 `JOB_NOT_CANCELABLE`。
- Job 详情只公开稳定 failure code、retryable 和受控摘要；事件公开消息在持久化时确定，不向页面暴露异常类名、内部路径或原始供应商输出。

| 阶段/命令 | 结果 | 证据摘要 |
| --- | --- | --- |
| RED：事件/取消 application 测试 | 预期失败 | owner-scoped 事件 Port、游标批次和取消服务尚不存在，测试编译失败 |
| RED：MockMvc SSE 合同 | 预期失败 | 初版 SSE 错误经 JSON ExceptionHandler 协商为 406；改为在 SSE 通道返回带稳定错误结构的 400/404 后通过 |
| RED：真实集成验证 | 预期失败 | V6 新增必填事件快照列后，T5 Outbox 测试 fixture 未提供列值；补齐 fixture 后 Relay 回归通过 |
| 聚焦真实 RabbitMQ 回归 | 通过 | `OutboxRelayIT` 6 项通过，证明新增事件投影列不改变 Outbox claim/publish/confirm 语义 |
| `server\mvnw.cmd clean verify -Pintegration` | 通过，约 65 秒 | 隔离端口与全新 `videopilot_t7_final` schema 从 V1 迁移到 V6；148 项单元/架构测试、27 项真实 MySQL/Redis/RabbitMQ/Flyway 集成测试及 JaCoCo 80% 门通过 |

T7 未修改 `client`，因此未运行前端 `check` 或浏览器联调；Job 状态、SSE、取消与失败的可视化验收面由紧随其后的 T7F 实现。

## 16. T8 假任务工作台纵向闭环证据

日期：2026-11-02。

- T4F/T7F 工作台使用冻结的 owner-scoped Job、事件与取消契约；提交携带一次意图一个幂等键和 CSRF，导航只使用服务端 links，请求不包含 ownerId。
- 页面状态、阶段、失败摘要和时间线只来自 REST/SSE。fetch-based SSE 显式携带最后 event id，校验 id/sequence、跨重连去重并采用 400/800/1600ms 有界退避；刷新终态页面仍从 MySQL 回放完整事件。
- 真实浏览器在 1440x900 与 375x812 视口通过完成、刷新、取消和失败展示；键盘主路径、可访问名称/区域、live region 均通过，最终 Console error/warning 为 0。
- 完成 Job 的页面与 MySQL 均显示 READY、version=6、event_sequence=7，七条事件类型和顺序一致。失败场景明确使用持久化 fixture，仅验收安全读取/展示，不冒充后续真实媒体失败生产链路。
- 联调修复了两个纵向缺陷：Consumer 对运行中 `CANCEL_REQUESTED` 不收敛，以及 SSE async dispatch 丢失认证。二者均增加失败优先的回归测试。

| 阶段/命令 | 结果 | 证据摘要 |
| --- | --- | --- |
| RED：Job API/工作台组件测试 | 预期失败 | API 模块与工作台不存在；先固定提交、事件、取消、失败和刷新恢复行为 |
| RED：取消收敛测试 | 预期失败 | handler 遇到 `CANCEL_REQUESTED` 后 Job 仍停在请求态；修复后推进到 CANCELED 并写终态事件 |
| RED：SSE async 安全回归 | 预期失败 | 初始请求认证成功，但 async dispatch 返回 401；修复过滤器分派后保持认证且不放宽授权 |
| 变异检查：移除 `CANCEL_REQUESTED` 收敛 | 预期失败 | `acknowledgesAConcurrentCancellationThroughTheStateMachine` 明确失败；恢复分支后 6 项 handler 测试通过 |
| `server\mvnw.cmd clean verify -Pintegration` | 通过，约 77 秒 | 150 项单元/架构测试、27 项真实 MySQL/Redis/RabbitMQ/Flyway 集成测试、JaCoCo 80% 门和打包通过 |
| `client\npm.cmd run check` | 通过 | 类型检查、零 warning Lint、29 项 Vitest、Statements 82.88%、Branches 80.35%、Functions 90.19%、Lines 89.43% 和生产构建通过 |
| 真实浏览器 | 通过 | 完成/刷新/取消/失败、桌面/手机、键盘/可访问树通过；Console error/warning 为 0，页面事件与 MySQL 一致 |

详细环境、场景与事实边界见 `tasks/M1/T8_BROWSER_ACCEPTANCE.md`。

## 17. T9 MediaAsset 与 MinIO upload session 证据

日期：2026-11-02。

- `MediaAsset` 与 upload session 由独立 domain/application/port 管理，Controller 只调用 application service。V7 新增持久化 session、内容长度和声明 MIME；对象 key 改为 ASCII binary 语义并具备完整唯一约束，只能按 `media/{ownerId}/{mediaId}/source` 由服务端生成。
- MinIO multipart 会话只向客户端返回指定 object key、uploadId 和 partNumber 绑定的短期 PUT URL，不返回长期 access/secret key。完成前以服务端 `ListParts` 核对有序 part/etag 和总字节数，再核对对象长度与客户端 SHA 元数据；错误对象不会进入可分析状态。
- 数据库唯一键决定同 owner/SHA 的赢家，不依赖 MySQL `ON DUPLICATE KEY UPDATE` 的受影响行数。重复创建会中止输家的 provider upload 并返回既有 session；重复完成直接返回既有媒体事实。
- session 过期既在后续访问时立即中止，也由有界定时批次扫描并释放 MinIO multipart；中止和过期状态只通过 owner-scoped session 更新。跨 owner 的读取、签名和完成统一表现为资源不存在。
- T9 完成后媒体仅为 `UPLOADED`，不会伪装成 `READY`。客户端 SHA、文件名和 MIME 都不是可信内容证明；T10 仍须用 FFprobe 和服务端可信哈希/元数据校验后才能允许提交分析。

| 阶段/命令 | 结果 | 证据摘要 |
| --- | --- | --- |
| RED：application/API 测试 | 预期失败 | Media upload domain、Port 和 Controller 尚不存在，测试先以编译失败固定 owner、分片、幂等与响应契约 |
| RED：首次真实迁移 | 预期失败 | `utf8mb4 VARCHAR(1024)` 唯一索引超过 InnoDB 3072 字节；将对象键收紧为真实的 ASCII binary 协议后，V1-V7 全量迁移通过 |
| RED：首次重复创建 | 预期失败 | MySQL 驱动把重复 upsert 报告为受影响 1 行，误插入 loser session；改为读取唯一键赢家并比较 mediaId，回归测试显式锁定该行为 |
| 变异检查：反转分片总长度条件 | 预期失败 | MinIO Adapter 的成功完成与错误总长度测试均失败，恢复条件后 6 项存储边界测试通过 |
| `server\mvnw.cmd clean test` | 通过，约 46 秒 | 175 项单元/Controller/持久化/架构测试及 JaCoCo 80% 门通过 |
| `server\mvnw.cmd clean verify -Pintegration` | 通过，约 80 秒 | 隔离端口与全新 `videopilot_t9_final` schema 上 175 项单元/架构测试、29 项真实 MySQL/Redis/RabbitMQ/MinIO/Flyway 集成测试及打包通过 |
| OWASP Dependency-Check | 未形成结论 | 无 NVD API Key 的全量更新持续无进度，按 M0 已接受的遗留风险终止；未把无结论扫描描述为通过 |

T9 没有修改 `client`，因此未运行前端 `check` 或浏览器联调；文件选择、分片进度、重试和中止的用户验收面由紧随其后的 T9F 实现。

## 18. T10 可信媒体探测与 READY 门证据

日期：2026-11-03。

- 上传完成仍只产生 `UPLOADED`；有界调度器从 MySQL 扫描待处理或陈旧 `PROBING` 资源，并用 version CAS 抢占，进程重启后可继续同一媒体探测，不依赖内存队列。
- MinIO 源对象复制到每次任务独占的受控临时文件；应用层计算服务端 SHA-256 后调用供应商无关 `MediaProbe` Port。成功、稳定失败、超时和异常路径均清理临时文件，FFprobe 超时会销毁子进程。
- FFprobe Adapter 使用固定参数数组，不经过 shell；并发、超时和输出字节数均受配置限制。只有存在视频流且时长、尺寸有效的结果进入 `READY`，损坏、无视频流、超时和缺少二进制得到稳定、安全的 `FAILED` 事实。
- V8 只做前进迁移，增加可信 SHA、容器、视频编码、尺寸、探测时间和受控失败列。Job 创建仍在 owner-scoped application 边界按 MySQL 真源校验 `READY`，不会信任客户端 MIME、文件名或摘要。
- 生产镜像和 CI 显式安装 FFmpeg；应用默认关闭 worker，Compose 运行环境显式开启，避免未配置二进制时悄然改变开发测试行为。

| 阶段/命令 | 结果 | 证据摘要 |
| --- | --- | --- |
| RED：媒体探测 application 测试 | 预期失败 | `MediaProbeApplicationService`、探测 Port 和失败事实尚不存在，4 项行为测试先以编译失败固定 CAS、可信摘要、失败收敛与清理 |
| RED：FFprobe Adapter 测试 | 预期失败 | Adapter 与受控进程配置尚不存在；随后用测试进程覆盖有效视频、无视频流、输出超限和超时销毁 |
| RED：持久化与装配测试 | 预期失败 | 探测候选扫描、version CAS、终态写入和 MinIO 受控复制接口尚不存在 |
| `server\mvnw.cmd test` | 通过，约 52.5 秒 | 186 项单元、Controller、持久化与架构测试，JaCoCo 80% 门通过 |
| Docker 内聚焦 `verify -Pintegration` | 通过，约 125 秒 | 全新 `videopilot_t10_final` schema 从 V1 迁移到 V8；4 项 application 测试及 2 项真实 MySQL/MinIO/FFprobe 集成测试通过 |

T10 没有修改 `client`，因此未运行前端 `check` 或浏览器联调；媒体列表、状态轮询与 READY 选择分别由紧随其后的 T10Q、T10F 提供。

## 19. T10Q owner-scoped 媒体查询证据

日期：2026-11-03。

- `GET /api/v1/media-assets?page&pageSize` 只使用认证 principal 的 owner，不接受 owner 查询参数；分页参数在 Controller 和 application service 两层限制，非法值稳定返回 `400 INVALID_REQUEST`。
- 查询 Port 的列表和详情都返回同一 `MediaAsset` 领域事实，并由 application service 统一投影为 `MediaAssetView`。HTTP 响应包含可信状态、时长和受控失败事实，不包含 ownerId、objectKey、临时文件或 FFprobe 命令。
- MySQL 列表只包含已完成 upload 的媒体状态，排除 `UPLOAD_PENDING`，并按 `created_at DESC, media_id DESC` 排序；相同创建时间仍有确定顺序，翻页不会因为并列时间产生漂移。
- 详情 SQL 同时约束 ownerId/mediaId；不存在和跨 owner 统一映射到 `404 RESOURCE_NOT_FOUND`，列表 count 与 data 使用相同 owner 和状态边界。

| 阶段/命令 | 结果 | 证据摘要 |
| --- | --- | --- |
| RED：application/controller 测试 | 预期失败 | query Port、分页结果、application service 和列表 Controller 尚不存在，测试以 19 项编译错误固定 owner、分页与统一投影边界 |
| 聚焦 application/controller/persistence | 通过 | 16 项测试覆盖分页派生、非法边界、详情隐藏、HTTP 元数据与 Mapper offset/count 调用 |
| `server\mvnw.cmd clean test` | 通过，约 58.4 秒 | 192 项单元、Controller、持久化与架构测试，JaCoCo 80% 门通过 |
| Docker 内 `clean verify -Pintegration -Dit.test=MediaAssetQueryIT` | 通过，约 252 秒 | 192 项后端回归及 2 项真实 MySQL 集成测试；覆盖双用户、混合状态、同时间戳稳定排序、分页、未完成上传排除和跨 owner 详情隐藏 |

T10Q 是后端查询薄片，没有修改 `client`；页面加载、轮询、READY 选择和真实浏览器验收由 T10F 完成。

## 20. T10F 媒体资源与 READY 选择前端证据

日期：2026-11-03。

- Media API client 只调用 owner-scoped list/detail 路径，不发送 ownerId；媒体响应补齐安全失败事实类型。资源面以服务端状态为真源，不依据扩展名、MIME 或上传成功自行推断 READY。
- 上传面通过组件事件把完成响应立即合并到第一页；资源面只轮询当前可见的 `UPLOADED/PROBING`，每轮最多当前页 8 项、单定时器 2 秒间隔，终态后停止并在卸载时清理。
- READY 是唯一显示“选择用于分析”操作的状态；T4F 表单不再接受手填测试 mediaId。选择 READY 后提交按钮才可用，提交仍沿用既有幂等键、CSRF 和服务端 links。
- 加载、空列表、分页、局部刷新和服务不可用均有明确状态；刷新从 MySQL 恢复媒体和 Job。请求失败时保留最近可信列表，不把网络失败误写成媒体失败。

| 阶段/命令 | 结果 | 证据摘要 |
| --- | --- | --- |
| RED：Media API/资源组件/上传接线 | 预期失败 | `list/get`、`MediaLibrary` 和 `uploaded` 事件尚不存在；测试分别以方法缺失、模块解析失败和事件缺失锁定合同 |
| `client\npm.cmd run check` | 通过 | 类型检查、零 warning Lint、52 项 Vitest；Statements 86.98%、Branches 80.91%、Functions 87.70%、Lines 92.53%；生产构建通过 |
| 真实浏览器 | 通过 | 有效视频 `UPLOADED -> READY`、可信 00:03、选择并提交到 Job READY、损坏视频 FAILED、刷新恢复、服务中断/恢复、双账号隔离、375 x 812 无溢出和 Console error/warning 为 0 |

详细环境、页面证据与事实边界见 `tasks/M1/T10F_BROWSER_ACCEPTANCE.md`。

## 21. T11 真实上传与进度纵向闭环证据

日期：2026-11-03。

- Job 工作台在提交成功时保留所选媒体事实，并在从服务端 `links.self` 恢复持久化 Job 时按当前会话查询 owner-scoped 媒体详情；页面展示真实文件名、可信时长、服务端媒体状态和 ID，不接受 owner 输入。
- 媒体关联查询使用修订号和当前 Job/mediaId 双重校验，迟到响应不会覆盖新选择。媒体详情暂时不可用时保留 Job 状态、操作和持久化时间线，并提供独立的媒体信息不可用提示。
- 真实浏览器从无会话页面完成登录、有效视频上传、`UPLOADED -> READY`、READY 选择、分析提交、Job READY 和 7 条持久化事件展示；整页刷新后会话、媒体列表、Job、媒体事实和事件均从服务端恢复。
- 损坏视频上传完成后由真实 FFprobe 路径收敛为 `FAILED / MEDIA_INVALID`，页面显示安全摘要且不提供分析选择。分析失败页面使用显式 MySQL 验收 fixture，只证明失败事实和 5 条持久化事件可被安全读取与展示，不宣称 M1 假处理器已具备真实故障注入能力。

| 阶段/命令 | 结果 | 证据摘要 |
| --- | --- | --- |
| RED：Job 媒体事实测试 | 预期失败 | 持久化 Job 详情缺少 `job-media-facts`，先以失败测试固定创建后绑定和刷新恢复行为 |
| 聚焦组件测试 | 通过 | Job 工作台与媒体资源组件 11 项测试通过，覆盖提交绑定、URL 恢复、迟到响应隔离和媒体详情失败降级 |
| `client\npm.cmd run check` | 通过 | 类型检查、零 warning Lint、54 项 Vitest、四项覆盖率门和生产构建通过 |
| 真实浏览器 + Compose | 通过 | MySQL、RabbitMQ、MinIO 和真实服务端链路完成有效上传/校验/提交/刷新；损坏上传与分析失败展示符合安全语义 |
| 响应式与浏览器诊断 | 通过 | 375 x 812 下无横向溢出，Console error/warning 为 0 |

详细账号处理、资源/Job 标识、数据库事实和 fixture 边界见 `tasks/M1/T11_BROWSER_ACCEPTANCE.md`。
