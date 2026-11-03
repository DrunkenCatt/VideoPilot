# T10F 真实浏览器验收记录

日期：2026-11-03。

## 验收边界

T10F 验收登录后的 owner-scoped 媒体资源列表、可信探测状态、有限轮询、READY 选择和 T4F Job 提交接线。页面只消费 T10/T10Q 已持久化事实，不根据客户端 MIME、扩展名或上传完成自行判断媒体有效。

环境使用本机 Compose 的 MySQL、Redis、RabbitMQ、MinIO 和 Qdrant；后端容器监听 18080，Vite 监听 5173 并代理到该后端，数据写入隔离 `videopilot_t10_final` schema。验收使用两个临时账号，凭据不写入仓库。

## 纵向场景

| 场景 | 浏览器证据 | 服务端证据 | 结果 |
| --- | --- | --- | --- |
| 有效视频上传与探测 | 约 95 KiB、3 秒的真实 MP4 上传后立即以“等待校验”进入列表；随后自动变为“可分析”并显示可信 `00:03` | MediaAsset 经 `UPLOADED -> PROBING -> READY`，时长来自真实 FFprobe，不来自文件名或 MIME | 通过 |
| READY 提交接线 | READY 卡片是唯一出现“选择用于分析”的资源；选择后显示文件名/mediaId 并启用提交 | 创建的 Job 使用所选 mediaId，经 Rabbit 消费与统一状态机收敛到 READY、version 6、7 条持久化事件 | 通过 |
| 损坏视频 | 30 B 损坏 `.mp4` 从等待校验收敛为“校验失败”，不出现选择按钮；页面只显示安全摘要 | MediaAsset 为 FAILED，稳定 code 为 `MEDIA_INVALID`；临时路径、命令和原始 FFprobe 输出未进入响应 | 通过 |
| 刷新恢复 | 刷新后上传控件回到等待选择，但 READY/FAILED 资源、可信时长、Job 和完整事件从服务端恢复 | 媒体来自 T10Q MySQL 查询，Job/事件来自既有 owner-scoped 持久化接口 | 通过 |
| 服务不可用与恢复 | 停止验收后端后局部刷新显示“无法读取媒体资源”，同时保留最近可信列表；恢复后点击刷新错误消失 | 失败请求不修改媒体真源；后端恢复后同一查询返回原事实 | 通过 |
| 跨账号隔离 | 第二临时账号登录后显示媒体空列表和 Job 空列表，看不到第一账号的两个媒体或 Job | T10Q 查询按认证 owner 过滤；浏览器请求不携带 ownerId | 通过 |

## 轮询与安全证据

- 列表每页固定读取 8 项；轮询只覆盖当前可见的 `UPLOADED/PROBING`，使用一个 2 秒定时器，资源进入 READY/FAILED 后停止，组件卸载时清理定时器。
- 上传完成事件只把服务端返回的 `UPLOADED` 合并到第一页；后续状态必须由 detail API 返回，客户端没有 `UPLOADED -> READY` 的本地推断分支。
- FAILED 和非终态资源没有分析选择按钮；提交按钮在未选择 READY 时禁用，手填测试 mediaId 入口已移除。
- 列表/详情请求只发送 Cookie 会话并使用冻结路径，不发送 ownerId；响应页面不显示 objectKey、临时路径、探测命令或原始供应商输出。
- 服务不可用时保留已加载事实并显示可操作错误，恢复后由用户局部刷新；认证过期仍提升到现有全局重新登录流程。

## 交互与响应式

| 检查项 | 证据 | 结果 |
| --- | --- | --- |
| 桌面主路径 | 上传、资源状态、选择、提交和 Job 时间线在同一工作台连续完成 | 通过 |
| 手机 | 375 x 812 下单栏重排；`innerWidth=375`、document/body scrollWidth=360，无横向溢出 | 通过 |
| 键盘/语义 | 文件 input、刷新、READY 选择、分析目标与提交均为原生控件；区域、分页和动态错误具备可访问名称/role | 通过 |
| Console | 有效、损坏、刷新、服务中断恢复、第二账号和手机视口完成后 error/warning 为 0 | 通过 |

## 自动化验证

| 命令 | 结果 |
| --- | --- |
| `client\npm.cmd run check` | 类型检查、零 warning Lint、52 项 Vitest；Statements 86.98%、Branches 80.91%、Functions 87.70%、Lines 92.53%；生产构建通过 |
| Media API/资源组件/工作台测试 | 覆盖 owner-scoped list/detail、加载/空态、分页、上传即时合并、轮询终止、READY 门、FAILED 摘要、服务不可用和提交接线 |
| 真实浏览器 + 隔离后端 | 有效/损坏视频、READY Job、刷新、服务中断恢复、双账号、375 x 812 与 Console 检查通过 |

后端 T10/T10Q 的 192 项回归、真实 MySQL/MinIO/FFprobe 与双用户分页集成测试已在对应完成记录中报告；T10F 只修改前端和验收文档，因此不重复运行完整后端集成套件。
