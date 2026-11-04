# VideoPilot Redis 使用边界与演进设计

> 文档状态：Architecture Baseline v1.0
> 对照日期：2026-11-04
> 对照项目：`C:\Users\zzm\Desktop\com\DoVideoAI`（只读参考）
> 适用范围：VideoPilot M1-M8

## 1. 目的

本文回答三个问题：

1. 参考项目 DoVideoAI 如何使用 Redis，VideoPilot 当前如何使用 Redis；
2. 哪些做法可以吸收，哪些做法会破坏 VideoPilot 的可靠性不变量；
3. M2-M8 在什么条件下引入哪些 Redis 能力，以及 Redis 故障时系统必须如何退化。

本文细化既有架构，不改变 ADR-0001、ADR-0002、ADR-0003 和 `CONSTRAINTS.md` 已接受的原则，因此不单独新增 ADR。若未来决定让 Redis 成为会话、上传、Checkpoint、预算、事件或 Agent Trace 的唯一真源，属于跨模块且难以逆转的架构变化，必须先新增 ADR。

## 2. 核心结论

VideoPilot 将 Redis 定位为**可丢失的加速与协调层**，不定位为业务状态数据库：

- MySQL 保存权限、资源归属、上传状态、Job、阶段执行、Checkpoint、事件、预算、调用审计和 Agent Trace；
- Redis 只保存可以从 MySQL 重建、自然过期或丢失后不改变业务结论的数据；
- Redisson 锁只降低并发冲突，数据库唯一约束、状态/version CAS 和输入指纹才负责最终裁决；
- Redis Pub/Sub 只负责通知“有新事件可读”，MySQL `JobEvent` 的 sequence 和历史负责断线补拉；
- Redis 限流负责短时间流量整形，持久预算、费用和调用次数仍由 MySQL 的确定性记录约束；
- 清空 Redis 后，登录限流窗口可以重新开始、缓存可以变冷、锁可以重新竞争，但任何已提交业务事实都不能消失、倒退或被重复高成本执行。

一句话边界：**Redis 可以决定“现在是否值得更快地尝试”，不能决定“业务上是否已经发生”。**

## 3. 参考项目与 VideoPilot 对比

以下结论来自两边实际代码，而不是仅依据依赖清单。

| 能力 | DoVideoAI 参考项目 | VideoPilot 当前实现 | VideoPilot 结论 |
| --- | --- | --- | --- |
| 登录会话 | Token 到用户映射主要存 Redis，并设置过期时间 | 会话哈希、CSRF、过期与撤销状态在 MySQL | 保持 MySQL 真源；不得迁移为 Redis-only Session |
| 登录防爆破 | Redis 计数和窗口 | Redis Lua `INCR + PEXPIRE`，Redis 故障时登录失败关闭 | 保留；这是安全控制，不要求从 MySQL 重建计数 |
| 上传分片 | Redis Hash/Set 保存上传元数据、已上传分片和完成映射，Redisson 控制合并 | 上传会话、分片状态和完成事实在 MySQL，MinIO 保存对象 | 不照搬；Redis 丢失不能破坏断点续传或使完成状态消失 |
| 分析提交去重 | Redis `SETNX` active key 是入口去重的重要依据 | Redis active key 只快速命中，MySQL owner-scoped 唯一约束和事实复核兜底 | 保持当前设计；Redis 命中不能跳过 owner-scoped MySQL 校验 |
| 消费并发 | Redisson 锁并配合运行状态 | Redisson 短租约 + MySQL 状态/version CAS + 阶段输入指纹 | 保持当前设计；锁不是完成事实或幂等证明 |
| 重试次数 | 部分任务使用 Redis `INCR` 和 TTL | Job/阶段 attempt、错误分类和事件在 MySQL | 不照搬 Redis-only 重试预算；重启或清库后不得获得额外昂贵重试 |
| Checkpoint | MySQL 真源、Redis 热缓存；数据库 CAS 后刷新缓存 | 当前直接读写 MySQL Checkpoint/阶段事实 | 可吸收其 cache-aside 思路，但仅在 M4 性能证据出现后引入 |
| 媒体列表与摘要 | Redis 缓存列表、MD5 和部分查询结果 | 当前 owner-scoped MySQL 查询 | M3 以后按测量结果选用短 TTL 缓存；先解决失效和 owner 隔离 |
| Context 复用 | Redis 内容哈希索引和 Redisson 构建锁，失败时回到正常构建 | M2 以 MySQL 输入指纹、工作项 CAS 和 Context 事实为准 | 可增加短租约减少同指纹并发，但复用资格必须由 MySQL 复核 |
| 转录运行状态 | 部分 active/state 在 Redis，最终转录进 MySQL | 工作项、通道状态、Checkpoint 和 Context 均计划持久化 MySQL | 不照搬；Redis 清空不能让进行中阶段变回 `NOT_STARTED` |
| SSE 跨实例事件 | Redis Pub/Sub 扩散本地订阅者，非持久 | M1 从 MySQL `JobEvent` 按 sequence 补拉，当前不依赖 Redis 推送 | M4 可增加 Pub/Sub 热唤醒，但历史、顺序和重连仍读 MySQL |
| Agent Trace/Telemetry | 内存 + Redis 保存带 TTL 的实时快照和索引 | Roadmap 要求 `AgentRun/AgentStep/ToolCall/ModelCall` 持久化 MySQL | M6 Redis 只做实时看板投影；审计和回放不能依赖 TTL 数据 |
| AI 限流与模式路由 | Redisson 用户级/全局限流，增强路由失败时回退通用模式 | 登录限流已实现；模型调用限流待 M4/M6 | 吸收分层限流与可选能力降级，预算与费用仍由 MySQL 硬门控制 |

参考项目展示了 Redis 在单机到多实例演进中的实用价值，但其中部分运行状态对 Redis 的依赖高于 VideoPilot 的可靠性目标。VideoPilot 只复用模式，不复制其业务源码、键名或 Redis-only 状态模型。

## 4. M1 已实现能力

### 4.1 登录失败计数

- Key：`videopilot:identity:login-attempt:{attemptKeyHash}`；
- 数据：窗口内失败次数；
- 原子性：Lua `INCR`，首次写入时 `PEXPIRE`；
- 默认窗口与阈值：由 `IDENTITY_LOGIN_ATTEMPT_WINDOW` 和 `IDENTITY_LOGIN_ATTEMPT_LIMIT` 配置；
- 故障语义：Redis 不可用时返回依赖不可用，登录失败关闭；
- 持久性：不需要重建，窗口过期或 Redis 清空后重新计数。

Key 使用散列后的尝试身份，不把用户名、来源地址组合或凭据明文写入 Redis。

### 4.2 活跃 AnalysisJob 缓存

- 正向 Key：`videopilot:analysis:active:{ownerId}:{analysisKey}`；
- 反向 Key：`videopilot:analysis:active-job:{jobId}`；
- Value：`jobId|status|version`；
- 默认 TTL：30 分钟，由配置覆盖；
- 原子性：Lua 同时维护正向/反向映射，并原子刷新或淘汰；
- 故障语义：缓存不可用或内容非法时回到 owner-scoped MySQL 查询；
- 真源：MySQL 的 Job、owner、analysisKey、idempotencyKey、状态和 version。

该缓存只能减少重复提交路径的数据库读取。即使命中，也必须以 owner-scoped MySQL 事实复核；即使丢失，也不能创建第二个逻辑执行。

### 4.3 AnalysisJob 消费锁

- Key：`videopilot:analysis:job:{jobId}`；
- 类型：Redisson 有限等待、有限租约锁；
- 目的：减少同一 Job 的并发消费者冲突；
- 故障语义：锁不可用时由数据库状态/version CAS 继续裁决；
- 真源：统一状态迁移入口、阶段唯一约束和输入指纹。

租约到期、Redis 重启或误释放最多增加一次数据库竞争，不能使两个消费者都获得阶段执行资格。

### 4.4 明确未放入 Redis 的数据

M1 已将以下事实保留在 MySQL：

- 用户会话、CSRF、过期和撤销；
- 媒体资源归属、上传会话、分片完成和媒体校验状态；
- AnalysisJob、JobEvent、阶段 attempt、Outbox、取消和失败事实；
- SSE sequence 与可补拉历史。

这些边界是后续里程碑的默认基线。

## 5. 数据分类与准入规则

### 5.1 可以进入 Redis

只有同时满足以下条件的数据才可进入 Redis：

1. 丢失后能够从 MySQL/MinIO/Qdrant 重建，或自然丢失不影响业务正确性；
2. 有明确 TTL、容量上限和 owner/版本隔离；
3. 读取方把 miss、过期、格式错误和 Redis 不可用视为正常分支；
4. 命中后仍执行必要的权限、版本和状态复核；
5. 不包含密码、Token、API Key、完整视频、完整转录、Embedding 或模型原始响应。

典型数据包括 active key、短租约锁、限流令牌、查询投影、Checkpoint 元数据热缓存、SSE 热事件提示和实时仪表投影。

### 5.2 禁止作为 Redis-only 数据

以下数据不得只存在 Redis：

- 登录会话与撤销事实；
- 资源 owner、上传会话、已上传分片和上传完成事实；
- Job 状态、Checkpoint、阶段输入指纹、重试/Replay 预算；
- Outbox、JobEvent sequence、SSE 历史；
- 模型调用状态、Token、费用和 `UNKNOWN` 结果；
- AgentRun、AgentStep、ToolCall、权限决策和预算消耗；
- 用户反馈、复核、评测结果、候选版本和晋级/回滚记录。

### 5.3 敏感与大对象限制

- Key 中优先使用 UUID、不可逆摘要和稳定版本号；不得出现密码、会话 Token、API Key 或用户输入原文；
- Value 只保存最小投影或对象引用，不保存完整转录、Prompt、模型响应、视频帧和 Embedding；
- 对用户输入派生的 Key 必须先规范化、限制长度并散列，防止键注入、超长 Key 和隐私泄露；
- 日志只记录能力名、Key 类型、耗时和结果分类，不打印完整 Key/Value。

## 6. 一致性与实现模式

### 6.1 查询缓存：Cache-aside

```text
read request
  -> Redis hit
      -> validate schema/version
      -> owner/status check where required
      -> return projection
  -> Redis miss/unavailable/invalid
      -> read authoritative store
      -> return result
      -> after successful read, best-effort refill with TTL
```

写路径先提交 MySQL，再在事务提交后删除或刷新缓存。禁止在数据库事务提交前写入“已完成”缓存；缓存刷新失败只记录指标，不回滚已经提交的业务事务。并发回填可使用短租约或随机 TTL 抖动避免击穿，但不能用锁代替数据库版本检查。

### 6.2 协调锁：Redisson + 数据库 CAS

```text
message/request
  -> try short Redis lease
  -> load MySQL state and expected version
  -> claim with CAS / unique constraint
  -> perform bounded work
  -> commit result + checkpoint/event/outbox
  -> release lease (or let it expire)
```

Redis 锁的结果只有 `ACQUIRED/BUSY/UNAVAILABLE`。`ACQUIRED` 不是执行资格证明；`UNAVAILABLE` 也不能绕过数据库 CAS。租约时间必须有限，业务执行不得依赖无限 watchdog 才能恢复。

### 6.3 SSE 热事件：Pub/Sub 唤醒 + MySQL 回放

M4 若需要跨实例低延迟推送，采用以下模型：

1. 业务事务写入 `JobEvent` 和 Outbox，分配单调 sequence；
2. 提交后发布只包含 `jobId`、最新 sequence、event type 和 trace 标识的 Redis 通知；
3. 收到通知的实例从 MySQL 读取 `sequence > lastSeen` 的 owner-scoped 事件，再发送给浏览器；
4. Pub/Sub 丢消息、重复消息或乱序时，浏览器通过 `Last-Event-ID` 和 MySQL 历史补齐；
5. Redis 不可用时退回当前数据库轮询/重连路径，Job 继续执行。

Pub/Sub 消息不是事件本身，不携带完整 payload，不参与审计，也不能推进状态机。

### 6.4 限流与持久预算分离

Redis/Redisson 适合用户级、供应商级和全局的秒/分钟窗口流量整形；MySQL 负责 Job/Run 的调用次数、Token、费用、deadline 和 Replay 次数硬门。

| 场景 | Redis 不可用时的默认行为 |
| --- | --- |
| 登录防爆破 | 失败关闭，返回稳定依赖不可用错误 |
| 必需且高成本的供应商调用 | 不无保护地放行；受控延迟/重试或稳定失败，由持久预算保持不变 |
| 可选增强能力、模式路由 | 降级到基础路径，例如 `GENERAL`，不阻断核心闭环 |
| 普通查询缓存 | 失败开放到权威存储 |
| 协调锁 | 回到数据库 CAS；若无法安全 claim，则延迟重试 |

### 6.5 版本化与失效

所有业务缓存 Key 或 Value 必须带足以判断兼容性的版本信息：

- schema version：Value 结构版本；
- source version：MySQL 行 version、updatedAt 或事件 sequence；
- algorithm version：extraction/alignment/embedding/retrieval/policy 等版本；
- owner scope：涉及用户资源的缓存必须按 owner 隔离。

版本变化优先通过新 Key 空间自然冷启动；不能仅靠批量扫描删除旧 Key。任何没有 TTL 的新业务 Key 都需要在设计评审中说明理由、容量上限和清理责任。

## 7. M2-M8 能力演进

| 里程碑 | Redis 能力 | 权威事实 | 引入条件与验收 |
| --- | --- | --- | --- |
| M2 VideoContext | 默认继续使用现有 Job 锁；仅在真实并发证据出现时增加 `mediaId + inputFingerprint` 短租约，减少重复派生/Context 构建 | MySQL 工作项、分片 Checkpoint、Context、Segment、通道状态；MinIO 派生物 | 清空 Redis 后从 MySQL 恢复，已完成 ASR/OCR 调用增量为 0；锁丢失时 CAS 仍单赢家 |
| M3 检索与问答 | 可选缓存 owner-scoped Context/Chunk 摘要、检索配置投影或短期查询结果；不缓存完整转录/Embedding/答案真源 | MySQL、MinIO、Qdrant、Answer/Run trace | 只有测量证明热点后引入；版本或 owner 不同绝不复用；Qdrant 降级不依赖 Redis |
| M4 可靠性门 | Checkpoint 元数据热缓存、跨实例 SSE Pub/Sub 唤醒、用户/供应商/全局限流、运维热指标 | MySQL Checkpoint/JobEvent/预算/ModelCall；Outbox | Redis flush、重启和网络中断故障测试必须通过；SSE sequence 可补齐；缓存命中与直读结果一致 |
| M5 评测 | 仅缓存按数据集版本 + 全部配置指纹确定的可重建只读产物或报告投影 | EvaluationRun、逐题 Trace、报告文件 | test/dev 隔离进入 Key；缓存不得隐藏重新评测，最终指标和报告不依赖 TTL 数据 |
| M6 Agent Loop | 实时 Agent 轨迹/仪表投影、Tool/模型调用流量整形、短租约执行协调 | AgentRun、AgentStep、ToolCall、ModelCall、预算和终止原因 | Redis 清空后仍可完整回放；限流令牌丢失不能重置持久预算；越权调用始终由 Java/MySQL 拒绝 |
| M7 反馈闭环 | 可选复核队列计数、列表投影和短期去重提示 | UserFeedback、复核、challenge set 归属和审计 | 未复核反馈绝不能因缓存状态进入生产；列表 miss 回源不改变裁决 |
| M8 晋级/回滚 | 可选候选对比投影、当前版本读取缓存和发布通知 | 候选版本、评测结果、人工确认、晋级和回滚历史 | 启用版本必须从持久记录解析；Redis 丢失不能自动晋级、回滚或改变历史 |

M2 不为“以后可能有用”提前增加 Checkpoint 热缓存。先完成真实视频与恢复验收；当 M4 的查询次数、延迟或数据库压力表明需要缓存时，再按第 6.1 节引入并以故障测试证明透明降级。

## 8. Key、TTL 与容量规范

推荐格式：

```text
videopilot:{domain}:{capability}:v{schemaVersion}:{scope...}
```

现有 M1 Key 保持兼容；新增 Key 采用版本段。示例：

```text
videopilot:analysis:checkpoint:v1:{jobId}:{stage}
videopilot:analysis:event-hint:v1:{jobId}
videopilot:context:build-lock:v1:{mediaId}:{inputFingerprint}
videopilot:retrieval:query:v1:{ownerId}:{retrievalVersion}:{queryHash}
videopilot:agent:live-trace:v1:{runId}
videopilot:limit:model:v1:{provider}:{ownerId}
```

TTL 由能力配置，不在业务代码中散落魔法数字。初始建议只用于设计和容量估算，必须经基准测试后冻结：

| 数据类型 | 建议 TTL/租约 | 备注 |
| --- | --- | --- |
| 登录失败计数 | 与安全窗口一致 | 到期后自然解封 |
| active job | 15-30 分钟并在有效迁移后刷新 | 终态立即 best-effort 淘汰 |
| 执行/构建锁 | 秒到分钟级有限租约 | 小于最坏业务耗时时应依赖 CAS，而非无限续租 |
| 查询/摘要缓存 | 1-30 分钟 + 随机抖动 | owner 与版本隔离 |
| Checkpoint 元数据缓存 | 10-60 分钟 | 不缓存大 payload；命中仍校验版本 |
| SSE 事件提示 | 秒到分钟级或纯 Pub/Sub | 历史始终在 MySQL |
| 实时 Agent 投影 | 小时到 7 天 | 仅看板便利，不用于审计回放 |

每类 Key 必须提供命中、miss、错误、回源、淘汰和估算内存指标。禁止依赖 `KEYS` 扫描处理在线请求；管理任务使用带前缀的 `SCAN`，并设置批次和 deadline。

## 9. 故障语义与验收矩阵

| 故障 | 必须保持的结果 |
| --- | --- |
| `FLUSHDB` 或 Redis 容器重建 | MySQL 中用户会话、上传、媒体、Job、Checkpoint、事件、预算和 Trace 不变；缓存逐步回建 |
| Redis 短时不可达 | 普通查询回源；Job 由数据库 CAS 收敛；登录失败关闭；可选增强降级 |
| 锁在执行中到期 | 第二个执行者无法绕过阶段 CAS/唯一约束；不会产生第二个完成事实 |
| 缓存中是旧 version | 识别后 miss/淘汰并回源；不能覆盖较新的 MySQL 行 |
| Pub/Sub 丢失/重复/乱序 | 按 MySQL sequence 补拉、去重和排序；浏览器最终看到连续历史 |
| 限流令牌丢失 | 持久预算和已记账调用不重置；高成本调用不会因清库获得额外额度 |
| Redis Value 损坏或 schema 不兼容 | 当作 miss，记录有限指标并回源；不得反序列化为错误业务事实 |

每次新增 Redis 能力至少包含：

1. 正常命中与 miss 测试；
2. Redis 不可用和清空测试；
3. 旧 version/坏 Value 测试；
4. 并发、租约过期或重复通知测试；
5. owner 隔离和敏感信息检查；
6. 与禁用 Redis 加速路径的结果等价性测试。

M4 退出前必须在 Compose 环境完成一次“运行中停止 Redis -> 继续/受控失败 -> 恢复 Redis -> 自动回建”的真实验收，并记录数据库事实、外部调用增量和 SSE sequence。

## 10. 禁止模式

- 用 Redis `SETNX` 成功代替 MySQL 唯一约束或状态 CAS；
- 在 Redis 中标记完成后再异步写 MySQL；
- 把 Redis 锁持有者视为 owner 或权限证明；
- 仅用 Redis 计数控制 Replay、模型费用或 Agent 预算；
- 把 Pub/Sub 当作可回放事件总线；
- 把会话撤销、上传完成、Checkpoint 或 Trace 只存 Redis；
- 缓存完整视频、转录、Embedding、Prompt 或供应商原始响应；
- Redis 故障时静默无限放行高成本调用；
- 新增无 TTL、无容量上限、无版本和无清理责任的业务 Key；
- 为提高缓存命中率而跨 owner 或跨模型/Prompt/算法版本复用结果。

## 11. 变更检查清单

新增或修改 Redis 能力前逐项回答：

- 权威数据在哪里？清空 Redis 后如何重建？
- miss、超时、格式错误和连接失败分别如何处理？
- 是否存在 owner、状态、schema 和算法版本复核？
- 数据库提交与缓存刷新/失效的先后关系是什么？
- 锁失效时，哪个数据库约束负责最终裁决？
- TTL、最大 Value、最大 Key 数和淘汰策略是什么？
- 是否包含凭据、用户原文、大对象或不必要的隐私？
- 是否有 Redis flush、网络中断、并发和旧缓存测试？
- 新能力是否改变 ADR 中的真源、身份、状态机、预算或消息语义？若改变，是否先新增 ADR？

只有这些问题都有可验证答案，Redis 优化才可进入里程碑实现。
