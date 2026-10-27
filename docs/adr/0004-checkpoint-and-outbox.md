# ADR-0004：以版本化 Checkpoint 和 Transactional Outbox 保证恢复

## Status

Accepted

## Date

2026-10-27

## Context

AnalysisJob 同时写 MySQL、对象存储和 RabbitMQ，无法依赖跨系统分布式事务。应用可能在产物落库、状态推进、消息发布或 ACK 的任意边界退出。系统必须在至少一次投递下恢复同一任务，同时避免重复执行 ASR、OCR、Embedding 和模型调用。

## Decision

MySQL 是 Job、阶段尝试、Checkpoint 和待发布事件的唯一状态真源。状态推进和 Outbox 写入同一数据库事务；Broker、Redis 和进程内存都不能成为完成事实。

每个高成本阶段以 `jobId + stage + inputFingerprint` 标识一次可复用输入，并记录：

- `schemaVersion`：Checkpoint payload 的结构版本，读取方只接受明确支持的版本；
- `inputFingerprint`：由规范化输入、依赖产物摘要、配置和相关模型/Prompt/Embedding/管线版本组成；
- `status`、`attempt`、开始/结束时间和稳定错误分类；
- 小型确定性元数据或 MySQL/MinIO 产物引用，不存视频、完整上下文或模型原始响应；
- 产物摘要和可见性状态，确保产物完成持久化后才推进阶段。

输入指纹一致且 Checkpoint 为完成状态时，Recovery 跳过阶段；指纹变化、版本不支持或产物缺失时不得复用。Recovery 继续同一个 Job。Replay 创建新的执行尝试或 Run，并保留可验证的历史记录。

Outbox 事件至少包含 `messageId`、`jobId`、`eventType`、`schemaVersion`、Job sequence、`requestId` 和创建时间。Relay 使用 Publisher Confirm/Return 发布；只有 confirm 成功才标记发布完成。消息不携带视频、完整上下文或模型输出。

Consumer 在业务事务和 Checkpoint 持久化成功后手动 ACK。ACK 前退出允许 RabbitMQ 重投，数据库状态、`version` CAS 和输入指纹把重复消息转为安全 no-op。

## Alternatives Considered

### 先写数据库再直接发消息

进程在两步之间退出会永久丢消息；反向顺序则可能消费到尚未提交的业务状态。

### 把 Checkpoint 放在 Redis

Redis 数据丢失会导致昂贵阶段重做，违反 MySQL 真源和可恢复要求。

### 把完整产物放进 RabbitMQ

大消息增加 Broker 压力，复制敏感内容，也无法成为可靠业务真源。

### 使用全局事务

增加部署复杂度且不能覆盖 MinIO/外部模型的全部边界，不适合当前单服务器架构。

## Consequences

- 初始迁移必须为阶段输入指纹和 Outbox `messageId`/Job sequence 建立唯一约束。
- Checkpoint schemaVersion 的不兼容变更需要显式迁移或拒绝恢复，不能猜测反序列化。
- 对象存储写入采用先产物、后引用可见的顺序，并需要清理孤儿对象的运维策略。
- ADR-0001 的 RabbitMQ 至少一次语义保持不变；M1 实现最小链路，M4 补齐故障注入和 Replay 运维。

## Validation

- 数据库事务回滚时 Job 状态和 Outbox 均不提交。
- 数据库提交后杀死 Relay，重启仍能发布同一 Outbox；重复发布由 Consumer 幂等吸收。
- Consumer 在业务提交后、ACK 前退出，重投不会重复阶段或高成本调用。
- 相同 `inputFingerprint` 的完成阶段可跳过；指纹变化、产物缺失或未知 schemaVersion 会拒绝复用。
- MQ 消息检查确认不包含视频、完整上下文、Embedding 或模型原始响应。

