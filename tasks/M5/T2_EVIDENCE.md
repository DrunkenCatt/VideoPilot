# M5 T2 验收证据

日期：2026-11-08

## RED / GREEN / mutation

| 阶段 | 命令 | 结果 |
| --- | --- | --- |
| RED | `server\\mvnw.cmd -q -Dtest=EvaluationReportCalculatorTest test` | 编译失败：`Answerability`、`MetricValue`、`EvaluationReportCalculator` 等尚不存在 |
| GREEN | 同上 | 3/3 通过；覆盖公式、空分母、浮点 MRR、UNKNOWN 和样本标准差 |
| mutation | 临时将 `1/rank` 改为 `1/(rank+1)` 后运行同一测试 | 2/3 失败；恢复实现后 3/3 通过，mutation 未存活 |

## 自动化验证

| 命令 | 实际结果 | 耗时/备注 |
| --- | --- | --- |
| `server\\mvnw.cmd clean test` | 382 项通过、0 failure/error、1 项既有条件 skip；ArchUnit 与干净 JaCoCo 80% 门通过 | 75 s |
| 定向 `JdbcEvaluationStoreIT,DatabaseMigrationIT` | 9/9 通过；已有 V17 数据库前进升级到 V18/V19，二次 migrate 为 0 | 迁移/Store 门 |
| `client\\npm.cmd run check` | typecheck、零 warning lint、16 files/104 tests、覆盖率和 production build 全通过 | 18 s |
| `git diff --check` | 通过；仅有 Windows 行尾提示 | 提交前检查 |

前端覆盖率保持 Statements 86.66%、Branches 80.75%、Functions 87.50%、Lines 91.77%。

## 真实三轮小样本

命令：`server\\mvnw.cmd -Devaluation.smoke=true -Devaluation.commit=7fc2494+t2 -Dtest=EvaluationBaselineLocalIT test`。

- 首次运行按预期被 `VERSION_DRIFT` 阻断，证明旧的 `bge-m3-v1/chunk-v1` 声明不能与实际 `siliconflow-bge-m3-1024-v1/chunk-5m-v1` 混算。
- 修正为数据库实际冻结版本后，发现仍运行的旧 Compose Worker 抢占 repetition 2/3；旧 Worker 写入 `EVALUATION_EXECUTION_FAILED`。停止旧 server、保持依赖容器运行后，用全新 series 重跑。
- 最终 1/1 集成测试通过，三轮 run 全部 `COMPLETED`，最终门耗时 179 s（含有界续跑遗留 REQUESTED）；三轮检索 outcome/rank 均为 `COMPLETED/null`，确定性一致。
- 三轮答案均保留 `UNKNOWN / ANSWER_DEPENDENCY_UNAVAILABLE`，没有伪装成拒答或成功；series 报告的 `answerableStatusAccuracy` 有 3 个 repetition 和 UNKNOWN 分布。
- 每轮精确关联 3 个既有 `model_call`，input units 均为 22；调用状态覆盖 `SUCCEEDED`、`FAILED`、`UNKNOWN_OUTCOME`。每轮 3 个调用均缺价，因此 `estimatedCost=null`，未按零成本处理。
- 真实数据保留在本地 MySQL；未生成或修改 Git 中的 M3 baseline 报告。

## 浏览器验收

重建并启动当前 server 镜像后，使用 Codex 内置真实浏览器访问 `http://127.0.0.1:5173/`：

- 页面标题 `VideoPilot`，h1 为“回到视频，也回到证据。”；`innerWidth=1280`、`clientWidth=scrollWidth=1265`、main 1217px，无横向溢出。
- Console 只有 Vite connection debug，warning/error 为 0。
- T2 不引入工作台 UI，正文仍不含“评测”，没有提前暴露 T3 功能。
- 内置浏览器客户端阻止直接打开 JSON API（`ERR_BLOCKED_BY_CLIENT`）；报告映射由 `EvaluationControllerTest` 覆盖，真实 MySQL API 数据路径由 Store/Local IT 覆盖。该限制不记为 API 通过证据。

## 未执行项

- 未运行完整 `verify -Pintegration`；本任务直接相关的迁移、Store 和真实三轮生产链路均已执行。完整集成、安全与供应链门保留到 T5 退出。
- T2 没有前端功能，因此浏览器只验收既有页面无回归；完整 report/compare/export 用户路径属于 T3。
