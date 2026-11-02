# T8 真实浏览器验收记录

日期：2026-11-02。

## 验收边界

T8 验收 T4F/T7F 假任务工作台，以及 `Outbox -> RabbitMQ -> Consumer -> MySQL -> SSE -> 页面` 的 M1 纵向链路。T9/T10 尚未交付真实上传与 FFprobe，因此页面暂用已归属且为 READY 的测试媒体 ID；失败场景使用持久化验收 fixture，只证明失败查询、SSE 和安全展示契约，不把它描述成真实媒体失败生产链路。

环境使用隔离端口的 MySQL、Redis、RabbitMQ、MinIO 和 Qdrant；后端监听 18080，Vite 监听 5173 并代理到该后端，数据写入独立 `videopilot_t8` schema。已存在且占用 8080 的服务容器未停止、未修改。验收账号和密码不写入仓库。

## 纵向场景

| 场景 | 浏览器证据 | MySQL/服务端证据 | 结果 |
| --- | --- | --- | --- |
| 提交并完成 | 提交后依次显示 `JOB_CREATED`、`JOB_QUEUED`、`CONSUMING`、`PROBING`、`CONTEXT_BUILDING`、`INDEXING`、`JOB_READY` | Job `bfd4390f-db5b-488a-a8f1-3c2331b5d9a5` 为 READY、version=6、event_sequence=7；7 条事件 sequence/type 与页面一致 | 通过 |
| 刷新补拉 | 刷新同一 URL 后恢复相同详情与 7 条时间线，不依赖先前页面内存 | SSE 从 MySQL sequence 1 回放；终态事件后正常结束 | 通过 |
| 运行中取消 | 页面提交取消后最终显示 CANCELED 与 `JOB_CANCELED`，不自行乐观宣称成功 | Consumer 收到 `CANCEL_REQUESTED` 后通过统一状态机/CAS 收敛到 CANCELED | 通过 |
| 失败展示 | FAILED 页面显示稳定错误码 `MEDIA_CORRUPT` 与安全摘要，无内部异常、命令或路径 | fixture 只写稳定公开字段和终态 `JOB_FAILED` 事件 | 通过（fixture 边界） |
| 重复/重连 | API 测试覆盖重复事件丢弃、瞬时断线后携带最后 event id 重连及有界退避 | Consumer 集成测试覆盖重复消息 no-op，MySQL version/阶段指纹保持幂等 | 通过 |

## 页面与接口证据

- POST 创建使用服务端冻结的 `/api/v1/analysis-jobs`，携带 `Idempotency-Key`、CSRF 和同源 Cookie；请求体没有 ownerId。
- 创建成功后仅使用响应中的 `links.self/events/cancellations`；URL hash 保存完整 `links.self`，刷新不拼装资源地址。
- SSE 请求使用 `Accept: text/event-stream`，重连显式携带 `Last-Event-ID`；客户端同时校验 SSE id 与 payload sequence，一次会话内及跨重连均拒绝重复或倒序事件。
- 列表、详情和终态时间线均从服务端重新读取。页面没有本地进度计时器，也没有根据耗时推断状态。
- 真实浏览器运行中 Console error/warning 为 0。后端日志确认实际 REST/SSE 请求；接口 header、重试和错误分支另由 Vitest 契约测试覆盖。

## 交互与响应式

| 检查项 | 证据 | 结果 |
| --- | --- | --- |
| 桌面 | 1440 x 900，两栏任务轨道与详情布局完整，无遮挡或横向溢出 | 通过 |
| 手机 | 375 x 812，单栏重排，提交、列表、详情和时间线可读可操作 | 通过 |
| 键盘 | Tab 顺序覆盖媒体 ID、分析目标、创建按钮和任务列表入口；焦点样式可见 | 通过 |
| 可访问名称 | h1/h2/h3 层级、表单 label、按钮名称、区域名称和状态 live region 均存在 | 通过 |
| Console | 最终成功、刷新、取消和失败页面检查均无 error/warning | 通过 |

## 联调发现并修复

1. T6 假处理器原先只推进到 CONSUMING，无法形成 T8 终态。它现在通过统一状态机与事务事件依次推进到 READY，并保持阶段输入指纹和 CAS 幂等。
2. Consumer 原先遇到 `CANCEL_REQUESTED` 会 ACK 后停止，运行中取消永久停留在请求态。现在同一 handler 将其合法推进到 CANCELED 并写 `JOB_CANCELED`。
3. SSE 初始请求已认证，但 Spring MVC async dispatch 会再次经过过滤器并丢失请求认证，终态时产生 response committed 安全异常。Session filter 现在也认证 async dispatch，并新增 MockMvc async 回归测试；没有放宽 SSE 授权规则。

## 验证命令

| 命令 | 结果 |
| --- | --- |
| `server\mvnw.cmd clean verify -Pintegration`（隔离依赖端口、全新 `videopilot_t8_verify` schema） | 150 项单元/架构测试、27 项真实依赖集成测试、JaCoCo 80% 门和打包全部通过 |
| `client\npm.cmd run check` | 类型检查、零 warning Lint、29 项 Vitest；Statements 82.88%、Branches 80.35%、Functions 90.19%、Lines 89.43%；生产构建通过 |
