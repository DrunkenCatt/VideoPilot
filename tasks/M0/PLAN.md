# M0 实施计划：工程骨架与契约冻结

## 目标与当前基线

M0 的目标是把当前“能启动的 Spring Boot/Vue/Compose 空骨架”提升为可验证的工程契约：固定 Java 21 工具链，冻结领域和任务可靠性边界，建立 Flyway/配置/Trace/健康检查/CI 基础，并形成可版本化的评测语料规范和供应商 Adapter 清单。M0 不实现上传、真实模型调用、RabbitMQ 消费者或完整假任务链；后者属于 M1 的可靠 Job 薄切片。

当前仓库已具备：Java 21 Maven 编译目标、Spring Boot 3.5.9/Undertow、Vue 3/Vite、五项基础设施 Compose 编排、`com.videopilot` 顶层领域包和 RabbitMQ ADR。当前缺口包括 Flyway、统一错误结构、Trace、五项依赖可区分健康状态、M0 所需 ADR、benchmark 规范、供应商清单和 CI。工作树已有用户未提交修改：`AGENTS.md`、`CONSTRAINTS.md`；本计划不覆盖或回退这些修改。

## 已验证基线

- `server\\mvnw.cmd test`：通过，1 个 Spring Context 测试通过；Maven 日志使用 Java 21。
- `client\\npm.cmd run check`：通过，类型检查、Lint、1 个 Vitest、生产构建均通过。
- `docker compose config --quiet`：通过。
- `java -version` 在当前 PowerShell PATH 中显示 Corretto 8，而 `JAVA_HOME` 指向 Temurin 21；必须在 M0 解决环境自证与 CI 漂移问题。
- `git status --short --branch`：`main` 分支，`AGENTS.md` 和 `CONSTRAINTS.md` 已修改；最近提交为 `deb1974`（项目架构初始化）、`eed3f34`（M0 应用骨架）、`1f94863`（项目基线）。

## 依赖顺序

```text
T1 工具链/基线盘点
  -> T2 领域契约与 ADR 冻结
      -> T3 Flyway 最小 schema 与迁移校验
      -> T4 配置、秘密、统一错误与 Trace
      -> T5 五项基础设施健康检查
          -> T6 本地一键启动验收
  -> T7 benchmark 目录、筛选规范与标注 Schema
  -> T8 固定供应商清单与 Adapter 边界
T3/T4/T5/T7/T8 -> T9 工程质量门接入
T6/T9 -> T10 CI 与 M0 退出验收
```

所有实现任务都先提交对应的失败测试或可执行验收场景，再修改生产代码；任务完成后先跑聚焦检查，再跑该任务列出的结束检查。

## 任务清单

### T1：工具链与现有骨架基线固定

先确认 Java 21、Node 22.12+、Docker Compose 和 Maven Wrapper 的可重复入口；补齐 README/开发检查说明中“实际通过”和“尚未接入”的边界，但不扩展业务功能。

- **依赖**：无。
- **验收标准**：
  - [ ] 仓库提供明确的 Java 21 选择/检查方式，PATH 中 Java 8 时会在构建或 CI 早失败，而不是静默使用错误版本。
  - [ ] 当前十个领域包、前后端现有脚手架和 Compose 服务清单与 Roadmap 一致；不复制参考仓库业务源码。
  - [ ] 验收文档准确记录基线命令和环境限制。
- **验证命令**：
  - `cd server; .\\mvnw.cmd -version`
  - `cd server; .\\mvnw.cmd test`
  - `cd client; npm.cmd run check`
  - `docker compose config --quiet`
- **预计影响文件**：`server/pom.xml`、`README.md`、`server/.mvn/wrapper/maven-wrapper.properties`、可能新增 `.tool-versions` 或 CI 工具链文件。
- **预计范围**：S。

### T2：任务身份、状态机、Checkpoint、Outbox 与错误分类契约

根据 Roadmap 第 5/6 节和 ADR-0001，冻结 `analysisKey/jobId/runId/idempotencyKey/requestId` 的边界、合法状态迁移、Checkpoint schemaVersion、Outbox 语义、可重试/不可重试/UNKNOWN 错误分类，并明确 MySQL 真源与 RabbitMQ 至少一次投递关系。

- **依赖**：T1。
- **验收标准**：
  - [ ] 新增四份 ADR：任务身份、状态机、Outbox/Checkpoint、云端模型调用与预算；每份包含上下文、决策、替代方案、后果和验证场景。
  - [ ] ADR 不与 `docs/adr/0001-use-rabbitmq-for-analysis-jobs.md` 冲突，并明确 M1/M4 才实现的内容不在 M0 交付范围。
  - [ ] 状态迁移、输入指纹、错误分类和模型调用审计字段可直接作为后续表结构/API 契约依据。
- **验证命令**：
  - `rg -n "analysisKey|jobId|runId|Checkpoint|Outbox|UNKNOWN|budget|schemaVersion" docs/adr`
  - `git diff --check -- docs/adr`
  - 人工逐条对照 `docs/PROJECT_ROADMAP.md` 第 5、6、8、13 节和 ADR-0001。
- **预计影响文件**：新增 `docs/adr/0002-analysis-identity.md`、`docs/adr/0003-analysis-job-state-machine.md`、`docs/adr/0004-checkpoint-and-outbox.md`、`docs/adr/0005-model-adapter-and-budget.md`。
- **预计范围**：M。

### T3：Flyway 与最小真源 schema

把 T2 冻结的字段落成只前进的 Flyway 初始迁移，覆盖 `media_asset`、`analysis_job`、`job_stage_attempt`、`job_event_outbox`、`model_call`；仅建 M0 所需结构和约束，不实现业务 Job 流程。

- **依赖**：T2。
- **验收标准**：
  - [ ] 应用启动自动执行 Flyway，重复启动不会重复迁移，迁移历史可查询。
  - [ ] 表包含身份唯一性、状态/version、阶段输入指纹、Outbox 发布状态、ModelCall 审计和必要时间/错误字段；敏感值不入表或日志。
  - [ ] 迁移校验失败会阻止启动/CI 通过，不依赖手工改表。
- **验证命令**：
  - `cd server; .\\mvnw.cmd test`
  - `docker compose up -d mysql`
  - `cd server; .\\mvnw.cmd verify -Pintegration`
  - `docker compose down`（不使用 `--volumes`）
- **预计影响文件**：`server/pom.xml`、`server/src/main/resources/application.yml`、新增 `server/src/main/resources/db/migration/V1__m0_foundation.sql`、新增迁移测试。
- **预计范围**：M。

### T4：配置分层、秘密保护、统一错误结构与日志 Trace

建立默认配置、local/test/CI 覆盖关系，所有密码/API Key 仅来自环境变量或本地未跟踪 `.env`；定义统一 HTTP 错误响应和 `requestId`/`jobId`/`runId` MDC/日志字段，为后续 Controller、MQ 和模型 Adapter 共享。

- **依赖**：T2；可与 T3 并行设计，落地时先完成 T2。
- **验收标准**：
  - [ ] `.env.example` 只含开发占位值，真实密钥、完整模型响应和视频/转录不进入仓库。
  - [ ] 错误结构至少能表达稳定错误码、消息、requestId 和可重试提示；日志 Trace 不泄露凭据或不必要隐私。
  - [ ] HTTP/异步调用的追踪字段命名与 Roadmap 身份契约一致。
- **验证命令**：
  - `git diff --check`
  - `rg -n -i "api.?key|password|secret|token|BEGIN .*PRIVATE" --glob '!package-lock.json' --glob '!server/mvnw*' .`
  - `git ls-files .env`
  - `cd server; .\\mvnw.cmd test`
- **预计影响文件**：`server/src/main/resources/application.yml`、`.env.example`、`.gitignore`、新增 `server/src/main/java/com/videopilot/infrastructure/...` 配置/错误/Trace 类及测试。
- **预计范围**：M。

### T5：五项基础设施健康检查

让 Actuator 区分应用存活与 MySQL、Redis、RabbitMQ、MinIO、Qdrant 五项依赖健康。健康检查只反映连接可用性，不把业务成功伪装成健康。

- **依赖**：T3、T4；Compose 编排本身可复用现有文件。
- **验收标准**：
  - [ ] `/actuator/health` 或分组端点能分别显示应用 liveness/readiness 与五项依赖状态。
  - [ ] 任一依赖不可用时，诊断能指出具体依赖；应用存活端点仍与依赖就绪语义区分。
  - [ ] 健康检查使用受控超时，响应中不暴露凭据、内部 URL 或敏感异常详情。
- **验证命令**：
  - `cd server; .\\mvnw.cmd test -Dtest=*Health*Test`
  - `cd server; .\\mvnw.cmd verify -Pintegration`
- **预计影响文件**：`server/pom.xml`、`server/src/main/resources/application.yml`、新增 MinIO/Qdrant 健康 Adapter、健康单元/集成测试。
- **预计范围**：M。

### T6：本地一键启动与空业务环境验收

在现有 Compose 基础上提供一致的启动、等待、检查和停止入口，并用真实五项基础设施验证 T3/T5；命名卷默认保留，任何清空数据操作都不进入普通启动脚本。

- **依赖**：T3、T4、T5。
- **验收标准**：
  - [ ] 一条文档化命令可启动五项基础设施和后端，等待 readiness 后返回成功；失败时指出具体依赖。
  - [ ] 空业务数据库自动完成 Flyway 迁移，五项依赖均可从健康端点区分。
  - [ ] 标准停止流程不使用 `--volumes`，不删除用户本地数据。
- **验证命令**：
  - `docker compose up -d`
  - `docker compose ps`
  - `Invoke-WebRequest http://localhost:8080/actuator/health -UseBasicParsing`
  - `docker compose config --quiet`
  - `docker compose down`
- **预计影响文件**：`compose.yml`、`README.md`、新增 `scripts/dev-up.ps1`、新增 `scripts/dev-check.ps1`、可能新增 `scripts/dev-down.ps1`。
- **预计范围**：M。

### T7：benchmark 目录、教学视频筛选规范与标注 Schema

建立不提交原视频/完整转录/Embedding 的评测目录和版本化规范，定义教学视频选择、版权/使用边界、dev/test 隔离及问题标注字段，使模板覆盖问题、相关时间窗、证据通道、必需事实和禁止断言。

- **依赖**：T1；不依赖运行时业务代码，可与 T3-T5 并行。
- **验收标准**：
  - [ ] `benchmark/datasets/{retrieval,answer,agent,feedback}`、`benchmark/runners`、`benchmark/reports`、`benchmark/local` 目录职责明确，原始大文件路径被忽略。
  - [ ] 标注 Schema 可表达可回答/不可回答、时间窗、ASR/OCR/视觉证据通道、必需事实、禁止断言、复核状态和数据版本。
  - [ ] 规范明确按视频/主题隔离 dev/test，自动生成问题需人工复核，样本不足只报告工程基线。
- **验证命令**：
  - `rg --files benchmark`
  - `git check-ignore benchmark/local/example.mp4 benchmark/local/example.json`
  - `git diff --check -- benchmark .gitignore`
- **预计影响文件**：新增 `benchmark/README.md`、`benchmark/schemas/*.schema.json`、`benchmark/datasets/*/.gitkeep`、`benchmark/local/.gitkeep`、`.gitignore`。
- **预计范围**：M。

### T8：固定供应商配置清单与 Adapter 边界

记录 MVP 固定的 DeepSeek、TeleSpeechASR、BGE-M3、Tesseract 方案、配置键、版本字段、超时/预算审计要求和后续替换边界；只定义 Port/配置契约，不接入真实供应商 SDK 或发起外部调用。

- **依赖**：T2、T4；可与 T6 并行。
- **验收标准**：
  - [ ] 清单明确 LLM、ASR、Embedding、OCR 的供应商、模型/引擎标识、版本、凭据来源、超时和成本记录字段。
  - [ ] 业务层只依赖 Adapter/Port 契约，不出现供应商专属响应类型；未配置密钥时应用仍可启动空业务环境。
  - [ ] Prompt、模型、Embedding、检索公式和 Agent Policy 的版本写入 Trace 的字段契约已记录。
- **验证命令**：
  - `rg -n "DeepSeek|TeleSpeechASR|BGE-M3|Tesseract|promptVersion|embeddingVersion|provider" docs server/src`
  - `cd server; .\\mvnw.cmd test`
  - `git diff --check`
- **预计影响文件**：新增 `docs/provider-catalog.md`、`server/src/main/java/com/videopilot/infrastructure/model/...` Port/配置/DTO 契约、`application.yml`、契约测试。
- **预计范围**：M。

### T9：工程质量门与统一验证命令

把 `CONSTRAINTS.md` 中从首次提交即适用的门接入构建：ArchUnit 包边界、JaCoCo 变更代码覆盖率基础、`integration` Profile、依赖漏洞扫描和秘密扫描入口；不得用 suppression、跳过测试或降低阈值通过。

- **依赖**：T3、T4、T5、T7、T8。
- **验收标准**：
  - [ ] `server\\mvnw.cmd test` 执行单测、架构测试和 Java 21 Enforcer；`verify -Pintegration` 执行真实边界/迁移校验。
  - [ ] `client\\npm.cmd run check` 保持类型、Lint、单测和构建；覆盖率与依赖扫描有独立、可重复入口。
  - [ ] Controller 直连 Mapper/基础设施规则有 ArchUnit 失败测试；High/Critical 依赖漏洞和凭据会阻断 CI。
- **验证命令**：
  - `cd server; .\\mvnw.cmd test`
  - `cd server; .\\mvnw.cmd verify -Pintegration`
  - `cd client; npm.cmd run check`
  - `cd client; npm.cmd audit --audit-level=high`
  - `gitleaks detect --redact`
- **预计影响文件**：`server/pom.xml`、新增 ArchUnit/覆盖率测试配置、`client/package.json`、可能新增 `.gitleaks.toml`、验证说明文档。
- **预计范围**：M。

### T10：CI、M0 退出门与验收证据

把 T9 的统一命令接入 CI：Java 21 编译、单测、Flyway 迁移校验、前端 `check`、Compose 配置校验、依赖与秘密扫描；最后形成可审计的 M0 验收记录，并明确未执行项。

- **依赖**：T6、T9。
- **验收标准**：
  - [ ] CI 在干净环境执行后端编译/单测、前端 check 和迁移校验；Java 版本不满足 `[21,22)` 时失败。
  - [ ] CI/本地检查不会通过删除断言、跳过测试、suppression 或提交凭据来“变绿”。
  - [ ] M0 退出证据覆盖：空业务环境启动、五项健康区分、迁移校验、密钥零进入仓库、标注模板字段完整。
  - [ ] 通过后才允许进入 M1；不把真实上传、模型调用和假任务可靠链误记为 M0 完成。
- **验证命令**：
  - `cd server; .\\mvnw.cmd test`
  - `cd client; npm.cmd run check`
  - `docker compose config --quiet`
  - `cd server; .\\mvnw.cmd verify -Pintegration`
  - `cd client; npm.cmd audit --audit-level=high`
  - `gitleaks detect --redact`
  - CI workflow 的同等命令（提交后由 CI 执行）
- **预计影响文件**：新增 `.github/workflows/ci.yml`、可能更新 `server/pom.xml`/`client/package.json` 检查脚本、`.gitleaks.toml`（仅在确有必要时）、新增 `tasks/M0/ACCEPTANCE.md`。
- **预计范围**：M。

## 检查点

### Checkpoint A：T1-T2 完成后

- [ ] Java/Node/Docker 工具链入口确定。
- [ ] 四份 M0 ADR 与 ADR-0001 一致，身份、状态、Checkpoint、Outbox、错误和预算术语冻结。
- [ ] 由人工确认后再落 Flyway schema，避免把未决契约写死。

### Checkpoint B：T3-T9 完成后

- [ ] 迁移可重复执行，配置/日志不泄露秘密。
- [ ] 五项依赖健康可区分，Compose 空业务环境可启动。
- [ ] benchmark Schema 和供应商清单可供 M1-M5 直接引用，工程质量门有可重复入口。

### M0 退出检查

- [ ] CI 在 Java 21 下完成编译、单测和迁移校验。
- [ ] 前端 `npm run check` 通过，Compose 配置校验通过。
- [ ] 密钥扫描为零，评测模板满足 Roadmap M0 退出门槛。
- [ ] 记录所有实际执行命令、结果、耗时和未执行项；未达到门槛则停留在 M0。

## 风险与边界

| 风险 | 影响 | 控制措施 |
| --- | --- | --- |
| 当前 PATH 的 Java 是 8、JAVA_HOME 才是 21 | CI/本地结果不一致 | T1 固定 Wrapper/CI 的 Java 21 检查，并让错误版本明确失败 |
| 健康检查直接依赖所有基础设施导致空业务开发无法启动 | 阻塞快速开发 | 区分 liveness/readiness；缺依赖只影响 readiness，不伪造业务成功 |
| M0 过早实现 RabbitMQ/模型真实流程 | 范围膨胀且无法定位故障 | T2/T10 明确把假任务链和真实消费者留给 M1 |
| benchmark 原始资料误入 Git | 版权、隐私和仓库膨胀 | `.gitignore`、目录 README 和 Gitleaks/人工检查共同守门 |
| ADR 与 schema 不一致 | 后续迁移返工 | Checkpoint A 人工审阅后才开始 T3 |

## 未决事项（不阻塞文档落地）

- CI 运行平台（GitHub Actions 或其他自托管平台）需在实现 T10 时按仓库实际托管环境确定。
- Qdrant/MinIO 的健康探针实现方式需在接入对应客户端依赖时选择官方 Java 客户端或受控 HTTP Adapter；M0 只冻结契约，不引入真实业务调用。
- 供应商具体模型版本和价格表在真实凭据/账户确认后填入本地配置，不写入仓库密钥或推测价格。
