# VideoPilot 前端重构验收记录

## F0：页面、路由和视觉合同

日期：2026-11-15

结论：通过，可以进入 F1。

### 范围证据

- 用户已审阅整体实施计划并明确要求按前驱依赖顺序开始执行。
- `DESIGN_CONTRACT.md` 冻结七个一级页面、路由、当前 `jobId` 上下文、页面状态矩阵、视觉令牌、桌面/平板/手机线框和关键交互。
- 合同明确删除全局问答历史页，区分 Media READY 与 AnalysisJob READY。
- 合同明确存储汇总、播放进度和真正课程章节不使用假数据占位。
- 本任务只修改 `tasks/frontend-redesign/**` 文档，未修改运行时代码、API、数据库或配置。

### 配置边界证据

- 只读检查本地 `.env`，仅核对键存在性和开关覆盖情况，未输出或记录凭据值。
- 实际 `.env` 未覆盖 Agent 相关开关。
- `.env.example`、`compose.yml` 和 `server/src/main/resources/application.yml` 的 Agent 相关缺省值为关闭。
- `AnswerWorkspace.vue` 仅在 Vite 开关严格为 `true` 时启用实验能力。

### 执行检查

| 检查 | 结果 |
| --- | --- |
| `git diff --check` | 通过 |
| 任务结构完整性（验收、验证、依赖） | 13/13 |
| 术语检查：`jobId`、`MediaAsset`、`AnalysisJob`、`VideoSegment` | 通过 |
| 运行时代码测试 | 未执行；F0 只有文档与合同变更 |

### F0 退出门

- [x] 路由、侧栏、页面职责和当前 `jobId` 上下文已冻结。
- [x] loading、empty、error、retryable、disabled、unauthenticated 状态已定义。
- [x] 视觉令牌和 320px/768px/1024px+ 响应式线框已冻结。
- [x] 合同没有假章节、假容量或假学习完成率。
- [x] M6 Agent 默认关闭边界未改变。

### 未进入的后续范围

- F1 的 Vue Router、应用壳层和 CSS 令牌尚未实施。
- 登录弹框、视频切换器和各业务页面均未开始迁移。
- 存储汇总和播放进度没有新增后端合同。

## F1：应用壳层、设计令牌和路由

日期：2026-11-15

结论：通过，可以进入 F2。

### 交付证据

- 引入 Vue Router 4，冻结的七个一级导航和解析任务详情路由均可直接解析，未知路径回到视频工作台。
- 新增桌面固定侧栏、768px 紧凑栏和 320px 模态抽屉；当前导航同时通过文字、蓝色和背景表达。
- 引入设计合同中的米白/白/浅灰/蓝色令牌，移除页面背景的装饰性渐变，并保留旧 feature 的语义变量兼容层。
- Session 仍由 `App.vue` 单点持有；RouterView 只把同一 Session 传给既有工作台，没有新增客户端业务真源。
- F4-F8 完成前，五个视频路由继续承载真实 `JobWorkbench`；没有加入“开发中”占位页面或假数据。
- 系统设置路由只显示当前 Session 的安全摘要，不展示 CSRF 值、密码、API Key 或可修改的质量硬门。

### 自动化检查

| 检查 | 结果 |
| --- | --- |
| 路由/壳层 RED | 目标模块不存在时 2 个 Suite 按预期失败 |
| 路由、壳层、Session 定向测试 | 18/18 通过 |
| `client\npm.cmd run check` | 通过；22 个测试文件、133 项测试 |
| 覆盖率 | Statements 87.72%、Branches 81.83%、Functions 89.66%、Lines 92.77% |
| 生产构建 | 通过；63 modules transformed |
| `npm audit --audit-level=high --registry=https://registry.npmjs.org` | High/Critical 0；另有 10 个既有 Moderate，未越界强制升级工具链 |

### 真实浏览器检查

- 使用只返回测试 Session 的临时 localhost 响应器检查 `/settings`；没有向仓库、数据库或外部站点写入数据。
- 1440×900：侧栏 240px、内容区 1200px、无横向溢出，当前项颜色为冻结蓝色令牌。
- 320×800：无横向溢出，菜单按钮可见，侧栏默认移出视口；打开后抽屉与遮罩正常，关闭动作可用。
- 桌面和手机 Console 的 warning/error 均为 0；DOM 可访问树包含 `navigation`、`main`、`banner`、`contentinfo` 和唯一 `h1`。

### 环境观察

- 原 Docker server 启动时遇到既有 Flyway V20 checksum mismatch，因此未执行 `flyway repair`，也未修改数据库历史。
- 为保持 F1 边界，浏览器验收只使用临时 Session 响应器；响应器和本次 Vite 进程已在验收后停止。
- 业务组件的 API/SSE 真实后端联调留在对应页面迁移任务；F1 只证明壳层和路由运行时行为。

### F1 退出门

- [x] 七个一级路由可直接解析、刷新并支持前进后退。
- [x] 侧栏选中态、平板紧凑栏、移动抽屉和页面标题可用。
- [x] 原视频与评测组件仍通过同一 Session 边界装载。
- [x] 类型、Lint、测试、覆盖率、构建和真实浏览器检查通过。
- [x] 未进入 F2 登录弹框、F3 视频切换或 F4-F8 页面拆分范围。
