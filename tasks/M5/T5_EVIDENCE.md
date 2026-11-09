# M5 T5 正式基线证据

状态：准备正式运行；冻结数据集导入身份薄切片已完成，尚未触发发布规模的外部模型调用。

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
