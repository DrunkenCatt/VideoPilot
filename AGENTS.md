# AGENTS.md

本文件是所有开发者和 AI coding agent 的项目入口。开始修改前，必须先阅读本文件、`CONSTRAINTS.md`、`docs/PROJECT_ROADMAP.md` 以及相关 ADR。

## 项目目标

VideoPilot 是面向教学长视频的可追溯 Video Agent。首版通过 Java 模块化单体和固定云端模型供应商完成：本地视频上传、可靠异步解析、ASR/OCR 时间对齐、混合检索、带时间戳证据的问答和明确拒答。

MVP 完成后再建设动态工具型 Agent Loop，以及“用户反馈 -> 复核 -> challenge set -> 候选版本 -> 离线评测 -> 晋级/回滚”的持续改进闭环。

## 已确认边界

- 从零新建项目；`C:\Users\zzm\Desktop\com\DoVideoAI` 只作为只读参考，不复制其业务源码。
- 项目名称为 `VideoPilot`，Java 根包为 `com.videopilot`。
- 首版只处理用户本地上传的教学视频，部署在本地或单服务器，不开放公网。
- 首版固定 DeepSeek、TeleSpeechASR、BGE-M3 和 Tesseract；通过 Adapter 隔离供应商类型。
- 消息队列使用 RabbitMQ 和 Spring AMQP，不使用 RocketMQ。
- 暂缓在线视频链接、多供应商路由、多 Agent、自动剪辑、完整学习管理系统和公网运营功能。

## 核心技术栈

- Java 21、Spring Boot 3.5.16、Undertow、MyBatis-Plus、Flyway
- Vue 3、Vite、SSE、Marked
- MySQL 8、Redis 7.x、Redisson、RabbitMQ、MinIO、Qdrant
- FFmpeg、Tesseract、LangChain4j、DeepSeek、TeleSpeechASR、BGE-M3
- Docker Compose

## 架构规则

1. Spring Boot 是一个部署单元，但代码按 `identity`、`media`、`analysis`、`context`、`retrieval`、`answer`、`agent`、`feedback`、`evaluation` 和 `infrastructure` 分域。
2. Controller 只能调用 application service，不能直接访问 Mapper、Redis、RabbitMQ、MinIO、Qdrant 或模型 SDK。
3. MySQL 是 Job、Checkpoint、Agent Trace 和业务状态的唯一真源；Redis 数据丢失后必须能够重建。
4. Job 状态只能通过统一的状态迁移入口更新，并同时写入事件/Outbox。
5. RabbitMQ 提供至少一次投递；消费者必须通过数据库状态、版本 CAS 和阶段输入指纹实现幂等。
6. 视频、完整上下文和模型输出不能进入 RabbitMQ 消息体，消息只携带标识、事件类型、Schema 版本和追踪字段。
7. 模型输出是候选数据。权限、状态迁移、预算、引用存在性和证据硬门由 Java 确定性代码控制。
8. Prompt、模型、Embedding、检索公式和 Agent Policy 都必须有版本号并写入执行 Trace。
9. 未经复核的用户反馈不能直接修改 Prompt、权重或 Agent 行为。

## 工作方式

- 按 `docs/PROJECT_ROADMAP.md` 的 M0-M8 顺序推进；未达到当前里程碑退出门槛，不进入下一阶段。
- 每次只实现一个可验证的薄切片，先写验收场景或失败测试，再实现代码。
- 任务身份、状态机、Outbox/Checkpoint、消息语义、Agent 预算或证据门发生跨模块、代价高或难以逆转的决策变化时新增 ADR；局部字段、实现步骤和测试场景记录在代码、迁移或任务文档中。
- 保留外部依赖降级路径；不得为了让主路径通过而删除 Qdrant、Embedding、OCR 或模型故障处理。
- 先运行与改动直接相关的检查，再运行任务结束检查；不要无理由重复执行相同命令。
- 不修改参考仓库 `C:\Users\zzm\Desktop\com\DoVideoAI`。

## 必须遵守的质量门

读取并遵守 `CONSTRAINTS.md`。不得通过降低阈值、删除断言、跳过测试或增加 suppression 让变更通过。

当前工程初始化后应提供以下统一命令：

```powershell
# 后端快速验证
cd server
.\mvnw.cmd test

# 后端完整验证（需要 Docker）
.\mvnw.cmd verify -Pintegration

# 前端验证
cd client
npm run check
```

这些命令存在不等于验证已经通过。每次交付必须报告本次实际执行的命令、结果和未执行项。

## 领域命名

- `EvidenceHit`：检索返回的候选证据。
- `CitedEvidence`：最终答案中绑定 Claim 的可核验引用。
- `VideoSegment`：时间对齐的 ASR/OCR/关键帧单元。
- `VideoChunk`：用于索引和召回的较长时间块。
- `AnalysisJob`：一次持久化、可恢复的后台任务。
- `AgentRun`：Job 内一次可回放的 Agent 执行。
- `Checkpoint`：MySQL 中用于恢复的阶段状态和产物引用。
- `Recovery`：恢复同一次任务并跳过输入指纹一致的已完成阶段。
- `Replay`：失败后发起新的执行尝试，保留视频固有产物，清除目标相关中间结果。

同一概念不要在代码中发明第二套名称。
