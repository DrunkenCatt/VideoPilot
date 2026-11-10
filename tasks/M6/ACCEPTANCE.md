# M6 验收记录：最小动态工具型 Agent Loop

> 当前状态：T0 已完成，下一步 T1。M5 仍未退出；M6 依据 `EX-2026-11-10-M6` 作为默认关闭的本地实验能力开始。

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
