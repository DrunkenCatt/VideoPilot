# VideoPilot

面向教学长视频理解与 Agent 持续改进的 Video Agent 项目。

当前阶段以本机参考源码 `C:\Users\zzm\Desktop\com\DoVideoAI` 为设计依据，从零新建项目并保留其核心技术栈。先用 Java 模块化单体和固定云端模型供应商跑通教学视频解析闭环，再建设真正由工具观察驱动的 Agent Loop、用户反馈改进闭环和可复现评测体系。

项目总体规划、架构边界、验收指标和逐级里程碑见 [docs/PROJECT_ROADMAP.md](docs/PROJECT_ROADMAP.md)。

## 当前阶段

- 状态：M0 工程骨架初始化中；Spring Boot、Vue 3 和本地基础设施编排已建立
- 第一目标：可靠的视频解析与有证据问答 MVP
- 第二目标：可持久化、可预算、可评测的 Agent Loop
- 第三目标：把经过复核的用户反馈转化为 Agent 候选版本，并通过评测完成晋级或回滚

## 实施原则

1. 每个里程碑都必须形成可运行、可演示、可评测的增量。
2. MySQL 保存业务事实和恢复状态；Redis 只做缓存、锁、限流和短期去重。
3. 模型输出是候选结果，状态迁移、权限、预算、引用和证据门控由 Java 确定性代码控制。
4. 没有冻结数据集和基线，不宣称 Agent、Prompt 或检索公式带来提升。
5. 先达到当前里程碑的退出门槛，再进入下一阶段。

## 本地开发

环境要求：JDK 21、Node.js 22.12+、Docker Desktop。真实凭据只能通过进程环境变量或部署平台的秘密管理注入，不能写入 Git、日志或 MQ 消息。仓库中的 `.env.example` 只有本地开发占位值；Docker Compose 会读取复制出的 `.env`，Maven 启动的 Java 进程不会自动读取它。

配置按运行环境分层：`application.yml` 只放所有环境共享的安全配置；`application-local.yml` 为本机基础设施提供开发默认值；快速测试使用隔离数据库依赖的 `test` profile；迁移集成测试使用 `integration` profile。非本地环境必须显式选择 profile 并通过环境变量提供凭据。

日志只允许记录标识符、阶段、稳定错误码和耗时。凭据、视频、完整转录、Embedding、模型 Prompt/原始响应都不能写入日志；HTTP 错误响应也只返回稳定公共文案。统一 Trace 字段为 `requestId`、`jobId`、`runId`，HTTP 使用 `X-Request-Id`、`X-Job-Id`、`X-Run-Id` 传递。

开始开发前运行工具链检查，脚本会验证 Java 21、Node.js 22.12+、Docker Compose 和 Maven Wrapper：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\check-toolchain.ps1
```

启动五项基础设施：

```powershell
docker compose up -d
docker compose ps
```

启动后端（默认 `http://localhost:8080`，健康端点为 `/actuator/health`）：

```powershell
cd server
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
```

后端提供三类健康检查：

| 地址 | 用途 |
| --- | --- |
| `/actuator/health/liveness` | 只判断应用进程是否存活；外部依赖故障不会触发重启循环 |
| `/actuator/health/readiness` | 判断应用是否可以承接业务流量；包含五项依赖 |
| `/actuator/health/dependencies` | 分别诊断 MySQL、Redis、RabbitMQ、MinIO、Qdrant |

健康响应只公开组件名称和 `UP/DOWN`，不公开连接地址、账号、异常消息或响应正文。数据库、Redis、RabbitMQ 和 HTTP 探针均设置了有限连接/响应超时，可通过 `.env.example` 中对应变量覆盖。

启动前端（默认 `http://localhost:5173`）：

```powershell
cd client
npm install
npm run dev
```

任务结束检查：

```powershell
cd server
.\mvnw.cmd test

cd ..\client
npm run check

cd ..
docker compose config --quiet
```

停止本地基础设施使用 `docker compose down`。命名卷会保留数据；只有明确需要清空本地数据时才使用 `docker compose down --volumes`。
