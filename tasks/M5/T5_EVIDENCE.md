# M5 T5 正式基线证据

状态：正式 DEV/TEST 基线、完整质量门和真实浏览器验收已完成；回答效果硬门失败，M5 阻断。

## 正式运行入口预检

- 新增默认关闭的 `M5ReleaseBaselineLocalIT`。只有同时提供 `m5.release=true`、候选 commit，并额外提供 `m5.release.execute=true` 时才会执行外部模型调用；缺少执行开关时只校验 5 条来源、owner、READY Job、已发布 Segment、DEV 导入和冻结 fingerprint。
- 预检首次在 Spring 启动阶段因 integration profile 启用但未配置完整 Tesseract 而失败；夹具随后只关闭不参与本评测的 ASR/OCR 启动项，保留真实 MySQL、MinIO、Qdrant、Embedding、回答与证据门。直接调用 Failsafe 不会自动重新编译测试，因此正式命令显式包含 `test-compile`。
- `server\mvnw.cmd test-compile -Pintegration "-Dit.test=M5ReleaseBaselineLocalIT" "-Dm5.release=true" "-Dm5.candidate.commit=20a1664" failsafe:integration-test failsafe:verify`：1/1 通过，最终耗时 32.112 秒；未设置 `m5.release.execute`，外部模型调用为 0，TEST 尚未导入或解封。运行入口在每轮结束后立即检查失败状态，不会在当前退出门失败后继续下一轮。
- 浏览器无回归检查使用独立应用内浏览器打开 `http://localhost:5173/`：公开登录面可访问，账号/密码控件和安全 Cookie 说明可见，Console error/warning 为 0。本切片不改前端；登录后的正式 dataset/run/trace/export 验收保留到基线运行完成后执行。

## 首次 DEV 运行阻断与恢复修复

- 候选 `a9af8d5` 的首次 DEV 正式运行在答案结果落库时失败：`CaseResult.answerId` 以 Java `UUID` 直接绑定到 MySQL `CHAR(36)`，Connector/J 将其序列化为二进制，MySQL 以错误 1366 拒绝。TEST 没有导入或运行，失败没有被记为拒答或成功。
- 独立 Compose server 的 Evaluation Worker 同时领取了另外两轮，因此三个 DEV Run 都保留为 `FAILED/EVALUATION_EXECUTION_FAILED`，每轮已完成 72/90 个检索题并关联 74 条调用审计；合计 222 条调用，其中 3 次回答模型调用。失败历史不删除、不覆盖。
- RED：真实 `JdbcEvaluationStoreIT` 给答案结果绑定已存在的非空 answer UUID 后，以相同 MySQL 错误 1366 失败。GREEN：存储 Adapter 在 JDBC 边界把非空 UUID 显式转换为字符串，并同时覆盖检索结果的 `NULL`；同一真实 MySQL 测试 1/1 通过，最终耗时 32.705 秒。
- 定向回归：`server\mvnw.cmd "-Dtest=JdbcEvaluationStoreTest,EvaluationApplicationServiceTest" test` 共 12/12 通过，JaCoCo 门通过，耗时 27.272 秒。独立应用内浏览器刷新后登录面正常，Console error/warning 为 0；本修复不改变前端合同。
- 新候选 commit 进入正式运行幂等键，失败后的重跑会创建新的 series，同时保留旧 Run；正式运行前临时停止 Compose server，避免两个 Worker 竞争，运行和浏览器验收结束后再恢复服务。

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

## `0.2.0` 正式运行结果

- 候选 `07dfb15`、execution fingerprint `15342a7f88f1a893fdcb8696c4c24587bf971bd38360e611052d67c12026dd71` 顺序完成 DEV/TEST 各三轮；正式命令 1/1 通过，总耗时 10 分 53 秒。
- DEV 90 ×3、TEST 60 ×3 全部形成 CaseResult；检索 rank 在三轮间一致，没有 `NON_DETERMINISTIC_RETRIEVAL` 或 `VERSION_DRIFT`。
- TEST 三轮 `Recall@3/Hit@1/MRR@3=100%`、引用定位率 100%、拒答正确率 100%；严格 Claim-Evidence 支持率三轮均 0%，可回答状态命中率三轮均 20%。回答效果门失败，M5 阻断。
- TEST outcome 总计 150 `COMPLETED`、27 `REFUSED`、3 `UNKNOWN`；三轮统计包含均值、样本标准差和完整 outcome distribution。
- 六份报告均通过 JSON Schema；敏感字段键扫描通过。价格缺失保留 `estimatedCost=null` 和 `unavailablePriceCalls`，未伪造零成本。
- 可靠性报告复用 `tasks/M4/ACCEPTANCE.md` 的冻结 20/20 样本摘要：恢复成功率 100%，任务丢失、重复高成本调用和非法推进均为 0；M5 没有复制第二套故障编排。
- 完整容器化质量门为 394/394 单元测试、90 项集成测试 0 failure/error（2 项显式付费开关 skip）、JaCoCo/ArchUnit 通过；前端 115/115。OWASP 因 NVD 网络重试未形成结论，Gitleaks 对历史测试文件名夹具报 1 个假阳性且工具门保持未通过，详见 `tasks/M5/ACCEPTANCE.md`。

## 最终浏览器验收

- 同一系统账号 `m1-reviewer` 登录真实 Vite/Compose 环境；DEV `90/90 approved` 与 TEST `60/60 approved` 分开显示并回指冻结 fingerprint，未读取 Cookie 或浏览器存储。
- DEV 三个成功 Run 和三个保留的失败 Run、TEST 三个成功 Run 均可展开；人工抽检覆盖检索成功、可回答、拒答和 `UNKNOWN / ANSWER_DEPENDENCY_UNAVAILABLE`，逐题 Trace 只显示 case ID、状态、计数、延迟和稳定错误码。
- 报告展示 Recall、Claim 支持、引用定位、拒答、可回答状态的分子/分母，以及全部执行版本；两个 TEST Run 的 compare 正常计算差值。
- 导出 Run `0708583a-5c43-4989-ab1c-754d008117c2` 得到 60 个结果，dataset fingerprint 和 execution fingerprint 与页面一致；精确敏感键扫描无命中。
- 刷新后状态恢复；320×720 下页面无横向溢出且局部数据集导航可滚动；Console warning/error 为 0。验收期间未发起新 Run。

## 正式基线后纠偏：可选证据合并

- 根因：同一 `chunkId/segmentId/channel` 的邻接证据先以 `selectable=false` 放入 Map，随后题目显式指定该 Segment 时，`putIfAbsent` 保留了旧值，导致本应可供模型选择的后续 Segment 仍不可选。
- RED：真实 MySQL `AnswerPersistenceAdapterIT` 同时指定两个 Segment 时只返回第一个 Segment 的可选 ASR/OCR，第二个 Segment 的 ASR 缺失；断言稳定复现该问题。
- GREEN：相同证据键合并时只允许 `selectable` 从 false 升级为 true，不改变文本、时间窗、通道或既有可选证据。真实 MySQL 测试 1/1 通过（37.196 秒）。首次启动因本机 Tesseract 配置与本切片无关而阻断，复跑仅关闭不参与该存储测试的 ASR/OCR Bean，没有跳过数据库或目标断言。
- 完整后端门首次运行时 `FfprobeMediaProbeTest` 在整套测试负载下把两个子进程误判为 2 秒超时；该类随后定向 4/4 通过，再次执行未改阈值的完整门为 394 tests、0 failure/error、1 个既有条件 skip，JaCoCo 与 ArchUnit 通过。
- 容器重建后使用同一系统账号 `m1-reviewer` 验收评测工作台：冻结数据集、三轮 Run、Trace 与报告均可读取，Console warning/error 为 0；未发起新评测、未产生模型费用。
