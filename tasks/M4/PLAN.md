# M4 实施计划：任务可靠性发布门

## 目标与当前基线

M4 将已经完成的上传、VideoContext、混合检索和证据问答闭环收敛为可发布的 V1 MVP。重点不是再建设一套任务框架，而是补齐现有 `AnalysisJob` 的自动恢复、有限重试、DLQ、Manual Replay、全链路资源预算和可操作性，并用故障注入证明任务不丢失且不会重复执行高成本阶段。

当前代码已经具备统一 `JobTransitionService`、MySQL 状态/Checkpoint/Outbox 真源、RabbitMQ 至少一次投递、Redisson 消费锁、分片输入指纹、`model_call` 审计、局部 ASR/OCR/索引恢复、SSE 游标重连和前端 Job 工作台。状态枚举也已预留 `RETRY_WAIT`、`DEAD_LETTERED`、`MANUAL_REPLAY`，但这些状态尚未形成完整生产闭环：消费重试耗尽与 Job 状态可能分离，缺少从 MySQL 扫描并恢复停滞任务的入口，Manual Replay 没有 owner-scoped API/审计/UI，全链路预算和运维聚合视图也尚未交付。

## 最小 MVP 决策

- 继续使用现有 `AnalysisJob`、`job_stage_attempt`、`job_event_outbox`、`analysis_message_failure` 和 `model_call`，只增加恢复调度、Replay 审计和预算所必需的字段；不引入工作流引擎、通用 Saga、事件溯源或第二套任务表。
- 用一个可靠性 application service 收敛“停滞扫描、有限重试、DLQ 收敛和 Manual Replay”规则；基础设施只提供一个聚合持久化端口和现有 Outbox 发布路径，不按 Retry/Recovery/Replay 各建一套 manager/repository。
- Recovery 恢复同一 Job：只跳过输入指纹一致且已原子完成的阶段。Manual Replay 是同一逻辑 Job 的新 attempt，由受控命令生成新事件和新 Outbox 消息；不得重投旧 RabbitMQ 消息。
- `RETRYABLE` 只允许配置化、有限次数且带 `nextRetryAt` 的自动重试；`UNKNOWN_OUTCOME` 不自动重复高成本调用；`NON_RETRYABLE` 直接失败；重试耗尽后进入 `DEAD_LETTERED`，由人工决定是否 Replay。
- 全链路 deadline 和调用/计量/成本预算由 Java 在高成本调用前后检查。Job pipeline 超限经统一迁移入口收敛失败；M3 的同步问答仍在 `answer_record/model_call` 记录稳定的预算错误，不得把已经 READY 的分析 Job 倒退或伪造为失败。供应商未返回价格时，不能把未知成本当作零，仍用调用次数、输入/输出计量和 deadline 形成硬门。
- MVP 复用并补齐现有 Adapter 的并发限制，为外部模型、FFmpeg/Tesseract 和消息消费使用彼此隔离的有界资源池；不引入 Resilience4j/Bucket4j 等新策略框架，也不建设多实例全局调度器。
- 运维仪表先交付一个只读聚合 API 和现有工作台内的轻量视图，覆盖队列/Outbox 积压、Job 状态与失败分类、阶段耗时、供应商错误、调用计量和成本可得性；不建设 Prometheus/Grafana 平台或独立 Admin SPA。
- 危险操作仅开放 owner 可见的单 Job Manual Replay，要求明确确认、预期版本和幂等键，并记录操作者、原因、来源 attempt 和结果；不提供批量重放、任意状态修改、删除消息或跳过证据门的接口。
- M4 不改变 M3 的检索公式、证据硬门和模型选择。若完整重试/Replay 语义要求增加或修改状态迁移边，先新增 ADR，再实现代码。

## 依赖图

```text
T0 可靠性合同、错误/恢复语义与故障矩阵
  -> T1 自动 Recovery、有限 Retry 与 DLQ 状态闭环
      -> T2 owner-scoped Manual Replay 纵向切片
      -> T3 deadline、预算、限流与资源池隔离
          -> T4 只读运维仪表纵向切片
              -> T5 故障注入、并发/SSE 验收与 M4 退出
```

T2 和 T3 在 T1 的状态/持久化合同稳定后可独立推进，但 M4 退出必须使用同一个候选版本完成 T4-T5。

## 任务表

| ID | 任务 | 高相关内容合并理由 | 依赖 | 规模 |
| --- | --- | --- | --- | --- |
| T0 | 冻结 M4 可靠性合同与故障矩阵 | 错误分类、恢复边界、状态迁移、Replay 语义和成功率口径共同决定 schema/API/测试，必须一次冻结 | 无 | M |
| T1 | 打通自动 Recovery、有限 Retry 与 DLQ 状态闭环 | 停滞扫描、`RETRY_WAIT` 调度、消费重试耗尽和 Job/DLQ 一致性属于同一自动恢复状态机，拆开会产生中间双真源 | T0 | M |
| T2 | 交付受控 Manual Replay 端到端操作 | Replay 的状态/CAS/Outbox/审计、owner API、确认 UI 和结果展示必须作为一个危险操作纵切验收 | T1 | M |
| T3 | 落地全链路 deadline、预算、限流与资源池隔离 | 这些规则共同决定一次执行何时允许继续、何时以 `BUDGET_EXHAUSTED` 终止，应共享一份最小执行策略而非多个抽象层 | T1 | M |
| T4 | 交付只读可靠性运维仪表 | MySQL/Rabbit 聚合查询、脱敏 API 和同工作台视图共同构成可操作性纵切，不单独建设监控平台 | T1、T3 | M |
| T5 | 完成故障注入、并发/SSE 验收与 M4 退出 | 恢复率、零丢失、零重复高成本调用、浏览器状态和运行手册必须在同一候选版本上形成发布证据 | T2-T4 | M |

详细验收标准、验证命令和预计改动区域见 `tasks/M4/TODO.md`。

## 检查点

### Checkpoint A：自动恢复真源（T0-T1）

- Job、阶段 attempt、错误分类、`nextRetryAt`、恢复 lease 和待发布事件都以 MySQL 为真源；RabbitMQ、Redis 和进程内计时器丢失后可重建。
- Worker/应用在 PROBING、CONTEXT_BUILDING、INDEXING 的可恢复点退出后，同一 Job 自动续跑；已完成且指纹一致的高成本阶段调用增量为 0。
- 自动重试、未知结果、不可重试和重试耗尽具有稳定且互斥的语义；消息进入 DLQ 时 Job 最终状态和失败审计可关联。

### Checkpoint B：受控人工操作与资源硬门（T2-T3）

- 只有 owner 能对满足条件的 `DEAD_LETTERED` Job 发起 Replay；并发双击、重复幂等键和旧版本请求只产生一个新 attempt/Outbox 事件。
- Replay 复用视频固有且指纹一致的 Context/索引产物，从失败目标阶段继续；不得重做已完成 ASR/OCR/Embedding，也不得修改历史 attempt。
- deadline、调用次数、输入/输出计量和已知成本任一硬门超限都阻止下一次高成本调用；Job pipeline 通过统一迁移入口、同步问答通过既有 answer/model-call 真源持久化 `BUDGET_EXHAUSTED`，不能破坏 READY Job；资源池饱和只能有限等待或稳定失败。

### Checkpoint C：可观察、可验证的 V1 MVP（T4-T5）

- 工作台能区分“自动恢复中 / 等待重试 / 需人工 Replay / 不可恢复 / 预算耗尽”，危险操作有确认和审计结果；刷新与 SSE 重连不改变事实。
- 运维视图能从真实数据展示积压、失败分类、阶段耗时、供应商错误及调用/成本，并明确标识价格不可得；不泄露 Prompt、模型原始响应、对象 key 或其他 owner 内容。
- 故障注入无任务丢失、无重复高成本阶段；可恢复样本成功率至少 95%；全部非法迁移被拒绝；完整质量门和真实浏览器证据有版本化记录。

## 明确不做

- 不实现 Temporal/Camunda/Quartz 集群、通用 DAG、Saga 框架、事件溯源、跨服务协调或多节点 leader election。
- 不为 Retry、Recovery、Replay、Budget 分别创建策略接口族、工厂、注册表和通用基类；只保留真实基础设施边界需要的窄 Port。
- 不提供批量 Replay、修改任意 Job 状态、删除 DLQ 消息、跳过 Checkpoint/证据门、自动重试 `UNKNOWN_OUTCOME` 或无限提高重试次数的能力。
- 不建设多租户管理员/RBAC、独立运维后台、Prometheus/Grafana/ELK 平台、短信/邮件告警或公网运维入口。
- 不进入 M5 数据集扩样、M6 Agent Loop、对话流式 token、多供应商路由或在线 Prompt/权重调整。
- 不通过降低恢复率、减少故障样本、放宽覆盖率或跳过 Docker/浏览器验收来宣布 M4 退出。

## 主要风险与控制

| 风险 | 影响 | 控制 |
| --- | --- | --- |
| RabbitMQ DLQ 与 Job 状态各自变化 | 出现“消息已死信但页面仍处理中”的双真源 | T1 以 MySQL 迁移和 Outbox 事件收敛 Job，Broker 只承载投递；加入断点故障测试 |
| 恢复扫描器与消费者并发执行 | 同一阶段重复调用或状态冲突 | 数据库 CAS + 短 lease + 稳定输入指纹；Redisson 锁只作优化，不作正确性依据 |
| UNKNOWN 被当成普通临时失败 | 重复付费或重复生成产物 | UNKNOWN 默认进入人工关注，不自动重发高成本调用；仅可依据已持久化结果对账恢复 |
| Replay 清理范围过大 | 丢失可复用视频产物并重复计费 | 按阶段输入指纹复用 Context/索引，只新建 attempt 和目标相关状态；历史记录只读 |
| 成本字段不可得导致预算失效 | 无法限制供应商费用 | 调用次数、计量和 deadline 永远生效；未知价格显式展示，不按 0 计费 |
| 资源池饱和把故障扩散到 Web/SSE | 工作台和健康检查失去响应 | 消息、外部模型、子进程与 Web 请求隔离有界；拒绝/超时有稳定错误语义 |
| 运维 API 暴露敏感数据 | owner 内容或内部凭据泄露 | 只返回聚合值和稳定错误码；owner 操作鉴权，禁止 Prompt/原始响应/对象 key |
| 小样本恢复率被过度解读 | 95% 结论不可信 | 固定场景、次数和分母，报告逐例结果与限制；工程基线不包装成生产 SLA |

## T0 需要冻结的参数

- 可自动恢复的状态/阶段、停滞阈值、lease 时长、最大自动重试次数、退避序列和 `nextRetryAt` 口径。
- `RETRYABLE`、`NON_RETRYABLE`、`UNKNOWN_OUTCOME`、`CANCELED`、`BUDGET_EXHAUSTED` 到 Job 状态和用户文案的唯一映射。
- Manual Replay 的允许前置状态、attempt 递增规则、幂等键、确认字段、审计字段、Checkpoint 复用/失效规则和目标相关清理范围。
- Job deadline、各 operation 最大调用次数、输入/输出计量、已知成本上限、并发/队列上限和资源池饱和行为；所有阈值配置化并进入执行 Trace。
- 运维聚合窗口、阶段耗时口径、积压定义、成本不可得展示和敏感字段清单。
- 故障注入样本分母及通过规则。建议至少 20 次可恢复执行并覆盖应用/Worker 终止、MQ 重投、Redis 丢失、供应商临时错误和资源饱和；至少 19 次成功才满足 95%，同时任何任务丢失或重复高成本调用均直接失败。
