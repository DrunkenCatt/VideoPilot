# M6 验收记录：最小动态工具型 Agent Loop

> 当前状态：T5 自身退出门已通过；T4/M6 因 Claim 支撑率和引用定位率未达 M5 原门槛仍不可晋级。
> `EX-2026-11-12-M6-T5` 只允许优先补齐体验闭环；Agent/目标分析保持默认关闭。OWASP 因 NVD
> 数据不可用未形成结论，负责人以 `EX-2026-11-11-M6-OWASP` 接受该项本地遗留风险并要求继续。

## 1. 冻结事实

- M5 候选：`5e71014`。
- DEV 三轮 Recall@3：均为 `100%`。
- DEV 三轮严格 Claim-Evidence 支持率：`28.57% / 46.67% / 54.55%`。
- DEV 三轮引用定位率：`82.35% / 77.78% / 84.62%`。
- TEST：分阶段硬门阻止重跑；历史 TEST 不用于 M6 调参。
- M6 默认状态：关闭、实验性；非 Agent 回答仍为默认路径。

## 2. T0 验收场景

### 场景 A：流程例外不冒充质量通过

**Given** M5 两项效果门失败；**When** 开始 M6；**Then** Roadmap、任务页和 UI 仍显示 M5 未退出，M5 阈值和失败报告不变，Agent 不默认启用。

### 场景 B：最小范围

**Given** 首版只有一个 Agent 实现；**When** 冻结架构；**Then** 只定义检索、扩窗、生成/验证三个动作和一个 application service，不创建插件 SDK、动态加载、多 Agent 或第二套检索/回答/预算框架。

### 场景 C：确定性控制

**Given** 模型提出动作；**When** owner、schema、白名单、预算、证据或状态不合法；**Then** Java 在工具/模型调用前拒绝并持久化稳定终止原因，模型不能覆盖裁决。

### 场景 D：运行时代码未改时不制造假门

**Given** T0 只修改计划和合同；**When** 当前工作台存在既有匿名 401、favicon 或浏览器环境噪声；**Then** 如实记录但不为通过文档任务修改无关运行时代码，也不创建 Run、假 Trace 或模型调用；用户可见 Agent 入口的 Console/Network 硬门从 T3 开始执行。

### 场景 E：晋级条件必须可计算

**Given** Roadmap 8.3 第三条没有冻结复杂题子集、显著收益数值和条件路由规则；**When** T4 计算晋级；**Then** Java 只自动裁决前两条数值规则，第三条只能报告观察结果，不能据此默认启用 Agent。

## 3. T0 实际证据

2026-11-10 已完成文档一致性检查和两种真实浏览器尝试：

- Compose server 与 MySQL、Redis、RabbitMQ、MinIO、Qdrant 均为 healthy；后端
  `GET /actuator/health` 返回 200。Compose 不承载前端静态页，因此另以仓库 Vite 启动
  `http://127.0.0.1:5173/`，没有修改运行时代码。
- Codex 应用内浏览器访问 `localhost:8080` 和 `127.0.0.1:8080` 均被客户端以
  `ERR_BLOCKED_BY_CLIENT` 阻止，不能作为页面或 Console 通过证据。
- 隔离的 headless Edge 在桌面和窄窗口均看到真实登录面；可见资源中没有 Agent UI 或
  `/agent` 请求，也没有执行登录、评测、问答或其他写操作，因此模型调用增量为 0。
- Edge Console 捕获到匿名会话探测 `/api/v1/sessions/current` 的预期 401、应用
  `/favicon.ico` 的 404，以及 Edge 新配置页自身的 MSN 404。当前结果不满足“Console
  error/warning 为 0”；窄窗口还未形成可信 320px 设备视口证据。
- 登录后的工作台验收需要把 Git 忽略 `.env` 中的本地测试账号密码提交给 localhost。
  本轮未在浏览器中读取 Cookie、本地存储或发送密码，也未复用个人浏览器配置。

结论：本次只读结果是 T0 的非阻断基线，不构成 Console 通过证据，也不要求为文档任务修复
既有 favicon/匿名会话行为。已生成的
截图与 Vite 日志位于 Git 忽略的 `benchmark/local/`，不会进入仓库。

## 4. T0 完成证据

2026-11-10 完成以下检查：

- `git diff --check`：通过；仅报告工作区既有 LF/CRLF 转换提示，无空白错误。
- Roadmap、Constraints、ADR-0015、M5 冻结状态和 M6 PLAN/TODO/ACCEPTANCE 的候选 commit、
  三轮指标、例外编号、默认关闭及 TEST 纪律一致。
- `docker compose ps`：server、MySQL、Redis、RabbitMQ、MinIO、Qdrant 均为 healthy。
- `GET http://127.0.0.1:8080/actuator/health` 与 `GET http://127.0.0.1:5173/` 均返回 200。
- `.env` 仍被 Git 忽略，检查只读取变量名；未输出、记录或暂存任何凭据。
- T0 未修改业务代码，因此未重复运行后端和前端测试；T1 开始后按任务门运行。

T0 退出门通过，可以进入 T1。M5 效果门仍失败，Agent 仍默认关闭。

## 5. T1 验收场景

### 场景 A：无费用创建与重复请求

**Given** 当前 owner 拥有 READY Job；**When** 用相同幂等键和等价 Goal 重复创建；**Then**
返回同一 Run，生成时间不同的 deadline 不改变请求指纹，`model_call` 不新增记录。

### 场景 B：MySQL 真源与 CAS

**Given** Run 为 REQUESTED；**When** Worker 领取、追加 Step 并终止；**Then** 只有正确 lease、版本和
连续 step number 能推进，完整版本/预算快照和去敏 Observation 可从 MySQL 回放。

### 场景 C：owner 隔离与去敏 API

**Given** Run 属于 owner A；**When** owner B 查询 Job 或 Run；**Then** 统一返回不存在。创建/读取 API
不返回 ownerId、幂等键、请求指纹、Prompt、模型原始响应或内部思考。

### 场景 D：调用前审计可落库

**Given** T2 将在外部调用前写 ToolCall；**When** V20 建表；**Then** ToolCall 只外键归属 Run，
REQUESTED/RUNNING 不依赖已完成 Step，同时状态、完成时间和 Run 内幂等键受数据库约束。

## 6. T1 实际证据

2026-11-11 在本地 Compose MySQL 8.4 上完成：

- `server\.\mvnw.cmd -Dtest=AgentApplicationServiceTest,AgentControllerTest test`：6/6 通过，
  26.6 秒；覆盖重复创建、deadline 变化、指纹冲突、owner 隔离、去敏 DTO 和稳定错误映射。
- 最终全新隔离库 `videopilot_m6_t1_final` 从 V1 前进迁移至 V20；定向执行
  `DatabaseMigrationIT,JdbcAgentStoreIT`：10/10 通过，42.0 秒。验证 Flyway no-op、表/检查约束、
  Run 内幂等、lease/version CAS、非法 lease 拒绝、终止与轨迹回放。
- `server\.\mvnw.cmd test`：413 项通过、0 failure、0 error、1 项既有 FFmpeg 环境条件跳过；
  JaCoCo 80% bundle 门和 ArchUnit 通过，最终重跑总耗时 71 秒。
- 隔离库执行后 `SELECT COUNT(*) FROM model_call` 为 `0`；T1 没有 Tool、供应商模型或前端调用，
  已知外部调用增量与未知费用均为 0。
- 首次宿主机定向命令未加载 `.env`，数据库鉴权失败；加载后又暴露无关 OCR Bean 配置，随后将
  聚焦 Store 测试显式关闭 ASR/OCR/Worker。首次隔离库连接因应用用户尚无该新库权限失败；仅授予
  `videopilot_m6_t1` 后原测试重跑通过。未删除断言、跳过被测边界或降低质量门。
- Redis 未参与 Agent Store/Service 的构造或读写，Run/Step/ToolCall 全部来自 MySQL；为避免清空
  正在使用的本地 Redis 数据，本任务以依赖结构和 MySQL 断连回放测试证明，不执行共享库 `FLUSHDB`。
- `npm audit --audit-level=high` 达到门槛：0 high/critical，报告 10 项 moderate；没有执行可能引入
  breaking change 的 `npm audit fix --force`。OWASP Dependency-Check 在后端测试、打包和覆盖率再次
  通过后，NVD 首分页连续 16 次失败且继续无上限重试，人工中止，因此未形成漏洞库通过结论，
  不把外部源不可用写成通过。Gitleaks 8.30.1 对 staged 内容扫描约 60 KB，0 leak。

接口与恢复操作见 `docs/M6_RUNBOOK.md`。T1 没有用户可见前端变化，因此按冻结任务表未运行
`client\npm run check` 或浏览器验收；它们从 T3 用户可见纵切强制执行。完整、可能触发既有真实模型
夹具的全量 `verify -Pintegration` 留在 T4；T1 已执行所有直接相关的真实迁移和 Store 集成门。

T1 退出门通过，可以进入 T2。M5 效果门仍失败，Agent 仍默认关闭。

## 7. T2 验收场景

### 场景 A：Observation 改变动作

**Given** 首次检索分别得到多条、一条或零条证据；**When** 策略读取已提交 Observation；**Then**
分别进入生成并验证、扩窗后生成、拒答或请求澄清，不执行固定的三段式工作流。

### 场景 B：调用前硬门

**Given** owner/Job、动作 Schema、step、deadline、输入/输出 Token、已知/未知费用或答案次数任一
不合法；**When** Worker 领取 Run；**Then** 在 Tool/供应商调用前以稳定原因终止，调用次数保持 0。

### 场景 C：先审计后副作用

**Given** 策略选择业务动作；**When** 执行既有 Retrieval、Context 或 Answer service；**Then** 先以
lease/version CAS 写入 RUNNING ToolCall，完成后同一事务提交 Step、调用摘要、耗时、结果引用与预算聚合。

### 场景 D：不安全恢复不重放

**Given** 租约过期时存在 RUNNING ToolCall；**When** 新 Worker 领取；**Then** 将调用标为 UNKNOWN 并以
`UNKNOWN_OUTCOME` 终止，不重放可能已产生费用的调用。

## 8. T2 实际证据

2026-11-11 完成以下验证：

- `AgentApplicationServiceTest,AgentLoopApplicationServiceTest,RuleBasedAgentPolicyTest` 共 26 项通过；
  覆盖三类路径、先写调用意图、owner/Schema/六类预算硬门、答案 Run 归属和 RUNNING 恢复停止。
- 最终干净执行 `server\.\mvnw.cmd clean test`：438 项通过、0 failure、0 error、1 项既有 FFmpeg
  环境条件跳过；JaCoCo 80% bundle 门和 ArchUnit 均通过，总耗时 66 秒。首次干净构建曾以 79%
  覆盖率失败；没有降低阈值，而是补齐 Worker、CAS、终止与错误路径测试后重跑通过。
- 专用隔离库 `videopilot_m6_t2_gate` 从空库前进迁移 V1-V20；定向执行
  `DatabaseMigrationIT,JdbcAgentStoreIT,AnswerPersistenceAdapterIT,VideoIndexPersistenceAdapterIT`
  全部通过。真实 MySQL 断言覆盖 ToolCall 先写、lease/version CAS、Step 原子完成、Token/费用聚合，
  以及 Answer/Retrieval `model_call.run_id` 归属。
- 首次误用现有 `videopilot_browser` 时发现其 V20 校验和来自较早工作区；测试按硬门失败停止，未执行
  `flyway repair` 或关闭校验，随后只在全新隔离库重跑并通过。
- 定向测试全部使用确定性 fixture/mock，没有发出供应商请求；外部调用增量、已知费用和未知费用均为
  0。运行时代码会把真实模型调用写入既有 `model_call`，无价格或 UNKNOWN 事实按非零未知调用聚合，
  下一 Step 在调用前失败关闭。
- `client\npm.cmd run check` 完整通过：18 个测试文件、115 项测试通过，类型、Lint、覆盖率和构建
  通过；`npm audit --audit-level=high --registry=https://registry.npmjs.org` 为 0 high/critical，仍有
  10 项 moderate。Gitleaks 8.30.1 扫描约 65 KB staged 内容，0 leak。
- OWASP Dependency-Check 12.2.2 在线更新 NVD 时首分页持续超时并进入重试；停止在线更新后执行
  `-DautoUpdate=false`，工具明确以 `NoDataException: No documents exist` 失败。本机没有可用 NVD
  缓存，因此依赖安全门未通过，不能把网络/数据源故障记作 0 high/critical。

T2 退出：实现、后端、前端、集成、覆盖率、架构、秘密和 npm 高危门均通过。OWASP 依赖安全门
没有有效结果；负责人在审阅该证据后明确要求跳过本次检查并继续 T3，已登记有期限的
`EX-2026-11-11-M6-OWASP`。该例外不是扫描通过，也不接受任何已知 High/Critical 漏洞；数据源
恢复或 CI 形成有效报告后仍须满足原阈值。Agent 与 Worker 仍默认关闭。

## 9. T3 验收场景

### 场景 A：恢复与单赢家

**Given** 已提交 Step、完成 ToolCall 或租约过期的 RUNNING ToolCall；**When** 重复 Worker、应用重启或
Redis 清空；**Then** MySQL CAS 只允许一个推进者，完成调用不重复，RUNNING 调用变为 UNKNOWN 后不重放。

### 场景 B：真实页面三路径

**Given** 真实 READY 视频和真实/故障注入的 Embedding 依赖；**When** 提交相关、短且不明确、长且无关
三类问题；**Then** 页面分别显示 `UNKNOWN_COST`、`NEEDS_CLARIFICATION`、`NO_SUFFICIENT_EVIDENCE`，
并展示真实预算和去敏轨迹，不展示 Prompt、参数或原始模型响应。

### 场景 C：刷新回放与默认关闭

**Given** 页面已创建 Run；**When** 刷新页面、重启应用或清空 Redis；**Then** URL 只保存 `runId`
指针，owner API 从 MySQL 恢复轨迹；仓库和 Compose 中 Agent/Worker/前端入口仍默认关闭。

## 10. T3 实际证据

2026-11-11 完成以下验证：

- `JdbcAgentStoreIT` 在全新 `videopilot_m6_t3_gate` 上通过 2/2；覆盖重复 Worker、重新创建 Store 后的
  进程恢复，以及旧的已完成 Step 不能重复提交。V1-V20 前进迁移与相关集成检查此前在同一 T3
  隔离门中 11/11 通过。
- `server\.\mvnw.cmd clean test`：439 项通过、0 failure、0 error、1 项既有 FFmpeg 环境条件跳过；
  JaCoCo 80% bundle 门与 ArchUnit 均通过，总耗时 73 秒。
- `client\npm.cmd run check`：类型、ESLint、19 个测试文件/122 项测试、覆盖率和生产构建全部通过；
  statements 87.54%、branches 82.79%、functions 89.12%、lines 92.68%。
- 真实浏览器使用 `m2-t7-smoke-8s.mp4` 的 READY Job 验证三条持久化路径：相关问题以
  `UNKNOWN_COST` 失败关闭；Embedding 连接故障下注入的短问题以 `NEEDS_CLARIFICATION` 终止，长且
  无关问题以 `NO_SUFFICIENT_EVIDENCE` 拒答。数据库最近记录对应 `FAILED/REFUSED/REFUSED`，每条
  `step_count=1`、`unknown_cost_calls=1`；没有把未知成本写成 0。
- 首次实现把费用硬门放在确定性终止之前，使拒答/澄清不可达；新增失败测试后只允许“不再产生任何
  Tool/模型调用”的终止落库，任何后续业务动作仍在调用前以 `UNKNOWN_COST` 停止。该修复不修改价格
  事实、不增加价格表，也不放松 UNKNOWN 自动重放规则。
- 页面将最新 Run 的 UUID 放入 URL 查询参数；刷新、应用容器重启和 Redis `FLUSHDB` 后，owner API
  仍从 MySQL 回放同一轨迹。Redis 清空前后 `DBSIZE` 均为 0，没有业务事实依赖 Redis。
- 桌面和 320px 视口均保留实验开关、预算、终止原因与折叠轨迹；Console 只有 Vite debug 连接消息，
  没有 warning/error。创建、轮询、刷新读取都返回可渲染响应，页面没有网络错误提示。
- 真实启动曾发现组件扫描的条件 Controller 在 Bean 注册顺序下未创建；改为沿用 Answer/Evaluation 的
  AutoConfiguration 显式 Bean 后，真实 HTTP 与聚焦 Spring/MySQL 集成测试均通过。首次尝试用空 API
  key 注入 Embedding 故障时被 ASR 启动校验拒绝，随后改为仅覆盖 Embedding base URL；没有关闭启动门。

T3 退出门通过，可以进入 T4。OWASP 仍仅由 `EX-2026-11-11-M6-OWASP` 临时覆盖本地缺失报告，
不视为安全扫描通过；Agent、Worker 和前端入口仍默认关闭。

## 11. T4 验证中止记录

2026-11-11 已完成 T4 的窄实现：V21 在既有 Evaluation Runner/报告上增加 BASELINE/AGENT 模式、
`agentPolicyVersion`、AgentRun 评测归属、答案硬门、P95、平均/未知成本、路径分布和 Java 晋级裁决；
没有新建第二套 Runner 或数据集。价格以负责人提供并由浏览器核对的 SiliconFlow 官方价格页为来源，
冻结为 `siliconflow-pricing-2026-11-11`，详细规则见 ADR-0016。

验证事实：

- `server\mvnw.cmd package`：450 项通过、0 failure、0 error、1 项宿主机 FFmpeg 条件跳过；JaCoCo
  80% bundle 门和 ArchUnit 通过。
- 全新隔离库 `videopilot_m6_t4_gate` 从空库成功前进迁移 V1-V21；T4 定向
  `DatabaseMigrationIT,JdbcAgentStoreIT,JdbcEvaluationStoreIT,AnswerPersistenceAdapterIT,
  VideoIndexPersistenceAdapterIT` 的迁移/持久化事实通过。首次运行发现建索引 BGE-M3 仍为未知价格，
  修复为与查询 Embedding 共用同一免费价格目录后重跑通过，没有修改断言。
- `client\npm.cmd run check`：19 个测试文件、122 项测试、类型、ESLint、覆盖率和构建全部通过；
  statements 87.34%、branches 82.20%、functions 88.05%、lines 92.42%。
- 宿主机完整 `verify -Pintegration` 先准确暴露缺少 FFmpeg/FFprobe、基准 commit 和依赖健康地址；随后
  在项目现有、包含 FFmpeg 的 server 镜像内，对同一全新隔离库执行未跳测试的完整命令。450 个单测、
  覆盖门和 93/94 个集成测试通过，依赖健康、真实媒体探测和 V21 均通过。
- 唯一失败为 `RetrievalBenchmarkLocalIT` 的实时回答硬断言：7 条引用只有 6 条满足复核定位规则，
  定位率 85.71%；6 个样本仅 3 个状态符合预期。失败报告保存在
  `benchmark/reports/m3-t6-answer-baseline.json`，没有降低 100% 定位断言或删除样本。

因此 T4 质量门未通过。按负责人要求已停止：未提交 localhost 登录凭据，未创建 DEV A/B series，
未执行浏览器桌面/移动比较，未申请晋级，也未提交 T4。Agent/Worker/前端默认开关保持关闭；后续必须
先修复或解释该引用定位回归并让完整门通过，才能继续同题 A/B。

### T4 引用定位回归修复与第二次完整门（2026-11-11）

- 根因不是定位窗口放宽或对齐搜索越界，而是回答提示在 12 条证据上限前按每段 `ASR -> OCR`
  截断。检索已命中后半段 Chunk，但较早 Segment 的 OCR 占满提示，`m3-p08-a05` 所需的
  360-420 秒 ASR 没有暴露给模型；模型转而引用 240-360 秒次相关内容。
- RED：新增 `coversEachRetrievedSegmentBeforeAddingSecondaryChannelsToThePrompt` 后，旧实现明确缺少
  最后两个 Segment 的 ASR；GREEN：先为每个命中 Segment 选择一条主证据，再补第二通道，
  `AnswerApplicationServiceTest` 11/11 通过。没有修改证据门、定位邻域、样本或阈值。
- 首次干净库真实基准虽然测试显示通过，但报告为 `0/0` 引用、仅 `1/6` 状态命中，并含两个
  `ERROR_DEPENDENCY_UNAVAILABLE`，属于空分母假阳性。随后把既有 M3 工程基线固化为硬断言：
  状态命中至少 `2/6`、引用数必须大于 0、且全部引用仍须定位；依赖错误不能伪装成通过。
- 在全新 `videopilot_m6_t4_gate` 上重新执行容器化 `verify -Pintegration`：451/451 单元测试、
  JaCoCo 80%、ArchUnit 以及 93/94 集成测试通过，2 项显式付费开关测试按设计跳过。唯一失败为
  `RetrievalBenchmarkLocalIT` 的状态基线断言：实际 `1/6`；a01/a02/a03/a05 为
  `CITATION_VALIDATION_FAILED`，a04 为 `DEPENDENCY_UNAVAILABLE`，仅不可回答的 a06 正确拒答，
  因而引用为 `0/0`，不能声称定位回归已经通过真实回答验收。失败报告仍保存于
  `benchmark/reports/m3-t6-answer-baseline.json`。

第二次完整门仍未通过，按依赖顺序停止；没有继续前端重跑、DEV A/B、浏览器验收或提交。下一步必须
先解释并修复结构化回答全部被证据门拒绝的问题，使非空引用和至少 `2/6` 状态基线恢复，再重跑完整门。

### T4 AnswerEvidenceGate 误拒修复与第三次完整门（2026-11-11）

- 根因一：claim tokenizer 的非中文分支 `\p{L}` 仍会吞入汉字，导致 `help命令获取帮助` 成为一个
  混合 token，无法与 claim 中独立的 `help` 对齐；修复后显式排除 Han，不改变“Claim 必须由引用
  支持”的硬门。
- 根因二：ASR 在 60 秒切段边界上会把连续原话拆到相邻 Segment，模型生成的逐字 quote 可能跨越
  两段；证据门原先只验证单段。修复只允许同 Chunk、同 channel、ordinal 连续的两个相邻 Segment，
  且 quote 两侧都必须 EXACT/NORMALIZED 对齐；持久化时拆成两个可独立定位的 `CitedEvidence`，不执行
  全视频搜索，也不放宽模糊距离或复核窗口。
- RED/GREEN：`AnswerEvidenceGateTest` 新增真实中文混排和相邻边界两类回归，旧实现 2 项失败，修复后
  7/7 通过；临时原始模型输出诊断已删除。
- 全新隔离库 `videopilot_m6_answer_fix` 的实时定向基准通过：`6/6` 状态命中，22 条非空引用，
  `22/22` 定位成功。
- 规范容器环境的完整 `clean verify -Pintegration` 快速层 `453/453`、JaCoCo 80% 和 ArchUnit 通过；
  集成层 `93/94` 通过、2 项显式付费开关测试按设计跳过。唯一失败仍为实时
  `RetrievalBenchmarkLocalIT`：`5/6` 状态命中、26 条非空引用，但 `m3-p08-a03` 仅 `5/6` 引用定位，
  汇总为 `25/26`（96.15%），没有达到 100% 硬门。MySQL 取证确认越窗项是 a03 最后一条
  `420000-434747ms` ASR 引用，其余五条位于 `120000-240000ms`；`m3-p08-a05` 同轮出现
  `ERROR_DEPENDENCY_UNAVAILABLE`。失败报告保存在 `benchmark/reports/m3-t6-answer-baseline.json`。
- 首次完整命令曾因 localhost `.env` 将 `IDENTITY_SECURE_COOKIE=false` 注入测试进程而在安全合同处
  失败；随后以质量门安全默认值 `true` 从空库完整重跑。该修正加强环境约束，没有修改代码、断言或
  阈值。

第三次完整门仍未通过，按依赖顺序停止；未执行前端门、浏览器验收、DEV A/B 或提交。当前修复已恢复
非空引用和至少 `2/6` 状态命中，但尚不能声称引用定位回归关闭；下一步需定位 `m3-p08-a03` 的单条
越窗引用及 a05 供应商失败，在不降低 100% 定位门的前提下重新验证。

### T4 引用语义校正、策略版本防漂移与第四轮验证（2026-11-12）

- `requiredCitation` 复核窗口被校正为核心参考答案，不再作为排斥真实补充 Claim 的 allowlist；引用定位
  只判断持久化时间窗是否落在视频内，引用原文与 ASR/OCR 的 EXACT/NORMALIZED 绑定仍由
  `AnswerEvidenceGate` 在持久化前强制执行。a03 的 `420000-434747ms` 原话因此是有效补充引用。
- 完整容器门曾暴露两个测试环境问题：多个 Spring Context 的后台 Evaluation Worker 争抢测试 lease，
  以及 MinIO 公网地址在容器内不可达。集成配置默认关闭后台评测领取，容器门显式使用
  `MINIO_PUBLIC_ENDPOINT=http://minio:9000`；没有修改业务质量阈值。
- 发现前端候选默认策略为旧的 `m6-rule-policy-v1`，运行时冻结版本为 `agent-policy-v1`。先补 RED 测试，
  再让前后端共享同一冻结值，并在创建候选时立即拒绝版本漂移，避免运行到第一个 ANSWER 样本才失败。
- 最终 `server\mvnw.cmd clean test` 为 457/457 通过（宿主机 1 项 FFmpeg 条件跳过）；规范容器
  `clean verify -Pintegration` 为 457 个快速测试、94 个集成测试通过，2 项既有条件跳过，JaCoCo 80%
  与 ArchUnit 均通过。最终实时报告为 `4/6` 状态命中、13 条非空引用、`13/13` 定位；此前同一修复的
  完整轮次为 `5/6`、`30/30`，两次都满足既有至少 `2/6` 且引用全定位的工程基线。
- `client\npm.cmd run check` 为 19 个测试文件、123 项测试通过，类型、ESLint、覆盖率和生产构建通过；
  statements 87.56%、branches 82.81%、functions 88.39%、lines 92.51%。
- 首组三轮旧策略候选在 73/90 处以 `VERSION_DRIFT` 失败并被排除。正确 `agent-policy-v1` 新 series 的
  三轮各完成 90/90，但运行环境给 `api.siliconflow.cn` 呈现 `Default Company Ltd` 自签名证书；Java
  TLS 校验正确拒绝连接，90 个 BGE 调用全部落为 `CONNECTION/UNKNOWN_OUTCOME`，15 个可回答样本随后
  以 `UNKNOWN_COST` 停止。候选硬门通过率仅 5.56%，成本不可得，确定性裁决为不具备晋级资格。
- 该次报告还暴露 `NULL result_rank` 经 JDBC 读取为 0 后产生 `MRR@3=Infinity/NaN`。新增 RED 测试后，
  mapper 立即保存 `wasNull`，计算器对非正 rank 防御为 miss；定向单测 4/4、MySQL 集成 1/1 以及上述
  最终完整门均通过。没有改写已完成评测事实来美化结果。

T4 仍未退出：当前需要可核验的本地代理根 CA 或恢复不被拦截的 SiliconFlow 直连，然后用新幂等键
重新运行三轮候选并完成桌面/320px 浏览器比较。不能关闭 TLS 校验、信任现场抓取的未知证书、删除
UNKNOWN 结果或降低晋级门。按依赖规则未提交 T4，也未开始目标分析结果 T5。

### T4 关闭代理后的真实 A/B 重试（2026-11-12）

- 宿主机无代理环境变量，WinHTTP 为 Direct；宿主机和 Docker 严格 TLS 请求均返回预期匿名 401。
  Docker 看到 `*.siliconflow.cn` 由 DigiCert 签发，不再出现 `Default Company Ltd` 自签名证书。
- 使用新幂等键和冻结的 `agent-policy-v1` 创建全新三轮候选。第一、二轮各完成 90/90；第三轮也写完
  90 个 CaseResult，但系列完成检查以 `NON_DETERMINISTIC_RETRIEVAL` 将 Run 标为 FAILED。
- 唯一差异为 `m5-free-media-licenses-r021`：前两轮 BGE-M3 均成功且 rank 1，第三轮供应商在 15.192 秒
  后返回 `HTTP 503`，`model_call` 如实记录 `FAILED/RETRYABLE/HTTP_503`，该题降级为未命中。TLS 已恢复，
  该失败属于供应商临时不可用，不应改写为模型随机差异或成功。

按“上一退出门不通过即停止”结束本次推进：不只采用前两轮、不删除 503 事实、不重写第三轮、不执行
浏览器验收、不提交 T4，也不开始 T5。若负责人要求继续，应创建另一组全新三轮 series，而不是修改
现有失败 series。

### T4 负责人批准后的全新三轮重试（2026-11-12）

- 负责人明确要求在 503 后重新尝试。使用新幂等键
  `m6-t4-agent-policy-v1-retry-after-503-20261112` 创建三轮，不修改上一组失败事实；Run 分别为
  `2ad87d03-5365-4276-b04e-dc88506068de`、`8da85668-acac-40a0-bfb4-609ad562acc9`、
  `c70bdf06-ea21-48c2-b1cd-ce3f9c007930`，三轮均完成 90/90。
- 三轮检索结果稳定：`Recall@3=100%`、`Hit@1=95.83%`、`MRR@3=97.92%`，三项样本标准差均为 0；
  拒答准确率为 100%，样本标准差为 0。
- 回答质量仍未达到 M5 原门槛：严格 Claim 支撑率三轮为 48.00%、36.36%、25.71%，均值 36.69%；
  引用定位率为 73.53%、75.00%、75.68%，均值 74.74%；答案硬门通过率均值 42.59%。这不是执行中断，
  而是完整样本上的正式质量结果，不能通过删除 Claim、样本或降低 85%/95% 阈值处理。
- 与冻结基线比较时，候选硬门通过率增量为 +16.67 个百分点，`ruleTwoMet=true`；但
  `ruleOneMet=false`、`m5GatesMet=false`、`promotionEligible=false`，稳定原因为
  `M5_GATES_NOT_MET`。因此“某条 M6 数值规则命中”不能覆盖 M5 原证据硬门。
- 第一轮另有 `m5-wikidata-book-zh-r122` 的 BGE-M3 检索向量调用返回 `HTTP 400`，落库为
  `FAILED/NON_RETRYABLE`，使该轮 126 次调用中 125 次成功、1 次失败，`costKnown=false`；第二、三轮
  126 次调用均成功，已知费用分别为 CNY 0.120246、0.120417。该异常不是 503，也不是费用不足信号，
  且即使成本完整也无法改变 M5 门未通过的结论。

本次三轮重试证明代理/TLS 与上一条 503 已不再阻断完整 series，但 T4 退出门仍未通过。按负责人规定
停止：不执行浏览器登录或桌面/320px 验收、不提交 T4、不开始目标分析结果 T5；Agent、Worker 与前端
入口继续保持仓库默认关闭。下一步若继续，应针对 Claim 支撑与引用定位做根因分析和修复，再以全新
series 重跑，不能降低质量门。

## 11. T5 自动目标分析结果验收（2026-11-12）

T4 已以 `8f47e9d` 独立保存不可晋级事实；负责人随后用 `EX-2026-11-12-M6-T5` 明确要求记录缺口并
优先完成 T5。该例外只改变任务顺序，不把 T4 或 M6 标记为通过，也不修改 85% Claim 支撑、95% 引用
定位、预算、权限或证据硬门。四个 Agent/目标分析开关继续默认关闭。

### 自动创建、恢复与隔离

- Worker 每 tick 最多从 MySQL 选择一个缺少目标 Run 的 READY Job，使用稳定键
  `goal-analysis:<jobId>` 调用既有创建入口；数据库唯一约束、请求指纹和 CAS 负责并发单赢家与重启去重。
- 目标结果接口按 owner 和 READY 状态读取，只接受已提交生成步骤中的严格 `answer:<uuid>`；Answer
  仍经 owner、Job 和证据关系校验。拒答或失败只返回稳定去敏原因，不伪造 Answer，也不修改 READY Job。
- 目标 Answer 仍使用标准 Answer/Claim/CitedEvidence 表，但从普通问答历史排除；页面只在独立的
  “目标分析结果”区域恢复它，不增加结果表或第二套回答管线。

### 自动化质量门

- `server\mvnw.cmd clean test`：462 项测试通过，0 failure/error；宿主机 1 项 FFmpeg 条件跳过，
  JaCoCo 与 ArchUnit 通过。
- 规范 FFmpeg 容器、全新库 `videopilot_m6_t5_gate4` 和隔离 Qdrant collection 执行
  `mvn verify -Pintegration -Dbenchmark.commit=8f47e9d`：462 项快速测试和 95 项集成测试通过，
  0 failure/error；2 项既有付费/发布条件测试按设计跳过，JaCoCo 与 ArchUnit 通过。只有该运行所需的
  SiliconFlow key 注入容器，未把整份 `.env` 注入测试进程。
- `client\npm.cmd run check`：19 个测试文件、125 项测试通过；类型检查、ESLint、覆盖率和生产构建通过。
  覆盖率为 statements 87.36%、branches 81.97%、functions 88.77%、lines 92.64%。
- `client\npm.cmd audit --audit-level=high`：退出码 0；无 high/critical，仍有 10 个 moderate。
- OWASP 依赖检查按负责人明确指示跳过，继续由 `EX-2026-11-11-M6-OWASP` 记录为“未形成结论”，
  不冒充扫描通过。

### localhost 真实浏览器

负责人在操作时明确授权把 Git 忽略 `.env` 中的本地测试账号和密码仅提交到
`http://127.0.0.1:5173`。验收没有读取 Cookie/localStorage，也没有输出或提交凭据。

- 开启本地五个实验开关后，既有 READY Job
  `4ecbe8d8-53f1-4692-8c44-0d4ee74d7fcd` 被 MySQL 扫描补齐唯一目标 Run；页面显示
  `REFUSED / NO_SUFFICIENT_EVIDENCE`，刷新后仍从持久化结果恢复。
- 通过页面复用同一 8 秒媒体创建新 Job `c97673c2-0ae2-483c-89b6-c1bba3035216`。Job 在 READY 后先显示
  PENDING，随后显示 ANSWERED，并给出一条 ASR 和一条 OCR 的 `00:00-00:08` 引用；点击 ASR 引用把
  播放器定位到 0 秒。刷新后仍只有一个目标结果区域，两条引用均恢复。
- MySQL 取证显示两条 Job 各只有一个 `goal-analysis:<jobId>` Run；新 Job 为
  `COMPLETED/COMPLETED`，既有 Job 为 `REFUSED/NO_SUFFICIENT_EVIDENCE`。目标 Answer 没有出现在普通
  问答历史。
- 桌面全页和 320x720 真实视口均完成；320px 时 `scrollWidth=clientWidth=305`，无横向溢出，目标区域
  仍可访问。刷新、轮询、结果和引用交互均成功渲染，浏览器 warning/error 日志为空。

T5 自身退出门通过，可以独立提交。T4 的效果缺口继续阻止 M6 晋级；本结论不启用任何默认开关。

## 12. T6 模型驱动闭环合同冻结（2026-11-13）

负责人明确决定冻结 T4 的不可晋级效果事实，并优先按 `T6 -> T7 -> T8 -> T9 -> T10` 推进；上一任务
退出门不通过时停止。`EX-2026-11-13-M6-T6-T10` 只改变 M6 内部实施顺序，不豁免 M5/M6 质量门，
Agent、Worker、目标分析和前端入口继续默认关闭。

ADR-0017 将最短闭环冻结为：

```text
PLAN -> RETRIEVE -> GENERATE_AND_VERIFY -> CRITIQUE
                                            | PASS -> FINISH
                                            ` REPAIR -> RETRIEVE|EXPAND_WINDOW
                                                         -> GENERATE_AND_VERIFY
                                                         -> FINISH|REFUSE
```

- 正常路径最多 3 次 LLM 调用，修复路径最多 4 次；Embedding 另行审计。
- 最多 6 Step、2 次答案生成、1 次 Critic 修复；恢复不能重置任何预算。
- Planner/Critic 输出经过版本化 Schema、白名单、参数、owner、预算和视频边界校验后才可执行。
- Critic 只判断覆盖与相关性，不能覆盖 `AnswerEvidenceGate`、权限、状态或预算裁决。
- T7 必须先证明检索/扩窗证据进入 Answer Prompt；T8/T9 才允许增加 Planner/Critic 模型调用。
- 不建设通用 DAG、多 Agent、动态插件、长期记忆或 chain-of-thought 存储。

文档一致性和 `git diff --check` 通过后，T6 可独立提交并进入 T7；这不是 T4/M6 退出或 Agent 晋级。
