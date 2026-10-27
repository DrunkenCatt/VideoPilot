# ADR-0004：Checkpoint 与 Outbox 决策并入 ADR-0001

## Status

Superseded by ADR-0001

## Date

2026-10-27

## Context

本记录最初单独描述 Checkpoint 和 Transactional Outbox。复核后发现其核心决定已由 ADR-0001 完整覆盖：MySQL 是任务与恢复真源，状态与 Outbox 同事务写入，RabbitMQ 至少一次投递，Consumer 依靠数据库状态、版本和阶段输入指纹实现幂等。

## Decision

不再维护一份重复的 Checkpoint/Outbox ADR。后续开发以 ADR-0001 为唯一决策来源；具体表字段、消息字段和测试场景放在迁移、代码和里程碑任务中。

## Consequences

- ADR-0004 仅保留为历史指针，避免编号和提交历史断裂。
- Checkpoint/Outbox 语义的后续变更应更新或取代 ADR-0001，而不是扩充本记录。
