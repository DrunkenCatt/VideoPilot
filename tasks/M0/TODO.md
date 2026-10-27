# M0 TODO：工程骨架与契约冻结

> 状态：进行中。本文档记录 M0 的实施顺序和验收清单；已存在的骨架和验证基线见 [PLAN.md](./PLAN.md)。

## 顺序任务

### T1 工具链与骨架基线

- [x] 固定并验证 Java 21、Node 22.12+、Docker Compose、Maven Wrapper 入口。
- [x] 记录当前基线命令结果；保持 `AGENTS.md`、`CONSTRAINTS.md` 用户修改不变。
- **验收标准**：`server\\mvnw.cmd test`、`client\\npm.cmd run check`、`docker compose config --quiet` 可复现；错误 JDK 明确失败。
- **验证命令**：`cd server; .\\mvnw.cmd -version`；`cd server; .\\mvnw.cmd test`；`cd client; npm.cmd run check`；`docker compose config --quiet`。
- **预计影响文件**：`server/pom.xml`、`README.md`、工具链/CI 文件。
- **依赖**：无。
- **完成记录（2026-10-27）**：新增 `scripts/check-toolchain.ps1` 和 README 入口；确认 Java 8 被 Enforcer 拒绝，Task 1 四条验证命令全部通过。

### T2 契约与 ADR 冻结

- [x] 新增任务身份 ADR。
- [x] 新增 Job 状态机 ADR。
- [x] 新增 Checkpoint/Transactional Outbox ADR。
- [x] 新增模型 Adapter、调用审计与预算 ADR。
- **验收标准**：四份 ADR 具备决策、替代方案、后果和验证场景；与 ADR-0001 及 Roadmap 一致。
- **验证命令**：`rg -n "analysisKey|jobId|runId|Checkpoint|Outbox|UNKNOWN|budget|schemaVersion" docs/adr`；`git diff --check -- docs/adr`。
- **预计影响文件**：`docs/adr/0002-*.md` 至 `docs/adr/0005-*.md`。
- **依赖**：T1。
- **完成记录（2026-10-27）**：新增 ADR-0002 至 ADR-0005；必需章节和契约关键词检查、`git diff --check -- docs/adr` 均通过。

### Checkpoint A

- [ ] 人工确认 T1/T2 后再开始 schema 和运行时配置实现。
- [ ] 确认 M0 不实现真实上传、模型调用、MQ Consumer 或 M1 假任务链。

### T3 Flyway 最小 schema

- [ ] 接入 Flyway。
- [ ] 创建 `media_asset`、`analysis_job`、`job_stage_attempt`、`job_event_outbox`、`model_call` 初始迁移。
- [ ] 添加迁移重复执行/校验测试。
- **验收标准**：启动自动迁移且只前进；身份唯一约束、状态/version、输入指纹、Outbox 和 ModelCall 字段可支撑 T2 契约。
- **验证命令**：`cd server; .\\mvnw.cmd test`；`docker compose up -d mysql`；迁移集成命令（接入后）；`docker compose down`。
- **预计影响文件**：`server/pom.xml`、`server/src/main/resources/application.yml`、`server/src/main/resources/db/migration/V1__m0_foundation.sql`、迁移测试。
- **依赖**：T2。

### T4 配置、秘密、错误与 Trace

- [ ] 建立 profile/config 分层和环境变量约定。
- [ ] 定义统一错误响应、错误码和可重试提示。
- [ ] 定义 `requestId/jobId/runId` Trace/MDC 字段。
- [ ] 完成凭据、模型原始响应、视频和完整转录的日志/提交防泄露检查。
- **验收标准**：无真实密钥入仓；错误结构和 Trace 字段可被 HTTP/MQ/Adapter 复用。
- **验证命令**：`git diff --check`；`rg -n -i "api.?key|password|secret|token|BEGIN .*PRIVATE" --glob '!package-lock.json' .`；`cd server; .\\mvnw.cmd test`。
- **预计影响文件**：`application.yml`、`.env.example`、`.gitignore`、`server/.../infrastructure/...` 配置/错误/Trace 类及测试。
- **依赖**：T2。

### T5 五项依赖健康

- [ ] 配置 MySQL、Redis、RabbitMQ、MinIO、Qdrant 健康指示器/就绪检查。
- [ ] 区分 liveness、readiness 和五项依赖诊断。
- [ ] 为探针配置受控超时和敏感详情保护。
- **验收标准**：任一依赖故障能被单独识别；liveness 不与依赖 readiness 混淆。
- **验证命令**：`cd server; .\\mvnw.cmd test -Dtest=*Health*Test`；`cd server; .\\mvnw.cmd verify -Pintegration`。
- **预计影响文件**：`server/pom.xml`、`application.yml`、健康 Adapter 及测试。
- **依赖**：T3、T4。

### T6 本地一键启动验收

- [ ] 提供启动、等待/检查和保留数据的停止入口。
- [ ] 验证空业务 MySQL 自动迁移及五项依赖 readiness。
- [ ] 更新 README 的故障诊断和数据保留说明。
- **验收标准**：一条文档化命令可启动并检查空业务环境；失败指出具体依赖；停止不删除命名卷。
- **验证命令**：`docker compose up -d`；`docker compose ps`；`Invoke-WebRequest http://localhost:8080/actuator/health -UseBasicParsing`；`docker compose config --quiet`；`docker compose down`。
- **预计影响文件**：`compose.yml`、`README.md`、`scripts/dev-up.ps1`、`scripts/dev-check.ps1`、可能的 `scripts/dev-down.ps1`。
- **依赖**：T3、T4、T5。

### T7 benchmark 与标注规范

- [ ] 建立 `benchmark/` 子目录和职责 README。
- [ ] 建立检索/答案/Agent/反馈 Schema 与示例。
- [ ] 写明教学视频筛选、版权/使用边界、dev/test 隔离、复核状态和数据版本规则。
- **验收标准**：模板包含问题、时间窗、证据通道、必需事实、禁止断言、可回答性和复核字段；原始资料路径不会入 Git。
- **验证命令**：`rg --files benchmark`；`git check-ignore benchmark/local/example.mp4 benchmark/local/example.json`；`git diff --check -- benchmark .gitignore`。
- **预计影响文件**：`benchmark/**`、`.gitignore`。
- **依赖**：T1。

### T8 固定供应商清单与 Adapter 边界

- [ ] 登记 DeepSeek、TeleSpeechASR、BGE-M3、Tesseract 及配置/版本/超时/成本字段。
- [ ] 定义 Port/Adapter 契约，禁止业务层依赖供应商响应类型。
- [ ] 记录 Prompt、模型、Embedding、检索公式、Agent Policy 版本 Trace 字段。
- **验收标准**：无真实外部调用也能启动；MVP 固定方案清晰且未来可替换。
- **验证命令**：`rg -n "DeepSeek|TeleSpeechASR|BGE-M3|Tesseract|promptVersion|embeddingVersion|provider" docs server/src`；`cd server; .\\mvnw.cmd test`；`git diff --check`。
- **预计影响文件**：`docs/provider-catalog.md`、`server/.../infrastructure/model/**`、`application.yml`、契约测试。
- **依赖**：T2、T4。

### T9 工程质量门

- [ ] 接入 ArchUnit 包边界检查。
- [ ] 接入 JaCoCo/前端覆盖率和 `integration` Profile。
- [ ] 接入 OWASP/npm audit 与 Gitleaks 阻断入口。
- [ ] 保持统一后端/前端验证命令，不使用 suppression、跳过测试或降低阈值。
- **验收标准**：Java 21、单测、架构、迁移/真实边界、覆盖率、High/Critical 漏洞和秘密规则均有可重复检查入口。
- **验证命令**：`cd server; .\\mvnw.cmd test`；`cd server; .\\mvnw.cmd verify -Pintegration`；`cd client; npm.cmd run check`；`cd client; npm.cmd audit --audit-level=high`；`gitleaks detect --redact`。
- **预计影响文件**：`server/pom.xml`、架构/覆盖率测试配置、`client/package.json`、可能的 `.gitleaks.toml`、验证说明。
- **依赖**：T3、T4、T5、T7、T8。

### Checkpoint B

- [ ] T3-T9 的契约、迁移、配置、健康、评测规范、供应商清单和质量门互相一致。
- [ ] 确认未引入 M1 的上传、Job Consumer、SSE 进度或真实模型请求。

### T10 CI 与 M0 退出验收

- [ ] 新增 CI workflow：Java 21 编译/单测、Flyway 迁移校验、前端 check、Compose 配置校验、秘密扫描。
- [ ] 新增 M0 验收记录，记录实际命令、结果、耗时和未执行项。
- [ ] 运行完整 M0 退出检查并由人工确认是否进入 M1。
- **验收标准**：CI 在干净环境通过；错误 Java 版本失败；五项健康可区分；无密钥入仓；标注模板满足 Roadmap M0 退出门槛。
- **验证命令**：`cd server; .\\mvnw.cmd test`；`cd server; .\\mvnw.cmd verify -Pintegration`；`cd client; npm.cmd run check`；`cd client; npm.cmd audit --audit-level=high`；`docker compose config --quiet`；`gitleaks detect --redact`。
- **预计影响文件**：`.github/workflows/ci.yml`、必要的构建配置、`tasks/M0/ACCEPTANCE.md`。
- **依赖**：T6、T9。

## M0 退出门

- [ ] 空业务环境可一键启动。
- [ ] 应用存活与 MySQL/Redis/RabbitMQ/MinIO/Qdrant 健康可区分。
- [ ] CI 完成 Java 21 编译、单测和迁移校验。
- [ ] 前端 `npm run check` 和 Compose 配置校验通过。
- [ ] 仓库和日志中无密钥、视频、完整转录、Embedding 或模型原始响应。
- [ ] 标注模板覆盖问题、相关时间窗、证据通道、必需事实和禁止断言。
- [ ] 未达到以上任一项时，保持 M0 未完成，不进入 M1。
