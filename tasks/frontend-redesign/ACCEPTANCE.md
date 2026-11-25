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

## F2：登录弹框和会话边界

日期：2026-11-16

结论：通过，可以进入 F3。

### 交付证据

- 原全页登录改为应用壳层上的必需登录弹框；未登录时不挂载 `RouterView` 业务组件，壳层内容同时设为 `inert` 和 `aria-hidden`。
- 登录失败、限流、服务不可用、提交中和 Session 过期都保留可恢复状态；失败后清空密码但保留账号。
- 登录不改写当前 URL，因此可恢复原目标路由；退出和 Session 过期会卸载当前业务页并递增会话 epoch，不保留前一用户的组件状态。
- 侧栏账户区显示当前账号和账户菜单，退出仍通过既有 Session API 发送页面内存中的 CSRF Token。
- `.env` 只检查了变量名与是否设置，未输出或复制任何值；实际 `.env` 不声明 Agent 开关，未改变默认关闭边界。

### 自动化检查

| 检查 | 结果 |
| --- | --- |
| LoginDialog/AppShell RED | 组件未实现时 2 个 Suite 按预期失败 |
| Session/App/LoginDialog 定向测试 | 22/22 通过 |
| `client\npm.cmd run check` | 通过；23 个测试文件、141 项测试 |
| 覆盖率 | Statements 87.91%、Branches 82.11%、Functions 89.91%、Lines 93.08% |
| 生产构建 | 通过；65 modules transformed |
| `server\.\mvnw.cmd -Dtest=SessionSecurityContractTest test` | 通过；7 项测试，验证 HttpOnly、SameSite=Strict 和 CSRF 合同 |

### 真实浏览器检查

- 使用 localhost 临时 Session 响应器实际设置 HttpOnly/SameSite Cookie，刷新 `/settings` 后会话恢复，退出请求必须同时带 Cookie 与正确 `X-CSRF-Token`。
- 未登录时账号输入框自动获得焦点；Tab 顺序为账号 -> 密码 -> 登录 -> 账号，Shift+Tab 反向圈定正常。
- Esc 不关闭必需登录弹框；错误登录显示安全文案并清空密码；成功后 URL 仍为 `/settings`。
- 登录、刷新恢复和退出全流程 Console warning/error 为 0。

### 环境边界

- 真实 Docker server 仍受既有 Flyway V20 checksum mismatch 阻断；未执行 `flyway repair`，未修改数据库历史。
- 临时 localhost Session 响应器、Vite 进程和浏览器页签均已停止；没有向仓库、数据库或外部站点写入数据。
- F2 未引入视频切换、最近视频存储或业务页面拆分；这些仍属于 F3 及后续任务。

### F2 退出门

- [x] 未登录时不挂载业务数据组件，弹框自动打开且无法误关闭。
- [x] 错误、限流、提交中、成功、过期和退出状态有自动化覆盖。
- [x] 原目标路由恢复、页面状态清理、Cookie/CSRF 和键盘路径已验证。
- [x] 类型、Lint、测试、覆盖率、构建和真实浏览器检查通过。
- [x] 未进入 F3 视频工作区或 F4-F8 业务页面范围。

## F3：当前视频工作区和切换视频

日期：2026-11-16

结论：通过，进入 Checkpoint A 人工确认；确认前不进入 F4。

### 交付证据

- `jobId` 作为工作台、上下文和问答的唯一当前上下文；侧栏跨页导航保留同一 `jobId`，不写 localStorage 或第二份当前选择。
- 新增“切换视频”弹框，按 Media 分组最近 Job，同一 Media 下通过分析目标、服务端状态和更新时间区分多个 Job。
- 无显式 `jobId` 的工作台/上下文/问答只默认打开更新时间最新的 `READY` Job，不会误选更新但仍在处理的 Job。
- 媒体没有 Job 时只显示“创建解析任务”；运行中、失败和 `READY` 状态沿用真实服务端语义。
- 无权限/已删除任务显示合并的安全 404 文案并不回显旧视频；非法 ID 不发起猜测请求；401 回到必需登录弹框。
- 既有 `JobWorkbench` 已从 URL hash 迁移到路由 `jobId`；内部创建/选择 Job 同步正式路由，快速切换时忽略旧请求的晚到响应。

### 自动化检查

| 检查 | 结果 |
| --- | --- |
| WorkspaceSwitcher/AppShell RED | 组件未实现、导航未保留 `jobId` 时按预期失败 |
| F3 定向测试 | WorkspaceSwitcher/JobWorkbench/AppShell/App 35/35 通过 |
| `client\npm.cmd run check` | 通过；24 个测试文件、152 项测试 |
| 覆盖率 | Statements 87.63%、Branches 81.38%、Functions 89.62%、Lines 92.79% |
| 生产构建 | 通过；67 modules transformed |

### 真实浏览器检查

- 从 `/settings` 打开切换弹框，同一媒体下可见“新版本索引 / 建立索引”和“考点复核 / 已就绪”两个真实状态项。
- 选择后 URL 为 `/workspace/10000000-0000-0000-0000-000000000009`，上下文与问答链接保留同一 ID。
- 点击上下文、浏览器后退、前进和刷新后，URL 与当前媒体均正确恢复。
- 320×800 下弹框标题、分组和任务完整可见，无水平溢出；首次检查发现顶栏包含块导致定位裁切，改用 Teleport 后复验通过。
- 404 路由显示“已删除，或当前账号无权访问”且旧 Job 标题数为 0；Job API 401 时登录弹框重新打开且业务标题数为 0。
- 上述流程 Console warning/error 为 0（浏览器运行时自身的 Statsig 初始化通知不来自项目页面 Console）。

### 环境边界

- 使用只读 localhost 响应器复现 Job/Media/Session 状态，未写仓库、数据库或外部站点；验收后响应器、Vite 和页签均已停止。
- 真实 Docker server 的既有 Flyway V20 checksum mismatch 未被修复或绕过。
- F3 未拆分媒体库、解析任务、上下文、问答或工作台内部；路由仍在过渡期复用现有真实 `JobWorkbench`。

### F3 退出门

- [x] URL 是唯一当前工作区上下文，跨页、刷新和历史导航可恢复。
- [x] Media 分组、多 Job 区分、最新 READY 默认和无 Job 引导符合合同。
- [x] 晚到响应、格式错误、401、404 与陈旧数据清理有自动化和浏览器证据。
- [x] 类型、Lint、测试、覆盖率、构建和桌面/手机浏览器检查通过。
- [x] 未进入 F4-F8 页面拆分，Checkpoint A 保持待负责人确认。

## F8R8：产品图一致性与全链路复验

日期：2026-11-16

结论：技术验收通过；2026-11-17 项目负责人在壳层与重复上传入口修正后明确要求继续按顺序执行，Checkpoint B 已确认，允许进入 F9。

### 页面结构与视觉证据

- 视频工作台、媒体资料库、解析任务、视频上下文、证据问答、评测中心和系统设置已经分别采用产品图对应的信息架构，不再由旧的通用纵向卡片流承载页面主体。
- 七页均使用同一克制的 SaaS 视觉语言，但保留各自的专用结构：工作台 2x2、媒体卡片加表格、全宽任务行、时间轴加详情、问答双栏阅读器、评测报告加硬门、设置四区栅格。
- 真实浏览器在 320、768、1024 和 1440 像素宽度逐页检查，四档共 28 个页面状态均无页面级横向溢出，主内容与唯一页面标题可见。
- 桌面与移动截图共 14 张，保存在 `C:/Users/zzm/.codex/visualizations/2026/11/16/videopilot-f8r8/`；截图只作为本机验收产物，不进入产品源码提交。

### 真实流程复验

- 上传与创建解析任务由 F8R2 的浏览器验收和回归测试覆盖；当前真实资料库仍显示此前浏览器上传的媒体及其服务端状态。
- 使用真实 READY Job `26e84e50-5f43-4c4d-b0d7-3350fb520589` 复验工作台、上下文与问答。视频资源成功加载，时长 17.67102 秒，浏览器 `readyState=4`。
- 工作台、上下文和问答保持同一 `jobId`；问答引用跳转到 `/context/:jobId`，并携带真实 `segmentId`、`fromMs=0` 和 `toMs=17671`，证据定位链路无回归。
- 当前真实环境没有评测数据集，评测页按合同显示空态，没有把概念图示例指标伪造成运行数据。
- 七页逐页加载及引用跳转期间，页面 Console 只有 Vite 连接 debug 信息，warning/error 为 0；真实 API 数据和 MinIO 视频短链均成功渲染，无可见请求失败或错误占位。

### 自动化与审查

| 检查 | 结果 |
| --- | --- |
| `client\npm.cmd run check` | 通过；30 个测试文件、202 项测试，类型检查、零 warning lint 和生产构建成功 |
| 覆盖率 | Statements 87.05%、Branches 80.86%、Functions 89.17%、Lines 91.87%，满足 `CONSTRAINTS.md` |
| 回归敏感性 | 将选中答案临时变异为始终选择首项后，历史选择测试按预期失败；恢复实现后重新通过 |
| 五轴审查 | correctness、readability、architecture、security、performance 均无阻断项 |

### 产品复审修正：壳层顶栏与当前视频

- 顶栏从暖黄色半透明背景改为 64px 白色产品壳层，只保留面包屑和页面动作；页面标题只在主体中出现一次。移动端动作保持单行，320px 无横向溢出。
- 视频选择器只在视频工作台、视频上下文和证据问答三个 Job 作用域页面出现。媒体资料库显示“上传视频”，解析任务显示“新建分析”，评测中心和系统设置显示“打开最近视频”。
- 根应用在当前登录会话内保存最后选择的 `jobId` 作为导航指针；三个 Job 页面仍以 URL 参数为事实来源。离开视频页面进入资料库、评测或设置后，工作台、上下文、问答和“打开最近视频”仍指向同一个 Job。
- 真实浏览器从上下文切换到 Job `79259a8a-1cc8-4de0-82ed-1cb8b38a6e46` 后，问答导航和最终 URL 均保持该 ID；资料库顶栏动作实际打开上传弹框。
- 修正后桌面与移动截图保存在 `C:/Users/zzm/.codex/visualizations/2026/11/16/videopilot-shell-fix/`；Console warning/error 为 0。
- 反向将视频选择器改为全路由显示后，壳层测试有 8 项按预期失败；恢复正确条件后 18/18 通过。

### 边界与退出门

- F8R8 没有新增产品能力，没有修改后端合同、权限、状态机或数据真源。
- `mynote/` 是用户未跟踪资料，未修改、未删除、未加入提交。
- [x] 七页结构、产品图层级和真实数据边界复验通过。
- [x] 四档响应式、14 张截图、Console 与真实流程复验通过。
- [x] 前端完整质量门与覆盖率门通过。
- [x] 项目负责人于 2026-11-17 确认继续执行，Checkpoint B 通过。

## F9：真实存储空间汇总

日期：2026-11-17

结论：通过，允许按顺序进入 F10。

### 后端合同与数据边界

- 新增 `GET /api/v1/media-assets/storage-summary`，身份只来自已认证会话，不接收客户端 `ownerId`；响应为 `usedBytes`、可空的 `capacityBytes` 和 `mediaCount`。
- application service 通过 query port 获取汇总，MySQL adapter 使用单条 owner-scoped 聚合查询；Controller 未直接访问 Mapper、Redis 或对象存储。
- 汇总统计 `UPLOADED`、`PROBING`、`READY`、`FAILED` 的已落盘媒体，不统计尚未形成源对象的 `UPLOAD_PENDING`；分页参数不会影响汇总。
- 容量来自服务端 `MEDIA_STORAGE_CAPACITY`，支持 `30GB` 等 `DataSize` 值；空配置映射为 `null`，前端明确显示“容量未配置”且不渲染百分比。

### 前端与交互

- 登录后侧栏在账号区上方加载真实存储卡片；配置容量时展示用量、容量、媒体数和可访问的 `meter`，未知容量不伪造进度。
- 加载失败显示可重试状态，401 通知应用恢复登录边界；未登录时不挂载存储查询。
- 768-1023px 的折叠侧栏隐藏完整文字卡片，桌面和移动抽屉保留完整信息，避免窄栏挤压。

### 自动化与运行证据

| 检查 | 结果 |
| --- | --- |
| 后端聚焦测试 | 23/23 通过，覆盖 owner 隔离、空库、分页独立、状态集合和容量解析 |
| `server\mvnw.cmd test` | 通过；501 项测试，0 failures、0 errors、1 skipped，ArchUnit 通过 |
| 隔离集成测试 | 临时库完成 23 个 Flyway 迁移，`MediaAssetQueryIT` 3/3 通过；测试库随后删除 |
| 回归敏感性 | 临时将 `usedBytes` 变为固定 0 后 application 测试按预期失败；恢复后 5/5 通过 |
| 前端聚焦测试 | API、存储卡片、AppShell 和 App 会话流 30/30 通过 |
| `client\npm.cmd run check` | 通过；31 个测试文件、207 项测试，零 warning lint、类型检查和生产构建成功 |
| 覆盖率 | Statements 87.15%、Branches 81.06%、Functions 89.02%、Lines 91.94%，满足 `CONSTRAINTS.md` |

### 真实浏览器检查与环境边界

- 使用临时数据库和独立的 `18081/15173` 后端、前端端口完成真实登录；侧栏通过真实汇总请求显示 `0 B / 30 GiB`、`0%` 和 `0 个视频`，媒体空库状态正常。
- 浏览器 DOM 确认存储区语义为“本地存储” region，进度为带名称和上下界的 meter；浏览器 Console warning/error 为 0。
- 1280×720 视口截图确认卡片位于账号区上方，未改变产品图壳层和媒体页结构。
- 共享 `videopilot` 数据库仍存在既有 Flyway V3 checksum mismatch；未执行 repair、未修改共享迁移历史。集成和浏览器验证均改用精确命名的临时库。
- 验收结束后临时 Vite 进程、server 容器和数据库均已停止或删除；`15173` 端口关闭。真实 `.env` 仅用于启动隔离环境，未修改且未输出密钥。
- `mynote/` 仍为用户未跟踪资料，未修改、未删除、未加入提交。

### F9 退出门

- [x] owner-scoped、分页无关且不依赖 Redis 的确定性汇总通过。
- [x] 容量配置、未知容量、失败重试和会话过期状态通过。
- [x] 后端、前端、集成、真实浏览器和五轴审查通过。
- [x] 后端合同与前端卡片保持两个独立薄提交，未进入 F10 范围。

## F12：响应式、可访问性和全链路回归

日期：2026-11-17

结论：技术验收通过；Checkpoint C 仅等待负责人最终视觉与产品流程审阅。

### 响应式与可访问性修正

- 320px 顶栏隐藏非必要面包屑，保留菜单和当前页面动作；七个路由在 320、768、1024 三档共 21 个状态均无页面级横向溢出。
- 新增统一弹框焦点管理，覆盖移动侧栏、视频切换、上传、创建任务、任务详情和可靠性面板；打开后焦点进入弹层，Tab/Shift+Tab 圈定，Escape 关闭并返回触发控件。
- 直接通过 `/library?action=upload` 打开弹框时也会在首次挂载后聚焦关闭按钮；该真实浏览器边界在验收中发现，并补充了失败测试后修复。
- `select` 纳入全局字体与可见焦点规则；证据问答输入框不再覆盖 `focus-visible` 轮廓。
- 调整蓝色、次要文本和绿色状态令牌；白底/浅色底关键组合最低对比度由 3.92-4.47:1 提升到 4.72:1 以上，满足 WCAG AA 普通文本要求。

### 自动化、回归敏感性与安全

| 检查 | 结果 |
| --- | --- |
| F12 聚焦测试 | 5 个文件、41 项测试通过 |
| `client\npm.cmd run check` | 通过；35 个测试文件、225 项测试，类型检查、零 warning lint 和生产构建成功 |
| 前端覆盖率 | Statements 87.37%、Branches 80.44%、Functions 88.52%、Lines 92.16%；新增 `useModalFocus.ts` 行覆盖率 82.35% |
| Escape 变异检查 | 临时破坏 Escape 分支后测试按预期失败；恢复实现后重新通过 |
| 最终 `verify` 的单元测试阶段 | 507 项测试通过，0 failures、0 errors；JaCoCo 和 ArchUnit 通过；本轮未另行重复执行独立 `server\mvnw.cmd test` |
| `server\mvnw.cmd verify -Pintegration -Dbenchmark.commit=4ab852a` | Docker 隔离环境通过；507 项单元测试与 100 项集成测试通过，集成测试按既有条件跳过 2 项；总耗时 8:45 |
| `npm audit --audit-level=high --registry=https://registry.npmjs.org` | High/Critical 0；10 个既有 Moderate，未使用强制升级破坏工具链 |
| `gitleaks detect --redact --no-banner` | 166 个提交扫描完成，0 leaks |
| 五轴审查 | correctness、readability、architecture、security、performance 均无阻断项 |

### 真实浏览器验收

- 七个路由使用真实本地会话和服务端数据逐页检查 320、768、1024 三档；工作台、上下文和问答保持同一 `jobId`，集合页动作矩阵未回归。
- 移动侧栏打开后焦点落在品牌链接，Escape 后返回菜单按钮；视频切换弹框的 Shift+Tab 从首控件回到末控件，Escape 后返回切换按钮。
- 上传弹框通过查询参数直接打开时焦点落在“关闭上传”，Escape 正常关闭；修正前焦点停留在页面主体，修正后复验通过。
- 当前共享 server 镜像仍显示已解释的存储摘要重试状态，原因是共享库历史迁移校验和与当前分支不一致；未执行 Flyway repair。交互中未观察到页面崩溃或未处理脚本错误，已知服务端错误进入既有 loading/error/retryable 状态；本轮浏览器接口未直接导出 Console 日志。
- 320px 媒体资料库截图已在本次浏览器验收中生成；移动端工具栏、筛选、媒体卡片和纵向阅读顺序与产品图结构一致。

### 环境与边界

- 最终集成在全新 `videopilot_f12_verify_pass_20261117` 库中执行 24 个迁移，并以提交 `4ab852a` 记录检索基线候选；测试生成的基线报告随后恢复为仓库原内容，未把运行时评测结果混入本次前端提交。
- 三个明确命名的 F12 临时数据库和临时 `videopilot-server-f12` 镜像均已删除；共享 `videopilot` 数据库、运行中服务和迁移历史未修改。
- `.env` 只在进程内向隔离测试传递所需依赖与模型变量，未修改、未输出或提交任何值。
- M6 Agent、Worker 和目标分析默认关闭及未晋级事实未改变；`mynote/` 仍为用户未跟踪资料，未修改、未删除、未加入提交。

### F12 退出门

- [x] 七个路由在手机、平板和桌面宽度无不可用溢出，移动侧栏可关闭。
- [x] 键盘主路径、可见焦点、语义弹框和 WCAG AA 对比度通过。
- [x] 前端、后端、集成、安全、覆盖率和真实浏览器技术门通过。
- [x] 实际命令、耗时、环境限制和清理结果已记录。
- [ ] 负责人完成最终视觉与产品流程审阅后，Checkpoint C 才能关闭。
