# T9F 真实浏览器验收记录

日期：2026-11-03。

## 验收边界

T9F 验收登录后的文件选择、客户端分块 SHA-256、MinIO multipart 直传、真实字节进度、有限重试、中止与过期页面语义。上传完成的服务端状态只能是 `UPLOADED`；内容可信性、FFprobe 和 `READY` 状态属于 T10/T10F，本任务不提前宣称视频有效，也不把上传结果自动接入 Job 提交。

环境使用隔离端口的 MySQL、Redis、RabbitMQ、MinIO 和 Qdrant；后端监听 18080，Vite 监听 5173 并代理到该后端，数据写入独立 `videopilot_t9f` schema。MinIO 浏览器端点为 19000。验收账号和密码不写入仓库。

## 纵向场景

| 场景 | 浏览器证据 | 服务端证据 | 结果 |
| --- | --- | --- | --- |
| 小文件上传 | 页面从文件提示进入上传，最终显示 100%、媒体 ID 和 `UPLOADED`，并明确提示等待 T10 | MySQL 中 126 B 的 `.mp4` 提示夹具对应 MediaAsset/UploadSession 分别为 `UPLOADED/COMPLETED` | 通过 |
| multipart 上传 | 页面完成 85,994,973 B 文件上传并显示 100%，默认 8 MiB part size 使其经过多个分片 | MySQL 中对象长度一致且 MediaAsset/UploadSession 为 `UPLOADED/COMPLETED`；完成前由后端核对 MinIO 分片清单与 ETag | 通过 |
| 跨源对象存储 | 浏览器能够读取每个 PUT 响应的 ETag 并完成 completion，否则客户端会拒绝完成 | 以页面 origin 执行预检得到 204 和匹配的 `Access-Control-Allow-Origin`；PUT 得到 200，`Access-Control-Expose-Headers` 包含 ETag | 通过 |
| 中止 | 大文件哈希阶段点击“中止上传”后页面收敛到“已中止”，不创建完成媒体、不显示可提交状态 | 单元测试覆盖活动 XHR `AbortSignal -> xhr.abort()`；工作流测试覆盖会话已创建后的幂等 DELETE 与原错误保留 | 通过 |
| 重试/过期/失败 | 组件测试区分“正在重试”“会话已过期”和有限重试耗尽，不把失败显示为完成 | API/工作流测试证明每次重试重新申请短期 part URL，最多三次；`UPLOAD_EXPIRED` 不重试 | 通过 |
| 客户端限制 | 组件测试在建立 upload session 前拒绝空文件、超过 5 GiB 和超长文件名 | 请求未到达服务端；服务端冻结合同仍独立执行同一上限 | 通过 |
| 刷新 | 成功页刷新后回到“等待选择”，旧媒体 ID、进度和短期签名状态均不再显示 | 页面没有把 upload URL、MinIO 凭据或上传会话写入 Local/Session Storage | 通过 |

测试用 `.mp4` 提示夹具只用于 T9F 传输通路，不作为有效视频内容证据；其真实性将在 T10 由 FFprobe 裁决。

## 接口与安全证据

- `POST /api/v1/media-uploads` 只发送文件名、长度、客户端 SHA-256 和 MIME 提示，不发送 ownerId；Cookie owner 与 CSRF 由现有会话边界提供。
- part URL 和 completion URL 只使用服务端响应 links；对象 key 始终不可由页面提交或替换。
- 预签名 URL 只存在于 `putPart` 调用局部变量中，XHR 不携带 Cookie，页面和持久化存储都不保存 MinIO access key、secret key 或签名 URL。
- 每个失败的 PUT 会丢弃旧签名并重新请求 part target；完成请求只携带递增 part number 与响应 ETag。
- SHA-256 以 4 MiB 块增量计算，不把最大 5 GiB 文件一次性读入浏览器内存；中止信号在块边界和网络请求处生效。
- 完成响应为 `UPLOADED`，页面不推断 `READY`，也不把该 mediaId 自动写入临时 Job 提交入口。

## 交互与响应式

| 检查项 | 证据 | 结果 |
| --- | --- | --- |
| 桌面 | 默认桌面视口下上传提示、操作、进度和服务端结果三栏完整，无横向溢出 | 通过 |
| 手机 | 375 x 812 下单栏重排，文件名、媒体 ID、进度和操作均可读，没有横向滚动 | 通过 |
| 键盘 | 从会话操作继续 Tab 时依次进入文件选择、媒体 ID、目标与提交按钮；文件选择后开始/中止按钮均为原生 button | 通过 |
| 可访问名称 | 上传区域有 h2 与 `aria-labelledby`，文件 input 有 label，动态状态使用 `role=status`，错误使用 `role=alert` | 通过 |
| Console | 成功、多分片、中止、刷新和手机视口检查后 error/warning 为 0 | 通过 |

## 自动化验证

| 命令 | 结果 |
| --- | --- |
| `client\npm.cmd run check` | 类型检查、零 warning Lint、47 项 Vitest；Statements 87.97%、Branches 82.33%、Functions 90.62%、Lines 92.91%；生产构建通过 |
| Media API/工作流/组件聚焦测试 | 18 项 T9F 测试通过，覆盖 API headers、真实 XHR progress、ETag、网络失败、有限重试、过期、超限、成功及中止 |
| 真实浏览器 + 隔离后端/MinIO/MySQL | 小文件、约 82 MiB multipart、刷新、中止、桌面、375 x 812、键盘与 Console 检查通过 |

后端 T9 的 MinIO/MySQL 集成测试和 `server\mvnw.cmd verify -Pintegration` 已在 T9 完成记录中报告；T9F 没有修改后端代码，因此本任务不重复运行完整后端集成套件。
