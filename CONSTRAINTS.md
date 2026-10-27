# VideoPilot Constraints

Last reviewed: 2026-10-27

本文件只定义项目不可静默降低的质量底线、架构不变量、检查方式和例外规则，不记录里程碑进度、任务完成情况或下一步工作。项目进度记录在 `tasks/<milestone>/TODO.md` 和对应验收记录中；产品范围与阶段目标记录在 `docs/PROJECT_ROADMAP.md`。

表格中的里程碑编号只表示质量门从哪个能力阶段开始适用，不表示该里程碑已经开始或完成。检查工具是否已经接入，应以构建配置和 CI 文件为准，不能仅凭本文档宣称检查已自动执行。

## Floor

以下规则从第一次提交起阻断变更：

- 不提交 API Key、密码、Token、私钥、真实 `.env` 或包含凭据的日志。
- 不新增 `@SuppressWarnings`、`@ts-ignore`、`eslint-disable`、`istanbul ignore`、`nosemgrep` 或类似 suppression 来绕过检查，除非先在本文件登记有期限的例外。
- 不提交未实现 Stub、`throw new UnsupportedOperationException("not implemented")`、空 `catch` 或吞掉失败的占位代码。
- 不无理由删除、跳过或弱化测试，不移除仍然有效的断言。
- 不为了让某项改动通过而降低本文件中的阈值或移除检查。
- 不把视频、完整转录、Embedding、运行时数据库或模型原始响应提交到 Git。
- 不修改 `C:\Users\zzm\Desktop\com\DoVideoAI` 参考仓库。

## Required Engineering Gates

| Dimension | Rule | Check | Runs at | Rationale |
| --- | --- | --- | --- | --- |
| Java version | 构建必须使用 JDK 21 | Maven Enforcer `requireJavaVersion [21,22)` | 每次后端构建、CI | 与 Spring Boot 3.5.9 及项目基线一致，避免开发机漂移 |
| Java compile | 零编译错误、零测试失败 | `server\mvnw.cmd test` | 任务结束、CI | 后端最小正确性门槛 |
| Java integration | MySQL/RabbitMQ/Redis/MinIO 边界测试全部通过 | `server\mvnw.cmd verify -Pintegration` | 合并前、CI | 任务可靠性不能只靠 mock 证明 |
| Frontend | 类型检查、Lint、单测和构建全部通过 | `client\npm.cmd run check` | 任务结束、CI | 单一可重复的前端交付命令 |
| Changed-code coverage | 新增或变更业务逻辑的行覆盖率至少 80% | JaCoCo/Vitest coverage 与 Git diff | 任务结束、CI | 足以要求关键分支测试，又允许纯配置和声明代码 |
| Dependency security | High/Critical 漏洞为 0 | OWASP Dependency-Check 与 `npm audit --audit-level=high` | CI | 首版处理上传和外部 API，不能接受高危已知漏洞 |
| Secrets | 仓库中检测到的凭据为 0 | Gitleaks `detect --redact` | 提交前、CI | 防止云端模型密钥进入历史和日志 |
| Architecture | Controller 直连 Mapper/基础设施违规为 0 | ArchUnit | 后端测试、CI | 保持模块化单体边界可演进 |
| State machine | 非法迁移、绕过迁移入口的更新为 0 | 状态机单测 + ArchUnit/代码审查 | 相关改动、CI | 状态可靠性是产品核心 |
| Task verification time | 与改动相关的本地任务结束检查目标不超过 90 秒 | 记录实际命令耗时 | 任务结束 | 让开发者和 Agent 持续运行检查；集成测试另行执行 |

## Runtime Quality Gates

以下指标由对应里程碑的数据和故障测试验证：

| Dimension | Gate | Applicable from |
| --- | --- | --- |
| 重复执行 | 同一幂等请求不产生重复高成本模型调用 | M1/M4 |
| 恢复成功率 | 可恢复故障场景成功率至少 95% | M4 |
| 引用有效性 | 至少 95% 的 `CitedEvidence` 可在声明时间窗原文中确定性定位 | M3/M5 |
| 检索 | 种子集先报告基线；发布集目标 `Recall@3 >= 85%` | M3/M5 |
| Claim 支持 | 发布答案的严格 Claim-Evidence 支持率至少 85% | M5 |
| Agent 晋级 | 硬门通过率提升至少 8 个百分点且 P95 延迟、平均成本增幅均不超过 30%；或满足 Roadmap 定义的等价降本条件 | M6 |

样本量不足时只能报告工程基线，不得把阈值命中包装成正式产品质量结论。

## Architecture Invariants

- MySQL 是业务和恢复状态真源；Redis、RabbitMQ 或进程内存不能成为唯一事实来源。
- 数据库状态与待发布事件使用 Transactional Outbox，不做无法恢复的数据库/Broker 双写。
- RabbitMQ 采用至少一次投递，Consumer 在业务提交成功后手动 ACK，并能安全处理重复与乱序消息。
- 所有高成本阶段都必须有输入指纹、Checkpoint、超时、有限重试和调用审计。
- 所有模型调用必须通过 Adapter，并记录供应商、模型、Prompt 版本、Token、延迟、状态和估算成本。
- 每条事实性 Claim 必须绑定至少一个 `CitedEvidence`；确定性证据门失败时，LLM 无权覆盖。
- Agent Loop 必须有步骤、时间、Token、成本和修复次数上限，并记录明确终止原因。
- 未经复核的用户反馈只能进入待处理队列，不能直接改变生产 Agent。

## Check Placement

| Phase | Budget | Scope |
| --- | ---: | --- |
| Edit | 约 5 秒 | 格式、编译增量检查、Floor diff 检查 |
| Task end | 90 秒目标 | 相关单测、类型、Lint、变更行覆盖率 |
| Review | 数分钟 | 完整单测、架构测试、秘密扫描、依赖扫描 |
| CI | 不设本地交互预算 | Docker 集成测试、故障测试和完整构建 |

## Ratchet Policy

- 没有可信基线的指标不得填写推测值。
- 首次通过规定测量流程得到的有效结果，记录在版本化评测报告中，作为该指标的基线。
- 后续变更不得使已建立的基线下降；确需调整时，必须记录原因、影响、负责人和期限。
- 覆盖率、前端 Bundle、API 延迟、任务成本等测量结果属于评测或验收产物，不写入本文件作为项目进度。

## Exceptions

例外必须在实施前登记，包含规则、路径、原因、负责人和不超过 90 天的到期日。

| ID | Rule | Path | Reason | Owner | Expires |
| --- | --- | --- | --- | --- | --- |
| - | - | - | 当前无例外 | - | - |
