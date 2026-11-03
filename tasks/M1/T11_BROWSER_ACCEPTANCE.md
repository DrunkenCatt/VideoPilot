# T11 真实上传与进度浏览器验收

日期：2026-11-03  
环境：本机 Chrome、Vite `http://localhost:5173`、Docker Compose server/MySQL/RabbitMQ/MinIO  
范围：T2F + T9F + T10F + T4F + T7F 的连续用户路径

## 账号与数据边界

- 使用项目既有的本地临时测试账号；项目负责人明确授权在本地浏览器输入凭据。
- 凭据未写入本文、源码、前端存储或 Git 历史。浏览器只保留服务端会话 Cookie。
- 所有媒体与 Job 请求均来自当前会话；页面和请求没有可由用户填写或切换的 ownerId。

## 成功路径

1. 从无会话页面登录，进入上传与任务工作台。
2. 上传真实 3 秒 MP4 `t11-valid.mp4`（41.2 KiB）。上传响应先显示 `UPLOADED`，资源轮询随后显示 `READY`、可信时长 `00:03`。
3. 选择该 READY 媒体，提交目标“`T11 浏览器纵向闭环验收`”。
4. Job 进入 `READY / INDEXING`，详情展示真实媒体名、时长、状态、媒体 ID 和 7 条持久化事件。
5. 整页刷新后，会话和媒体列表恢复；URL hash 指向服务端返回的 Job self link，Job、媒体事实和完整事件时间线均从服务端恢复。

关键事实：

| 类型 | ID | 最终事实 |
| --- | --- | --- |
| 有效媒体 | `312b1dc8-39cc-48e1-a9c6-688387857f8a` | `READY`，`duration_ms=3000`，version 3 |
| 成功 Job | `a47167df-88bb-427f-bc33-d17e58febf96` | `READY / INDEXING`，version 6，event sequence 7 |

## 失败与恢复路径

- 上传 12 字节损坏文件 `t11-corrupt.mp4`。传输本身完成，但真实服务端探测把媒体 `ae5c5ca4-17df-49f2-a278-4005ed6eca02` 收敛为 `FAILED / MEDIA_INVALID`；安全摘要为 `The uploaded file is not a supported video.`，页面不提供“选择用于分析”。
- M1 假处理器没有自然失败开关。为验证既有 T7/T7F 失败读取面，验收数据库加入显式 fixture Job `11000000-0000-0000-0000-000000000011`：`FAILED / PROBING`、version 4、event sequence 5、错误码 `ANALYSIS_FIXTURE_FAILURE`。页面显示明确终止、安全摘要、真实媒体事实和 5 条持久化事件。
- 该 fixture 仅验证失败事实的 owner-scoped 查询、刷新恢复和安全展示，不验证生产处理器生成故障，也不新增调试端点或前端伪状态。

## 浏览器与依赖证据

- 成功路径实际经过 MySQL 状态真源、MinIO 对象上传、FFprobe 探测、RabbitMQ/Consumer 和 Job 持久化事件链路。
- 375 x 812 视口下 `innerWidth=375`、页面滚动宽度 360，无横向溢出。
- 验收期间 Console 的 `error`、`warn`/`warning` 均为空。
- 页面截图保存在本机忽略目录 `tmp/T11_BROWSER_ACCEPTANCE.png`，不作为仓库运行时依赖。

## 自动化验证

最终交付以 `client\npm.cmd run check` 为统一质量门，覆盖类型检查、Lint、Vitest 覆盖率阈值和生产构建。T11 没有修改后端代码，因此不重复运行 Maven 全量门；后端行为由上述真实 Compose 冒烟链路验证。
