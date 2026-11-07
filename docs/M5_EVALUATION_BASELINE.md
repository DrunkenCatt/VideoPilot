# M5 评测基线：持久化纵切

本文记录 M5 T1 已交付的最小评测路径。指标公式、重复统计和报告导出仍属于 T2，不在本纵切中提前抽象。

## 数据流

1. 受控本地调用把 JSONL 行交给 `EvaluationApplicationService.importJsonLines`。
2. 服务端规范化 case 顺序并计算 case/dataset fingerprint；只有当前 owner 的 `READY` AnalysisJob 能成为 case 输入。
3. 创建运行只写 MySQL 并立即返回。`Idempotency-Key` 与请求指纹共同防止重复运行和重复高成本调用。
4. Worker 通过 MySQL 行锁和 lease 领取一个 run，按 ordinal 直接调用现有检索/回答 application service。
5. 每题结果先落 `evaluation_case_result`；进程中断或 lease 过期后只执行缺失 case。完成数、终态和版本指纹均从 MySQL 读取。

Redis、RabbitMQ 和导出文件都不参与 Dataset、Run 或 CaseResult 的真源判定。T1 没有引入第二套 Job、Outbox、模型适配器或 Runner 注册框架。

## JSONL 最小格式

```json
{"caseId":"retrieval-001","caseType":"RETRIEVAL","jobId":"<ready-job-uuid>","question":"问题","expectedHits":[{"segmentId":"<segment-uuid>"}],"review":{"status":"APPROVED","reviewerCount":1}}
```

- `caseType`：`RETRIEVAL` 或 `ANSWER`。M4 可靠性事实接入留到 T2，T1 不以占位结果冒充可靠性基线。
- `review.status`：可导入 `PENDING`、`DISPUTED`、`APPROVED`，但只有全部 `APPROVED` 的数据集可以运行。
- dev 运行要求至少一人复核；test 还要求数据集冻结且每题至少两人复核。
- Question 只保存在 MySQL；列表、运行和逐题结果 API 不返回问题、答案、Prompt、转录或模型原始响应。

## 状态与恢复

`REQUESTED -> RUNNING -> COMPLETED|FAILED` 是 T1 的完整运行状态。每题 outcome 为 `COMPLETED`、`REFUSED`、`ERROR` 或 `UNKNOWN`；供应商结果不确定时必须保存 `UNKNOWN`，不能记为成功或零成本。

运行恢复使用相同 run identity，不新建 AnalysisJob。相同 execution fingerprint 可在同一 series 中按 repetition index 创建最多三次；相同幂等键搭配不同请求指纹会被拒绝。

## 本地验证

快速验证：

```powershell
cd server
.\mvnw.cmd test
```

MySQL 迁移与续跑验证需先把仓库 `.env` 的端口/凭据加载到当前 shell，并关闭与本测试无关的 ASR/OCR 启动项，然后运行：

```powershell
.\mvnw.cmd -Pintegration "-Dit.test=JdbcEvaluationStoreIT,DatabaseMigrationIT" failsafe:integration-test failsafe:verify
```

真实小样本 smoke 由 `EvaluationBaselineLocalIT` 提供，默认跳过，避免无意触发外部模型费用。显式设置 `evaluation.smoke=true` 和候选 commit 后，它会复用一条本地已索引教学视频，新建有预算的新 Job，并执行一题检索和一题回答。
