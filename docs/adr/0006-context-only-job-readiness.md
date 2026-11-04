# ADR-0006：Context-only Job 可从 CONTEXT_BUILDING 直接进入 READY

## Status

Accepted

## Date

2026-11-04

## Context

M1 为验证可靠消息闭环，使用确定性假处理器依次推进 `PROBING -> CONTEXT_BUILDING -> INDEXING -> READY`，但没有生成真实 VideoContext 或索引。M2 只交付时序多模态 VideoContext，Roadmap 明确把 `VideoChunk`、Embedding、Qdrant 和混合索引放在 M3。

若 M2 继续经过 `INDEXING`，Job、Checkpoint、SSE 和浏览器都会把尚未实现的阶段记录为已完成；若为 M2 新增一个终态，又会扩大数据库、API 和前端状态集合。`READY` 更适合表达“当前 pipeline version 所要求的全部产物已经可用”，而不是固定表示所有未来阶段均已完成。

## Decision

在现有统一状态机中增加 `CONTEXT_BUILDING -> READY` 合法迁移，不新增状态。

- M2 的 context-only pipeline 在 VideoContext、VideoSegment、通道状态和完成 Checkpoint 同事务提交后，经 `JobTransitionService` 直接进入 `READY`。
- M2 不创建 `INDEXING` stage attempt，不发布 INDEXING 事件，也不在页面上显示该阶段已完成。
- M3 使用新的 pipeline version；只有真实完成 VideoChunk/Embedding/Qdrant 或已定义的降级索引路径后，才走 `CONTEXT_BUILDING -> INDEXING -> READY`。
- 编排器根据持久化的 pipeline version 选择路径。Controller、Consumer、Mapper 和缓存仍无权直接写 Job 状态。
- `READY` 只表示当前 pipeline version 的必需产物可用。API 通过 Context/索引能力和版本字段说明具体可用能力，调用方不能仅凭 `READY` 推断问答或索引已经存在。

## Alternatives Considered

### M2 继续伪走 INDEXING

代码改动最少，但会制造虚假阶段事实，破坏恢复、进度和审计语义，因此拒绝。

### 新增 CONTEXT_READY 状态

语义直观，但会扩大状态机、数据库约束、REST/SSE 合同和前端分支；M3 仍需决定如何迁移旧 Job，超出最小 MVP 所需，因此拒绝。

### M2 Job 停留在 CONTEXT_BUILDING

无法形成稳定完成态，客户端会持续认为任务运行中，取消、缓存清理和结果复用也无法正确收敛，因此拒绝。

## Consequences

- 状态机多一条合法边，但没有新状态或数据库迁移。
- M2 编排和测试必须证明 context-only Job 从未创建 INDEXING attempt。
- M3 不能复用 M2 pipeline version 静默补索引；必须使用新版本并通过真实 INDEXING 阶段。
- Job 详情未来应展示 pipeline version/能力，而不是把 `READY` 翻译成“所有产品能力均完成”。

## Validation

- 状态机单测接受 `CONTEXT_BUILDING -> READY`，其他未声明迁移仍全部拒绝。
- M2 集成测试断言 READY Job 具有完成的 Context Checkpoint，且不存在 INDEXING stage attempt/event。
- M3 集成测试在落地时断言带索引能力的 pipeline 必须经过真实 INDEXING。
