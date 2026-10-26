# ADR-0001：使用 RabbitMQ 承载异步分析任务

## Status

Accepted

## Date

2026-10-26

## Context

VideoPilot 的视频解析、Embedding、索引和 Agent 执行都属于长耗时、高成本任务。HTTP 请求提交后需要立即返回，后台任务必须支持削峰、失败重试、死信处理和消费者异常后的重新投递。

项目参考 DoVideoAI 的异步任务设计，但本项目明确选择 RabbitMQ，并保持 Java 21、Spring Boot 3.5.9 模块化单体和其他核心技术栈不变。

消息代理不能成为业务状态真源。系统还需要处理以下边界：

- 数据库提交成功但消息尚未发布；
- Broker 已接收消息但发布端没有收到确认；
- Consumer 完成业务处理前进程退出；
- 同一消息被重复投递；
- 暂时性故障重试耗尽；
- 无法自动恢复的任务进入人工 Replay。

## Decision

使用 RabbitMQ 和 Spring AMQP 承载异步分析任务。MySQL 中的 `AnalysisJob`、Checkpoint 和 Outbox 是任务事实真源，RabbitMQ 只负责传输和削峰。

### Exchange 与队列

初始拓扑采用持久化 Direct Exchange：

```text
videopilot.analysis.exchange
  └── routing key: analysis.execute
        └── videopilot.analysis.queue

videopilot.analysis.retry.exchange
  └── videopilot.analysis.retry.queue
        └── TTL 到期后 dead-letter 回 analysis.execute

videopilot.analysis.dlx
  └── videopilot.analysis.dead-letter.queue
```

Exchange、Queue 和消息均设置为持久化。拓扑由 Spring Bean 声明，名称通过配置管理；开发、测试和生产环境使用不同前缀或 Virtual Host。

### 可靠发布

1. 创建或推进 `AnalysisJob` 时，在同一个 MySQL 事务中写入 Outbox。
2. Outbox Relay 使用 Spring AMQP 发布只包含 `messageId`、`jobId`、`eventType`、`schemaVersion` 和追踪字段的消息，不在消息中复制完整业务状态。
3. 开启 Publisher Confirm 和 Publisher Return。
4. Broker confirm 成功后才把 Outbox 标记为已发布；失败、超时或不可路由时保留记录并重试。
5. Relay 重发可能产生重复消息，因此发布链路提供至少一次投递，不承诺恰好一次。

### 消费确认与幂等

Consumer 使用手动 ACK：

1. 根据 `jobId` 从 MySQL 加载最新任务和 Checkpoint。
2. 通过数据库期望状态、`version` CAS 和阶段输入指纹抢占执行资格；Redisson 锁只降低并发冲突，不能替代数据库判定。
3. 业务事务和阶段产物持久化成功后执行 ACK。
4. 消息重复到达但阶段已完成时，Consumer 将其作为成功的 no-op 并 ACK。
5. Consumer 在 ACK 前退出时，RabbitMQ 可以重新投递，数据库幂等规则阻止昂贵阶段重复执行。

### 重试与死信

- 网络错误、429、明确的临时 5xx 和短暂基础设施故障进入有上限的延迟重试。
- 重试通过带 TTL 的 Retry Queue 和 dead-letter routing 实现，不在 Consumer 线程中 `sleep`。
- 消息头记录受控的重试次数；系统不无界信任客户端可修改的 header。
- 文件损坏、权限错误、参数错误和达到最大重试次数的任务进入业务 `FAILED/DEAD_LETTERED` 状态，并路由至 DLQ。
- DLQ 是诊断入口，MySQL 的失败任务记录才是人工 Replay 的依据。Replay 创建新的执行尝试或 Run，不直接把旧消息原样重新入队。

### 顺序、并发与大消息

- 系统不依赖 RabbitMQ 的全局消息顺序；`AnalysisJob` 状态机负责拒绝乱序迁移。
- 同一 Job 同一时刻只允许一个有效执行者，不要求整个队列单消费者。
- 视频、完整 VideoContext、模型输出和 Checkpoint 不进入消息体，只传递对象或数据库标识。
- Prefetch、Consumer 并发和线程池大小必须与模型配额、数据库连接池及 FFmpeg 资源预算一起标定。

## Alternatives Considered

### RocketMQ

参考项目已经验证了 RocketMQ 的异步削峰、重试和失败处理思路，但 VideoPilot 选择 RabbitMQ，因此不引入两套消息客户端和运维体系。可靠任务所需的确认、重投、重试队列与 DLQ 能通过 RabbitMQ 和应用层状态机实现。

### 进程内线程池

部署简单，但进程退出时排队任务会丢失，也无法为多实例消费者提供可靠重新投递，不满足长视频任务恢复要求。

### 仅使用 MySQL Job Polling

可以减少一个基础设施组件，但用户已经确定使用 RabbitMQ。MySQL 仍保留 Job 和 Outbox 真源，避免 Broker 与数据库双写不一致。

## Consequences

- 后端依赖 Spring AMQP，部署环境需要 RabbitMQ 和对应的健康检查、指标及告警。
- 系统以“至少一次投递 + 幂等消费”实现可靠性，业务代码必须正确处理重复和乱序消息。
- Publisher Confirm 只能证明 Broker 接收消息，不能证明业务任务已经完成；完成事实只能来自 MySQL 状态机。
- RabbitMQ DLQ 不能替代业务失败表、Checkpoint 或 Replay 流程。
- 后续若更换消息代理，Outbox Relay 和 Consumer Adapter 是主要替换边界，领域状态机无需随之改变。

## Validation

- 数据库提交后杀死 Relay，重启后 Outbox 能继续发布。
- 发布 Confirm 超时或路由失败时，Outbox 不会被错误标记为成功。
- Consumer 在业务提交前退出，消息重新投递且任务能继续。
- Consumer 在业务提交后、ACK 前退出，重复消息成为 no-op，不重复调用云端模型。
- 暂时性故障按计划进入 Retry Queue，达到上限后进入业务失败状态和 DLQ。
- 同一 `jobId` 并发投递时只有一个消费者获得有效状态迁移资格。
