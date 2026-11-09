# M5 T5 正式基线证据

状态：准备正式运行；冻结数据集导入身份薄切片已完成，尚未触发发布规模的外部模型调用。

## 正式运行入口预检

- 新增默认关闭的 `M5ReleaseBaselineLocalIT`。只有同时提供 `m5.release=true`、候选 commit，并额外提供 `m5.release.execute=true` 时才会执行外部模型调用；缺少执行开关时只校验 5 条来源、owner、READY Job、已发布 Segment、DEV 导入和冻结 fingerprint。
- 预检首次在 Spring 启动阶段因 integration profile 启用但未配置完整 Tesseract 而失败；夹具随后只关闭不参与本评测的 ASR/OCR 启动项，保留真实 MySQL、MinIO、Qdrant、Embedding、回答与证据门。直接调用 Failsafe 不会自动重新编译测试，因此正式命令显式包含 `test-compile`。
- `server\mvnw.cmd test-compile -Pintegration "-Dit.test=M5ReleaseBaselineLocalIT" "-Dm5.release=true" "-Dm5.candidate.commit=20a1664" failsafe:integration-test failsafe:verify`：1/1 通过，最终耗时 32.112 秒；未设置 `m5.release.execute`，外部模型调用为 0，TEST 尚未导入或解封。运行入口在每轮结束后立即检查失败状态，不会在当前退出门失败后继续下一轮。
- 浏览器无回归检查使用独立应用内浏览器打开 `http://localhost:5173/`：公开登录面可访问，账号/密码控件和安全 Cookie 说明可见，Console error/warning 为 0。本切片不改前端；登录后的正式 dataset/run/trace/export 验收保留到基线运行完成后执行。

## 冻结身份导入

- `EvaluationApplicationService.ImportDataset` 增加可选 `sourceFingerprint`；只接受小写 64 位 SHA-256。未提供时保留既有草稿/Smoke 的服务端计算行为，避免破坏已有调用方。
- 正式 `0.2.0` 导入携带 T4 冻结 manifest fingerprint，MySQL、运行、浏览器和报告因此能回指同一不可变身份，不使用第二套 fingerprint 冒充发布集。
- 测试侧 `M5ReleaseDatasetLoader` 只负责把 Git 忽略的题目、review index、真实 READY Job 与已发布 Segment 转成现有 JSONL 合同；生产检索、回答、证据门和预算逻辑不复制。
- 回答题的 required citation 时间窗在导入时绑定到重叠的已发布 Segment；检索题保留冻结的 expected hit。所有题继续由服务端校验 review 状态和 owner/Job 边界。

## RED / GREEN

- RED：`EvaluationApplicationServiceTest` 因 `ImportDataset` 不接受冻结 fingerprint 编译失败；`M5ReleaseDatasetLoaderTest` 因加载器不存在编译失败。
- GREEN：`EvaluationApplicationServiceTest` 与 `M5ReleaseDatasetLoaderTest` 定向测试通过；非法 source fingerprint 在保存前拒绝，正式 case 能绑定运行时 Job 和答案 Segment。
- 完整门：`server\mvnw.cmd test` 共 393 tests，0 failure/error、1 个既有条件 skip，JaCoCo 与 ArchUnit 通过；第一次完整门因测试夹具违反 JSONL 一行一条而失败，修正夹具后重新完整通过，未放宽加载器。
- 前端回归：`client\npm.cmd run check` 的 18 files / 115 tests、typecheck、零 warning lint、coverage 和生产构建通过。
- 真实浏览器：刷新后视频工作台与评测工作台均可访问，9 个既有 dataset 可见，无横向溢出，Console warning/error 为 0。本切片不导入正式 dataset，因此没有提前触发外部模型调用。

## 费用门

正式 dev/test 均含答案题，合同要求各运行 3 次：共 450 个逐题执行，其中 90 次回答模型调用。正式运行工具默认关闭，必须显式提供候选 commit 和发布开关，避免普通测试意外产生费用。
