# M4 可靠性运行手册

本文用于本地或单服务器 V1 部署的任务恢复、故障诊断和受控 Replay。MySQL 中的 `analysis_job`、Checkpoint、`model_call`、Job Event 与 Outbox 是唯一真源；RabbitMQ、Redis 和页面状态都不能替代数据库事实。

## 1. 启动与发布前检查

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\check-toolchain.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\dev-up.ps1 -Build
powershell -ExecutionPolicy Bypass -File .\scripts\dev-check.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\run-m4-reliability-gate.ps1
```

`run-m4-reliability-gate.ps1` 每次重建独立的 `videopilot_m4_gate` 数据库，执行 20 个冻结恢复样本以及 MySQL、RabbitMQ、Redis、Outbox、Qdrant 和并发边界测试。它不会修改业务数据库，但不得把业务库配置成这个保留测试库名。发布候选还必须执行：

```powershell
cd server
.\mvnw.cmd clean test
.\mvnw.cmd verify -Pintegration

cd ..\client
npm run check

cd ..
powershell -ExecutionPolicy Bypass -File .\scripts\security-check.ps1
```

不要用放宽覆盖率、删除断言、跳过集成测试或增加 suppression 的方式处理失败。

## 2. 先判断事实在哪一层

1. 运行 `powershell -ExecutionPolicy Bypass -File .\scripts\dev-check.ps1`，区分应用、MySQL、Redis、RabbitMQ、MinIO 和 Qdrant 故障。
2. 在工作台查看 Job 的状态、阶段、可靠性说明、attempt 和事件时间线；刷新后仍相同才是持久化事实。
3. 查看“可靠性”区域的 24 小时 Outbox/队列积压、失败分类、阶段 P50/P95、供应商错误和计量。数据源不可用会显示降级，不应解释为零。
4. 需要深入定位时按 `requestId`、`jobId`、`runId` 搜索服务日志。日志不得包含凭据、Prompt、模型原始响应、转录全文或对象 key。
5. 只有确认 MySQL 状态后再检查 RabbitMQ 管理页或容器日志；不要直接重发旧消息来“修复” Job。

常用只读命令：

```powershell
docker compose ps
docker compose logs --tail 200 server
docker compose logs --tail 200 rabbitmq
curl.exe -s http://localhost:8080/actuator/health/dependencies
```

## 3. 错误到操作的唯一映射

| Job/错误语义 | 自动行为 | 操作员行为 |
| --- | --- | --- |
| `RETRYABLE` / `RETRY_WAIT` | 有限退避，到期由 Recovery 重新排队 | 观察 `nextRetryAt`；不要提前重发消息 |
| lease 过期的执行中 Job | Recovery 扫描经 CAS 领取并恢复同一 Job | 若长期不推进，检查 MySQL、Outbox 和 Worker，而非新建重复 Job |
| 自动重试耗尽 / `DEAD_LETTERED` | 停止自动执行，进入人工处理 | 核对失败原因和成本后执行受控 Manual Replay |
| `UNKNOWN_OUTCOME` | 禁止自动重发高成本调用 | 先到供应商侧核对结果/账单，再决定是否 Replay |
| `NON_RETRYABLE` | 失败且不自动恢复 | 修正输入或配置；不要 Replay 同一无效输入 |
| `BUDGET_EXHAUSTED` | 在下一次高成本调用前确定性停止 | 检查限制版本与已用计量；调整限制属于发布配置变更，不是临时重试 |
| `CANCELED` | 保持取消 | 需要处理时提交新的明确业务请求，不恢复旧 Job |

## 4. Recovery、Retry 与 DLQ

Recovery worker 默认每 10 秒扫描一次，批量读取到期的 `RETRY_WAIT` 或 lease 已过期的可恢复 Job。每次领取依赖 MySQL CAS；Redis 锁只是竞争优化。默认自动重试上限为 3，重试延迟 10 秒，执行 lease 90 秒。参数通过 `videopilot.analysis.recovery.*` 配置，修改前必须重新跑 M4 门。

- 应用重启：正常重启即可。扫描器会从 MySQL 恢复；不要清空 Job/Checkpoint/Outbox 表。
- RabbitMQ 短暂不可用：保持服务和 MySQL 在线。Outbox 保留未发布事实，RabbitMQ 恢复后 relay 会继续发布。
- Redis 丢失：允许 Redis 重建。数据库状态/version CAS 仍决定赢家，已完成阶段不会因为缓存丢失而重做。
- DLQ 有消息但 Job 仍处理中：以 MySQL 为准，检查消费失败审计与关联 messageId；不要把 DLQ 数量直接当作业务失败数。
- Outbox 长期积压：先检查 RabbitMQ readiness、publisher confirm/return 和 relay 日志，再处理 Broker；不要手工把记录改成 `PUBLISHED`。

## 5. Manual Replay

只在工作台显示“需人工 Replay”时操作。Replay 必须提供非空原因、明确确认、当前 Job version 和新的幂等键；服务端同时校验 owner、状态与 CAS。

1. 阅读失败分类、最后阶段、attempt、模型调用结果和 UNKNOWN 提示。
2. 确认可能产生新的供应商调用和费用；UNKNOWN 必须先核对供应商侧结果。
3. 在 Job 详情填写原因并确认危险操作，只提交一次。
4. 成功后核对 attempt 增加、出现新的不可变审计事件和新的 Outbox 消息。
5. `409` 表示版本/状态已变化，刷新页面重新判断；重复幂等键返回同一结果，不应再次创建 attempt。

禁止重新投递旧 messageId、修改旧 attempt、删除失败审计，或用 SQL 直接把 Job 改回 `QUEUED`。输入指纹一致的已完成 ASR/OCR/Embedding/LLM 结果应复用；发现重复高成本调用即停止发布。

## 6. 预算、资源池与积压

执行限制版本默认为 `m4-limits-v3`。按 ADR-0012，Job deadline 只约束后台解析、索引、Recovery、Retry 和 Replay；READY 后的同步 Query Embedding 与 Answer 不再因 Job 年龄失败，但仍受原 Job 的 operation 调用数、输入/输出计量、已知成本和供应商等待上限硬门约束。输入/输出计量按 ASR、OCR、Embedding、Answer 分别聚合和限额，禁止把毫秒、像素、字符和 Token 直接相加裁决。价格未知显示为 unknown，不得当作 0。`m4-limits-v1/v2` 的历史 Trace 保持原样；自 v2 起 ASR `input_units` 是音频窗口毫秒，`output_units` 是 Unicode 字符数，并额外受默认 1200 字符/分钟的单次输出率硬门约束。

`asr-v2` 将供应商显式返回的空 `text` 视为合法静音片段并记录 `output_units=0`，对齐前会跳过该片段。只有视频至少存在一个非空转写片段时才能发布；全片静音仍由 `ASR_EMPTY` 硬门拒绝。该版本变更避免短静音尾段让前序有效转写整体失败，同时不放宽无语音视频的发布条件。

输入指纹一致时，后续 Job 可以继续完成前一 Job 创建但尚未发布的 Context。此时 `source_job_id` 保留原创建者，当前 Job 的 Context stage 记录完成事实；发布仍校验 owner、media、pipeline 和 Context version CAS。若日志出现 `PublicationConflictException`，先检查这些事实，禁止改写 `source_job_id` 或放宽 CAS。

- 单个 Job 预算耗尽：保持失败事实，核对限制版本和 `model_call` 聚合。不要通过重启清零。
- 大量 provider timeout/429：降低进入量并等待 `Retry-After`；不要扩大无界线程池或自动重发 UNKNOWN。
- Worker 饱和但 Web/SSE 正常：这是资源隔离的预期行为。观察队列与阶段耗时，待容量恢复。
- Web/SSE 也失去响应：检查容器 CPU/内存、数据库连接和进程日志；这不是正常背压，需要停止发布候选并复现。
- 成本聚合为 unknown：继续用调用次数和计量诊断；补价格必须作为有版本的配置发布。

## 7. SSE 与页面诊断

Job 事件序列保存在 MySQL。浏览器重连会携带 `Last-Event-ID`，服务端从该序列之后补拉；Redis Pub/Sub 只负责热唤醒。刷新或重连后若时间线缺口：

1. 记录 Job id 与页面最后 sequence。
2. 确认 MySQL 中事件 sequence 连续且属于当前 owner。
3. 检查 SSE 请求是否携带 `Last-Event-ID`，以及响应是否为正常事件流。
4. Redis 故障时允许更新变慢，但刷新后必须能从 MySQL 恢复；否则停止发布。

## 8. 回滚

M4 的数据库变更是前进迁移，不执行 `flyway repair`、降版本或删列回滚。应用回滚前先停止新流量，等待正在执行的高成本调用结束或明确进入可恢复边界，然后保留数据库、MinIO 和消息卷：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\dev-down.ps1
```

切换到已经验证兼容当前 schema 的上一应用构建，再运行 `dev-up.ps1` 与 `dev-check.ps1`。不要使用 `docker compose down --volumes`。如果上一构建不兼容当前 schema，则保持 M4 构建、关闭受影响入口并以前进修复发布；不得破坏任务历史来换取启动成功。

回滚后核对：Job 数量不变、未发布 Outbox 仍在、正在恢复的 Job 未产生第二逻辑任务、已完成高成本调用计数未增加、工作台刷新可恢复事件。任一项不满足都视为回滚失败并停止继续操作。

## 9. 必须升级而非现场绕过的情况

- 任意任务丢失、同一逻辑 Job 被创建两次、非法状态迁移成功；
- 指纹一致的已完成高成本阶段被再次调用；
- UNKNOWN 被自动重发，或价格 unknown 被显示成 0；
- owner 能看到或操作其他 owner 的 Job；
- 为恢复服务需要删除历史、清卷、修改旧 migration 或直接 SQL 改状态。

这些情况立即停止发布，保留数据库和日志证据，按 `tasks/M4/ACCEPTANCE.md` 的故障样本复现并修复。
