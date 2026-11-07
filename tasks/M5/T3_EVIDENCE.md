# M5 T3 验收证据

日期：2026-11-08

## RED / GREEN

- RED：定向 Vitest 因 `api/evaluation.ts` 和 `EvaluationWorkbench.vue` 尚不存在而 2 suite 失败。
- GREEN：API、组件和 App 定向测试通过；随后完整 `npm.cmd run check` 通过 18 files / 115 tests。
- 后端新增 dataset-run 列表沿既有 application/store/controller 边界实现；不新增前端 store、指标计算服务或候选实体。

## 自动化质量门

| 命令 | 实际结果 |
| --- | --- |
| `server\\mvnw.cmd clean test` | 383 tests，0 failure/error，1 个既有条件 skip；ArchUnit 和干净 JaCoCo 门通过，87 s |
| 定向 `JdbcEvaluationStoreIT,DatabaseMigrationIT` | 9/9 通过；真实 MySQL 验证 owner scoped 运行列表和 V1-V19 迁移 |
| `client\\npm.cmd run check` | typecheck、零 warning lint、18 files / 115 tests、coverage、production build 全通过 |
| 前端覆盖率 | Statements 87.31%、Branches 82.19%、Functions 88.80%、Lines 92.45% |

一次与后端全量测试并行的前端运行有两个既有用例在 5 秒超时；没有断言失败。后端结束后不改代码、不改超时和阈值，单独重跑同一 `npm.cmd run check` 全部通过。

## 真实浏览器

在最新 Compose server 与 Vite 页面、既有登录会话下完成：

1. 打开 9 个 MySQL 数据集，查看 version/split/frozen/review；历史 V18 `{}` scoring 数据最初触发 500，补充向前兼容回归测试后恢复。
2. 从最近报告自动恢复十项冻结版本，发起 3 repetition DEV series；页面先显示 `REQUESTED 0/2` partial，刷新后从 MySQL 恢复完成状态。
3. 展开两个脱敏 case Trace，查看 execution fingerprint、指标、延迟、Token、调用数与成本 unknown。
4. 选择两次完成运行，页面只显示数值差；点击 JSON 导出后仍停留工作台。
5. 320/768/1024px 的 `scrollWidth == clientWidth`，工作台和运行按钮均可见；Tab 可到达顶部按钮。
6. Console 只有 Vite connect/HMR debug，warning/error 为 0。

随后执行 Compose Redis `FLUSHDB`，重新打开页面仍恢复 9 个 dataset、6 个 run、2 条 Trace、同一 execution fingerprint 和报告；证明报告不依赖 Redis。清空的是架构声明可重建的本地缓存，MySQL/MinIO 未删除。

## 脱敏与边界

- API client 与导出测试断言没有 question、Prompt 正文、transcript 或 raw response 字段。
- 前端只比较服务端 metric value，不复制 numerator/denominator 公式或 TEST 双人复核判定。
- T3 浏览器运行产生少量真实 dev 模型调用；UNKNOWN 保留，没有改写成成功或拒答。
