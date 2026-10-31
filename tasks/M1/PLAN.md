# M1 实施计划：上传与异步 Job 最小闭环

## 目标与当前基线

M1 要在 M0 已冻结的契约上交付第一个真实用户闭环：用户登录后上传本地教学视频，系统保存资源归属和媒体元数据，通过统一状态迁移与 Transactional Outbox 把 `AnalysisJob` 可靠投递到 RabbitMQ，Consumer 在至少一次投递下幂等推进任务，并由持久化事件向页面提供可补拉的 SSE 进度、取消和失败信息。

当前 `main` 已完成 T0-T6：M0 退出、M1 合同、本地身份/资源归属、Job 领域状态机、MySQL 原子迁移、幂等 Job 提交、事务 Outbox、RabbitMQ 可靠发布，以及手动 ACK 幂等 Consumer 均已有测试证据；登录与会话恢复页面作为 T2F 首个前端验收面交付。MinIO 业务 Adapter、FFprobe、SSE 和后续 M1 操作面仍待实现。

M0 已于 2026-10-29 由项目负责人确认退出。远端 CI run `40837414790` 的 backend、frontend、integration 与 Gitleaks 已通过；OWASP Dependency-Check 仍发现 CVSS 7 以上依赖漏洞，项目负责人明确接受该遗留风险并允许进入 M1。安全 Job、报告和 CVSS 7 阻断阈值保持不变，该风险不视为已修复。

## 实施边界与决策

- M1 只实现不调用 LLM/ASR/OCR/Embedding 的可靠任务薄切片；M2 才接入 VideoContext 和真实模型调用。
- 严格复用 ADR-0001/0002/0003/0005。若实现需要改变任务身份组成、合法状态迁移或消息语义，先新增 ADR，不在代码中静默偏离。
- Controller 只调用 application service；领域模块定义 Port，MyBatis-Plus、MinIO、RabbitMQ、Redis/Redisson 和进程调用均位于 infrastructure Adapter。
- MySQL 是用户归属、媒体、Job、阶段和事件的真源。Redis active key 与 SSE 热事件只做加速，清空 Redis 后可从 MySQL 恢复。
- 上传接口先抽象为 upload session，由后端签发限定 object key 的 MinIO 上传凭据；前端不接触 MinIO 密钥。具体采用单 PUT 还是 multipart，由 T1 根据 M1 验收视频大小冻结，但外部 API 不暴露 MinIO 实现细节。
- 登录采用本地/单服务器范围的最小账号与服务端会话方案；密码只保存强哈希，所有媒体和 Job 查询都以当前用户作为强制过滤条件。
- `Idempotency-Key` 由客户端为一次提交意图生成并在重试时复用。数据库原子唯一约束裁决并发；同 key/同请求返回同一 Job，同 key/不同请求明确报冲突。
- SSE 使用 MySQL 中单调 `job_sequence` 作为可恢复游标，支持 `Last-Event-ID`；Redis 仅用于降低热路径延迟，不能成为补拉真源。
- M1 的假任务沿已接受的状态图推进，不生成伪造的 ASR/OCR/索引产物。它只证明调度、状态、幂等和可观测链路。
- 用户可见能力采用“后端薄片 + 紧随的前端验收面 + 浏览器证据”；纯内部可靠性能力继续由单元/集成/故障测试证明，并在下一个可操作页面显示真实服务端结果，不新增调试端点或前端伪状态。
- 前端延续 Roadmap 的“教学档案工作台”语言：暖纸色、墨色、深绿主操作、橙色强调，中文衬线标题与清晰正文；优先信息层级、键盘可用和响应式，不堆叠装饰性卡片。

## 依赖图

```text
T0 M0 退出门
  -> T1 M1 验收场景与 API/消息契约
      -> T2 身份登录与资源归属 -> T2F 登录/会话前端验收面
      -> T3 Job 领域模型、状态机与 V2 迁移
          -> T4 幂等提交与事务 Outbox
              -> T5 RabbitMQ 拓扑与 Outbox Relay
                  -> T6 Consumer、Redisson 锁与 CAS 幂等
                      -> T7 Job 查询、SSE 补拉、取消和失败语义
                          -> T7F Job 状态/SSE/取消前端验收面
                              -> T8 假任务浏览器纵向闭环
      -> T9 MediaAsset 与 MinIO 上传闭环
          -> T9F 上传前端验收面
          -> T10 FFprobe 校验与 Job 接入 -> T10F 媒体校验前端验收面
T4 + T10F -> T4F Job 提交/查询前端验收面 -> T7F
T8 + T9F + T10F -> T11 真实上传/进度浏览器纵向闭环
T6 + T7 + T10 -> T12 Redis active key 可重建加速
T8 + T11 + T12 -> T13 M1 可靠性与权限验收
T13 -> T14 文档、质量门与退出记录
```

## 任务索引

详细验收标准、验证命令和预计文件见 `tasks/M1/TODO.md`。

| ID | 薄切片 | 依赖 | 规模 |
| --- | --- | --- | --- |
| T0 | 关闭 M0 外部门禁并取得人工确认（已完成） | 无 | S |
| T1 | 冻结 M1 验收场景与 REST/SSE/MQ 契约（已完成） | T0 | M |
| T2 | 本地账号登录与资源归属（已完成） | T1 | M |
| T2F | 登录、会话恢复与退出前端验收面（已完成） | T2 | S |
| T3 | Job 领域模型、统一状态机与前进迁移（已完成） | T1 | M |
| T4 | 幂等创建 Job 与事务 Outbox（已完成） | T2、T3 | M |
| T4F | Job 提交/查询前端验收面 | T4、T10F | S |
| T5 | RabbitMQ 拓扑与可靠 Outbox Relay（已完成） | T4 | M |
| T6 | 幂等 Consumer、Redisson 锁与阶段 CAS（已完成） | T5 | M |
| T7 | Job 查询、持久化 SSE、取消与失败 | T6 | M |
| T7F | Job 状态、SSE、取消和失败前端验收面 | T7、T4F | M |
| T8 | 假任务浏览器纵向闭环 | T7F | S |
| T9 | MediaAsset 与 MinIO upload session | T2 | M |
| T9F | 视频选择、分片上传与进度前端验收面 | T9 | M |
| T10 | FFprobe 校验并连接真实媒体与 Job | T3、T9 | M |
| T10F | 媒体校验状态前端验收面 | T9F、T10 | S |
| T11 | 真实上传与进度浏览器纵向闭环 | T8、T9F、T10F | S |
| T12 | Redis active key 与缓存丢失重建 | T6、T7、T10 | S |
| T13 | 重投、重启、并发、越权集成验收 | T8、T11、T12 | M |
| T14 | M1 文档、全量质量门与退出记录 | T13 | S |

## 检查点

### Checkpoint A：契约与真源（T0-T4）

- M0 退出门有成功证据与人工确认；OWASP 遗留风险被显式记录且未通过降低阈值隐藏。
- REST/SSE/MQ 契约、错误语义和幂等行为已由失败测试固定。
- 所有 Job 状态写入只能经过 `JobTransitionService`，且状态、阶段、事件/Outbox 同事务。
- 登录、会话恢复、错误和退出已通过前端测试与真实浏览器联调，密码/会话 Cookie 不进入客户端存储。

### Checkpoint B：可靠假任务（T5-T8）

- 不调用任何模型的任务可以从提交走到终态，页面能显示实时与补拉进度。
- Outbox 发布失败可重试；重复消息是安全 no-op；取消和失败使用稳定语义。
- 应用重启后可从 MySQL 查询 Job 和历史事件。
- Job 页面只显示服务端状态和持久化事件，桌面/手机视口、键盘主路径、Console 与网络请求均有浏览器证据。

### Checkpoint C：真实上传（T9-T12）

- 登录用户可上传视频到 MinIO，完成后得到可信媒体元数据和 FFprobe 校验结果。
- 对象 key 由服务端生成，用户不能借上传/读取接口访问其他用户对象。
- Redis 清空后，active 状态和 SSE 补拉仍可由 MySQL 重建。
- 用户能在同一浏览器工作台完成选择文件、分片上传、媒体校验、提交 Job 和刷新恢复，且不会看到或持有 MinIO 长期密钥。

### Checkpoint D：M1 退出（T13-T14）

- 重复提交只创建一个逻辑执行，MQ 重投不重复推进。
- 重启后 Job 仍可找到并继续展示；用户 B 无法枚举或读取用户 A 的媒体和 Job。
- 后端快速测试、集成测试、前端 check、安全检查和 M1 验收记录均满足 `CONSTRAINTS.md`。

## 主要风险与控制

| 风险 | 影响 | 控制 |
| --- | --- | --- |
| 已接受的 OWASP 高危依赖遗留风险延续到 M1 | 已知漏洞可能影响后续功能与发布判断 | 保留安全 Job、报告和 CVSS 7 阈值；依赖变更时重新扫描，不把风险接受写成扫描通过 |
| 大视频经应用中转导致内存/磁盘压力 | 上传失败或阻塞 Undertow | upload session + 限定对象 key；流式上传；冻结大小/超时/分片策略 |
| `Idempotency-Key` 只查后写 | 并发重复 Job 和重复成本 | MySQL 唯一约束原子裁决，并校验请求指纹 |
| Outbox 已发布但确认丢失 | Relay 重发产生重复消息 | 至少一次投递；messageId、状态/version CAS 和阶段指纹共同幂等 |
| Redisson 锁被误当作真源 | Redis 丢失后重复执行 | 锁仅降冲突；数据库状态、version 和唯一约束作最终裁决 |
| SSE 只依赖内存/Redis | 刷新、断线或重启后丢进度 | MySQL 单调 sequence + `Last-Event-ID` 补拉 |
| FFprobe 子进程卡死或泄露临时文件 | Worker 耗尽、磁盘堆积 | 有界超时、输出上限、强制销毁、`finally` 清理和失败分类 |
| 只在 Controller 做 owner 检查 | 其他入口越权 | application service/repository 查询必须携带 ownerId，集成测试双用户 |
| M1 越界实现 ASR/OCR | 同时调试可靠性与模型效果 | 假任务不调用模型；M2 能力保持未实现 |

## T1 已冻结的选择

- M1 单个验收视频最大 5 GiB，使用 MinIO multipart upload session；前端按分片获取短期预签名 URL。
- 本地账号不开放自助注册，由受控初始化流程提供；验收使用两个隔离用户。
- 假任务沿既有状态图推进到 `READY` 后停止，不伪造 M2/M3 产物，也不新增状态迁移边。
- 完整 REST、SSE、幂等、错误与 MQ envelope 合同见 `tasks/M1/ACCEPTANCE.md`。

