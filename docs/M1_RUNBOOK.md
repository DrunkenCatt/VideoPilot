# M1 本地运行与验收手册

本手册只描述已经通过 M1 验收的能力：本地账号、owner 隔离、MinIO 分片上传、FFprobe 可信媒体校验、可靠异步假任务、持久化事件、SSE 补拉、取消和稳定失败展示。M1 的分析流水线仍是确定性假处理器，不包含真实 ASR/OCR、`VideoSegment`/`VideoChunk`、Checkpoint 阶段恢复、Embedding/Qdrant 检索或证据问答；这些能力从 M2 起逐步交付。

## 环境与启动

需要 JDK 21、Node.js 22.12+、Docker Desktop，以及可由容器调用的 Docker Compose。先检查工具链：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\check-toolchain.ps1
```

从示例创建本机 `.env`，只在未被 Git 跟踪的 `.env` 中填写临时演示账号。初始化密码至少 12 个字符；启动后把 `IDENTITY_BOOTSTRAP_ENABLED` 改回 `false`。重复启动不会重置同名账号的密码。

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
# 编辑 .env：
# IDENTITY_BOOTSTRAP_ENABLED=true
# IDENTITY_BOOTSTRAP_USERNAME=<本地临时用户名>
# IDENTITY_BOOTSTRAP_PASSWORD=<至少 12 字符的临时密码>
```

如果本机 3306、8080 或其他默认端口被占用，在 `.env` 中修改对应宿主机端口，例如 `MYSQL_PORT=13306`、`SERVER_PORT=18080`。若后端不是 8080，启动前端时把代理目标指向实际端口。

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\dev-up.ps1 -Build

cd client
npm ci
$env:VITE_API_PROXY_TARGET='http://localhost:8080'
npm run dev
```

后端默认是 `http://localhost:8080`，前端默认是 `http://localhost:5173`。`dev-up.ps1` 会等待 readiness；也可单独运行 `scripts/dev-check.ps1`。演示结束后运行 `scripts/dev-down.ps1`，该命令保留命名卷。

## 浏览器演示路径

1. 打开前端并使用 `.env` 中的临时账号登录。刷新页面，确认会话仍可由服务端恢复。
2. 选择一个本地视频上传。页面展示分片传输进度；完成传输只代表 `UPLOADED`，不能直接提交分析。
3. 等待服务端 FFprobe 校验。有效视频进入 `READY` 并显示可信时长；损坏或无视频流文件进入 `FAILED`，且只显示稳定错误事实。
4. 在媒体资源列表选择一个 `READY` 资源，填写目标并提交。页面显示 Job 状态和持久化事件，最终 M1 假任务进入 `READY`。
5. 刷新页面，确认媒体、Job 和事件仍从 MySQL 恢复。运行中的 Job 可发起取消；终态 Job 保持不可逆。
6. 可选：使用第二个本地账号登录，确认看不到第一个账号的媒体和 Job。跨 owner 详情、完成上传、取消和 SSE 订阅都统一表现为资源不存在。

上传 URL 由 MinIO 预签名并绑定 upload、object key 和 part number。浏览器不会得到 MinIO 长期凭据；页面也不会把密码或会话 token 写入 localStorage/sessionStorage。

## HTTP 与 SSE 合同

除创建会话和健康检查外，API 都需要服务端签发的 HttpOnly `VP_SESSION` Cookie。所有改变状态的已认证请求还需要会话响应中的 `X-CSRF-Token`。浏览器同源代理会自动携带 Cookie；不要把 Cookie 值或 CSRF token 写入日志。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/v1/sessions` | 登录并创建本地会话 |
| `GET` / `DELETE` | `/api/v1/sessions/current` | 恢复当前会话 / 退出 |
| `POST` | `/api/v1/media-uploads` | 创建 owner-scoped 分片上传会话 |
| `POST` | `/api/v1/media-uploads/{uploadId}/parts` | 获取某一分片的短期 PUT URL |
| `POST` / `DELETE` | `/api/v1/media-uploads/{uploadId}/completion` / `/api/v1/media-uploads/{uploadId}` | 完成 / 中止上传 |
| `GET` | `/api/v1/media-assets`、`/api/v1/media-assets/{mediaId}` | 分页查询媒体 / 查询详情 |
| `POST` / `GET` | `/api/v1/analysis-jobs` | 幂等提交 / 分页查询 Job |
| `GET` | `/api/v1/analysis-jobs/{jobId}` | 查询 Job 详情及服务端 links |
| `POST` | `/api/v1/analysis-jobs/{jobId}/cancellations` | 请求取消 |
| `GET` | `/api/v1/analysis-jobs/{jobId}/events` | 订阅 `text/event-stream` 持久化事件 |

提交 Job 必须提供 `Idempotency-Key`，合法字符为字母、数字、点、下划线、冒号和短横线，长度 1-128。同一 key 与相同请求返回同一逻辑 Job；同一 key 携带不同请求返回稳定冲突。

SSE 的每条 `job-event` 都有单调递增的 `id`，并与 JSON `sequence` 相同。重连时通过 `Last-Event-ID` 只读取更大的序列；心跳是 comment，不占序列。历史与当前序列来自 MySQL，Redis 清空或应用重启不会丢失可补拉事件。

## MQ 与事实边界

Transactional Outbox 与 Job 迁移在同一 MySQL 事务提交。Relay 发布到 RabbitMQ 的消息只包含 `messageId`、`jobId`、`eventType`、`schemaVersion`、`requestId` 和 `occurredAt`；不包含视频、目标全文、完整上下文、凭据或模型输出。

RabbitMQ 是至少一次投递。Consumer 只有在业务事务完成或确定识别为 no-op 后才 ACK；重复和乱序消息由 MySQL 状态、version CAS、阶段唯一约束和输入指纹收敛。Redis 只提供 active key 与短租约加速，任何缓存命中都必须回到 owner-scoped MySQL 事实复核。

## 关键配置

`.env.example` 是本地开发占位示例，不是秘密文件。Docker Compose 只把明确列出的变量传给后端；常用配置如下：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` / `MYSQL_PORT` | `8080` / `3306` | 宿主机映射端口，可解决本机端口冲突 |
| `IDENTITY_BOOTSTRAP_*` | 关闭/空 | 只用于受控创建本地账号，密码至少 12 字符 |
| `MINIO_PUBLIC_ENDPOINT` | `http://localhost:9000` | 必须是浏览器可访问的预签名 URL 地址 |
| `MEDIA_PROBE_*` | 见 `.env.example` | FFprobe 超时、输出、并发和扫描边界 |
| `MEDIA_UPLOAD_*` | 见 `.env.example` | 分片大小、数量和 URL/session 有效期 |
| `ANALYSIS_CONSUMER_*` | 见 `.env.example` | 有限重试、载荷上限、并发与 Redisson 短租约 |
| `ANALYSIS_PIPELINE_VERSION` | `m1-fake-v1` | 写入 M1 假流水线执行事实的版本 |
| `ANALYSIS_ACTIVE_CACHE_TTL` | `30m` | 可丢失 active key TTL，不改变 MySQL 真源 |

生产或非本地环境必须显式选择 profile、启用 HTTPS/Secure Cookie，并通过秘密管理注入凭据，不能复用示例密码。

## 验证与排障

```powershell
cd server
.\mvnw.cmd test
.\mvnw.cmd verify -Pintegration

cd ..\client
npm run check
npm audit --audit-level=high

cd ..
docker compose config --quiet
powershell -ExecutionPolicy Bypass -File .\scripts\security-check.ps1
```

readiness 失败时先运行 `docker compose ps` 和 `scripts/dev-check.ps1`，再按组件查看 `docker compose logs server` 或 `docker compose logs <dependency>`。健康接口只公开组件的 `UP/DOWN`，不会返回连接地址和凭据。

M1 的逐项验收事实见 `../tasks/M1/ACCEPTANCE.md`，可靠性与权限场景映射见 `../tasks/M1/T13_ACCEPTANCE_MATRIX.md`。M0 已接受的 OWASP 遗留风险仍不是“已修复”；安全脚本的每次实际结果必须如实记录，不能用历史接受替代本次扫描。
