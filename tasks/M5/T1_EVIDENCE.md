# M5 T1 验收证据

日期：2026-11-08（Asia/Shanghai）

## 实现证据

- Flyway V17 建立四张评测表；dataset version/fingerprint、运行意图、series repetition 和 case result 均有数据库唯一约束。
- Controller 只调用 `EvaluationApplicationService`；POST 创建 run 后立即返回，Worker 通过 MySQL lease 继续执行。
- Runner 直接复用 `RetrievalApplicationService` 与 `AnswerApplicationService`。
- MySQL 集成测试模拟首题完成后 lease 过期，再次领取时只看到缺失题，最终完成同一 run。
- 本地真实 smoke 生成一个 `COMPLETED` run（2/2 case）：检索为 `COMPLETED`；答案供应商调用不确定，被持久化为 `UNKNOWN / ANSWER_DEPENDENCY_UNAVAILABLE`。

## 实际验证

| 检查 | 结果 |
|---|---|
| 初始 RED：`EvaluationApplicationServiceTest` | 编译失败，缺少 T1 service/runner，符合先失败要求 |
| 定向 application/runner/controller/contract | 10/10 通过；随后补齐 draft review 场景 |
| `JdbcEvaluationStoreTest` | 通过，并把项目总覆盖率恢复到既有 80% 门以上 |
| MySQL `JdbcEvaluationStoreIT,DatabaseMigrationIT` | 8/8 通过；Flyway 校验 17 个迁移 |
| `EvaluationBaselineLocalIT` | 1/1 通过；真实生产检索/回答代码，约 41 秒 |
| 浏览器 | 当前镜像重建后健康；1280px 页面无横向溢出，Console warning/error 为 0 |

集成验证首次暴露并修复了 `INSERT ... SELECT ... ON DUPLICATE KEY` 中歧义的 `run_id`。未通过 suppression、删除断言、跳过质量门或降低阈值处理。
