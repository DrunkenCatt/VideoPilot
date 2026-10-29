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
| `GET` | `/api/v1/media-assets/{mediaId}` | 无 | `200` + `MediaAssetResponse` |

约束：

- 服务端不信任扩展名和 MIME；只有 FFprobe 确认存在视频流且元数据合法后，媒体才进入 `READY`。
- multipart 完成请求必须覆盖已上传分片，分片号唯一且升序；重复完成同一 upload session 返回同一媒体事实，不重复创建对象或媒体。
- 预签名 URL 只能写当前用户 session 对应 object key 的指定 part，过期时间不超过 upload session。
- `sha256` 为 64 位十六进制；对象完成后必须与受信校验结果一致。失败对象不能创建分析 Job。

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
