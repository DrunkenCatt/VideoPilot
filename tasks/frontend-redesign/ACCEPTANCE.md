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
