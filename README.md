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

环境要求：JDK 21、Node.js 22.12+、Docker Desktop。真实凭据只写入本地 `.env`，仓库仅提供 `.env.example` 的开发默认值；如默认端口已被占用，可在 `.env` 中覆盖对应的 `*_PORT`。

启动五项基础设施：

```powershell
docker compose up -d
docker compose ps
```

启动后端（默认 `http://localhost:8080`，健康端点为 `/actuator/health`）：

```powershell
cd server
.\mvnw.cmd spring-boot:run
```

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
