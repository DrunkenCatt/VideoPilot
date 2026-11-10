# M5 冻结评测运行手册

本文用于本地或单服务器环境中的非 Agent 基线评测。MySQL 中的 Dataset、Case、Run、CaseResult 和 `model_call` 是唯一真源；`benchmark/reports/` 只保存可重建的脱敏导出，不能替代数据库事实。

## 1. 数据集导入与冻结

正式数据先在 Git 忽略的 `benchmark/local/m5-t4/` 完成标注与双人复核，再由冻结工具生成不可变 manifest。原视频、问题全文、required facts、转录、OCR 和复核工作表不得进入 Git。

```powershell
cd benchmark\tools
python -m unittest -v test_freeze_m5_dataset.py test_apply_m5_reviews.py test_validate_m5_dataset.py
python validate_m5_dataset.py `
  ..\manifests\m5-v0.2.0-dev.json `
  ..\local\m5-t4\dev-retrieval-cases.jsonl `
  ..\local\m5-t4\dev-answer-cases.jsonl `
  ..\local\m5-t4\dev-review-index.jsonl `
  ..\manifests\m5-v0.2.0-test.json `
  ..\local\m5-t4\test-retrieval-cases.jsonl `
  ..\local\m5-t4\test-answer-cases.jsonl `
  ..\local\m5-t4\test-review-index.jsonl
```

冻结后不得原地修改题目、review index、视频归属或 fingerprint。任何内容变化都必须产生新的 `dataVersion`、dataset ID 和 manifest fingerprint。运行时导入通过 `M5ReleaseDatasetLoader` 把 manifest、真实 READY Job 和已发布 Segment 绑定到现有 JSONL 合同；生产代码不建立第二套题库或检索实现。

## 2. DEV 与 TEST 使用纪律

- DEV 可以用于发现实现缺陷和候选调试；TEST 在候选版本冻结、DEV 三轮完成前不得导入或运行。
- TEST 首次运行后不得依据其逐题结果修改同一版本 Prompt、检索权重、题目、expected hit 或证据门，再把同一 TEST 包装成未见数据。
- TEST 失败时保留 Run、CaseResult 和调用审计。后续候选若要宣称效果，必须使用新候选 commit，并根据数据治理规则准备新的未见 test 版本。
- 检索指标至少重复两次；当前最小执行器为减少分支和抽象，对含答案题的数据集统一运行三次。答案指标报告均值、样本标准差和 outcome 分布。

## 3. 正式运行

正式夹具运行在宿主机，必须使用宿主机发布端口：当前 Compose 的 Qdrant HTTP/gRPC 分别为 `16333/16334`，因此 `QDRANT_BASE_URL=http://localhost:16333`、`QDRANT_HEALTH_URL=http://localhost:16333/healthz`。Compose server 仍固定使用容器网络地址 `http://qdrant:6333`，不要把宿主机地址写入容器。运行命令前应把项目 `.env` 导入当前 PowerShell 进程；`.env.example` 保持相同的非敏感端口合同。

先执行不产生外部费用的导入预检。Failsafe 命令必须包含 `test-compile`，否则可能复用旧测试 class：

```powershell
cd server
.\mvnw.cmd test-compile -Pintegration `
  "-Dit.test=M5ReleaseBaselineLocalIT" `
  "-Dm5.release=true" `
  "-Dm5.candidate.commit=<candidate-commit>" `
  failsafe:integration-test failsafe:verify
```

付费运行前临时停止 Compose server，避免其 Evaluation Worker 与夹具竞争领取；MySQL、Redis、RabbitMQ、MinIO 和 Qdrant 保持运行。确认候选 commit、DEV fingerprint 和预算后再显式增加执行开关：

```powershell
cd ..
docker compose stop server

cd server
.\mvnw.cmd test-compile -Pintegration `
  "-Dit.test=M5ReleaseBaselineLocalIT" `
  "-Dm5.release=true" `
  "-Dm5.release.execute=true" `
  "-Dm5.candidate.commit=<candidate-commit>" `
  failsafe:integration-test failsafe:verify

cd ..
docker compose up -d --build server
```

夹具顺序为 DEV 三轮全部完成并通过确定性门，然后才导入 TEST 并运行三轮。任一 repetition 失败立即停止，不继续下一轮。候选 commit 参与幂等键：同一候选重复命令返回原 series，不重复高成本调用；修复后的新候选创建新的 Replay series，旧失败 Run 保留。

## 4. 失败恢复

1. 先从 MySQL 核对 Run 状态、已完成 case、错误码和关联 `model_call`，不要删除或直接修改状态。
2. `RUNNING` 且 lease 过期时，恢复同一 Run 并跳过已有 CaseResult；不得为相同候选创建第二个逻辑 series。
3. `FAILED`、`VERSION_DRIFT`、`NON_DETERMINISTIC_RETRIEVAL` 或 `UNKNOWN_OUTCOME` 不自动重发高成本调用。修复实现或配置后提交新候选，再以新 commit 创建 Replay series。
4. 如果有第二个 Worker 竞争，先停止额外 Worker；不要放宽 CAS、移除幂等约束或删除失败历史。
5. TEST 已使用后，恢复只允许完成同一冻结执行；不得以修复名义调题或调参后继续宣称该 TEST 未见。

## 5. 报告、抽检与导出

报告从 MySQL 重建并写入 `benchmark/reports/m5-<version>-<split>-r<index>.json`。每份报告必须通过 `benchmark/schemas/evaluation-report.schema.json`，并包含 dataset/execution fingerprint、候选 commit、全部执行版本、指标分子/分母、三轮统计、调用数、Token、延迟和价格状态。

浏览器验收使用当前 owner 登录评测工作台，依次检查 dataset、三个 repetition、逐题 Trace、报告比较、JSON 导出和刷新恢复；再以 320px 视口检查无横向溢出。Console error/warning 必须为 0。抽检至少覆盖检索、可回答、不可回答、ERROR 和 UNKNOWN；浏览器值必须能回指相同 run/case 和 execution fingerprint。

允许导出 case ID、状态、rank、计数、指标、稳定错误码和版本。禁止导出用户名、ownerId、问题/答案文本、required facts、forbidden claims、引用原文、Prompt、完整转录/OCR、模型原始响应、对象 key、Embedding、堆栈、连接串或凭据。价格缺失必须显示 `unknown`，不能写为 0。

## 6. 发布门与回滚

最低门为 `Recall@3 >= 85%`、严格 Claim-Evidence 支持率 `>= 85%`、引用定位率 `>= 95%`。任一项未达到时，只能记录工程基线并阻断 M5 退出、M6 启动和 Agent 效果宣称；不得删题、修改 TEST、降低证据门或改变分母。

M5 数据库迁移只前进。应用回滚时停止新评测领取，保留数据库和报告历史，切换到已验证兼容当前 schema 的上一构建并恢复 server。不得执行 `flyway repair`、删除 Dataset/Run/CaseResult、覆盖冻结 manifest 或使用 `docker compose down --volumes`。回滚后确认历史 Run、CaseResult、fingerprint 和调用审计数量不变。

## 7. 0.2.0 正式基线结论

候选 `07dfb15` 于 2026-11-10 顺序完成 DEV/TEST 各三轮。TEST 的 `Recall@3=100%`、引用定位率 `100%`、拒答正确率 `100%`，但严格 Claim-Evidence 支持率三轮均为 `0%`，可回答状态命中率均为 `20%`。因此该结果是可复现的失败基线，M5 未退出；后续只能从 DEV 建立新候选，并为下一次效果宣称准备新的未见 TEST 版本。

可靠性不另建 M5 故障执行器，继续引用 M4 冻结报告：20/20 样本恢复成功，任务丢失、重复高成本调用和非法推进均为 0。其可复核命令和分母记录在 `tasks/M4/ACCEPTANCE.md`，M5 仅关联该版本化摘要。
