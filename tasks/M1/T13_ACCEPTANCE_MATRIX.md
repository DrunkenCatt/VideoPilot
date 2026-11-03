# T13 M1 可靠性与权限验收矩阵

日期：2026-11-03。

## 自动化矩阵

| 风险场景 | 自动化证据 | 结果 |
| --- | --- | --- |
| 六路并发提交同一意图 | `AnalysisJobCreationIT.concurrentIdenticalSubmissionsCreateExactlyOneJobStageAndPendingEvent`：Job、初始 Stage、Outbox 均为 1 | 通过 |
| 同一幂等 key 携带不同请求 | `AnalysisJobCreationIT.sameKeyWithDifferentRequestHasAStableConflict`：稳定返回 `IDEMPOTENCY_KEY_REUSED`，原 Job 不变 | 通过 |
| MQ 并发重复与串行重投 | `AnalysisMessageConsumerIT.concurrentAndSerialRedeliveryConvergeOnOneFakePipeline`：最终 READY，阶段数 4、事件数 6，后续重投为 no-op | 通过 |
| 已终态 Job 收到乱序旧消息 | `AnalysisJobExecutionHandlerTest.acknowledgesAnOutOfOrderOldDeliveryWithoutReopeningAReadyJob`：ACK 且无新迁移 | 通过 |
| Consumer 在业务提交前退出 | `RabbitAnalysisMessageConsumerTest.rejectsForBrokerRetryWhenBusinessFailsBeforeCommit`：业务异常不 ACK，进入有限重试链路 | 通过 |
| 数据库提交后、ACK 前连接退出 | `RabbitAnalysisMessageConsumerTest.leavesCommittedDeliveryUnacknowledgedWhenAckFails` 与串行重投测试共同证明：ACK 失败向容器传播，Broker 可重投，数据库终态重投为 no-op | 通过 |
| Redisson 锁竞争或 Redis 不可用 | `AnalysisMessageConsumerIT.lockContentionRetriesThroughTtlQueueThenDatabaseClaimsJob`、`AnalysisJobExecutionHandlerTest.databaseCasStillClaimsOnceWhenRedisIsUnavailable` | 通过 |
| 应用完整关闭、Redis 清空、重新启动 | `ApplicationRestartRecoveryIT` 连续启动两个真实 Spring/Undertow 上下文；第二实例从 MySQL 找回同一 Job 和 sequence 1 事件 | 通过 |
| SSE 断线补拉与游标去重 | `AnalysisJobControllerTest` 验证 `Last-Event-ID`；`client/src/api/jobs.test.ts` 验证断线重连、旧序列去重和稳定鉴权错误停止重试 | 通过 |
| 取消与 Consumer CAS 竞态 | `AnalysisJobPersistenceAdapterIT.cancellationAndConsumerRaceEndsOnALegalCasBranch`：只允许合法 `CANCELED/CANCEL_REQUESTED` 分支 | 通过 |
| 失败事实安全展示 | `AnalysisJobControllerTest.exposesOnlyTheStableFailureContract` 与 `JobWorkbench.test.ts`：仅展示稳定 code/retryable/summary，不显示异常内部信息 | 通过 |
| 跨用户媒体枚举和详情 | `MediaAssetQueryIT`：列表只返回当前 owner；跨 owner 详情统一 `RESOURCE_NOT_FOUND` | 通过 |
| 跨用户对象上传、完成和中止 | `MediaUploadIT`：其他 owner 不能取得分片 URL 或完成上传；中止保持幂等且不改变他人资源 | 通过 |
| 跨用户 Job 枚举、读取、取消和订阅 | `AnalysisJobCreationIT`、`AnalysisJobPersistenceAdapterIT`、`AnalysisJobControllerTest`：列表为空，读取/取消/SSE 均统一 `RESOURCE_NOT_FOUND` | 通过 |
| MQ 最小载荷 | `M1ContractTest.keepsTheMqEnvelopeMinimalAndRejectsUnknownSchemaVersions`：消息仅含 messageId/jobId/eventType/schemaVersion/requestId/occurredAt | 通过 |
| 日志敏感信息 | 对全部生产 `LOGGER` 调用做静态复核：只记录稳定错误码、异常类型、mediaId 或固定降级文案；不记录视频、密码、会话 token、目标文本、object key 或 MQ body | 通过 |

## 浏览器主路径

T8、T10F、T11 已在真实浏览器和 Compose 环境证明“登录 -> 上传 -> READY 校验 -> 选择提交 -> Job 事件 -> 刷新恢复”，并覆盖取消、稳定失败展示、第二账号空列表、375 x 812 响应式以及 Console error/warning 为 0。T13 没有改变用户界面，不重复制造一套浏览器 fixture；自动化矩阵复用同一 REST/SSE 合同并补齐故障时序与越权写操作。

## 本次命令

| 命令 | 环境 | 结果 |
| --- | --- | --- |
| 聚焦 Consumer/Handler 单测 | Windows/JDK 21 | 16 项通过 |
| 聚焦上传/取消权限集成测试 | Compose MySQL/Redis/RabbitMQ/MinIO | 8 项通过 |
| 聚焦应用重启测试 | 两次真实 Spring/Undertow 启停 + Redis flush + MySQL | 1 项通过；首次因 `SERVER_PORT` 环境变量优先级占用 8080 失败，改用命令行随机端口后通过 |
| Consumer 异常分支变异检查 | 临时把提交前异常改为 ACK | 新测试按预期失败；恢复生产条件后通过 |
| `server\mvnw.cmd verify -Pintegration` | 隔离 Docker 网络、`videopilot_t12` schema、真实五项依赖与 FFmpeg | 205 项单元/架构测试、37 项集成测试、JaCoCo 与打包全部通过，约 5 分 15 秒 |
| `client\npm.cmd run check` | Windows/Node.js | 54 项测试、类型检查、零 warning Lint、四项覆盖率门和生产构建通过 |

所有测试 fixture 使用随机或固定测试标识，并在结束后清理；账号密码、会话 token、视频内容和运行时数据库未写入仓库。
