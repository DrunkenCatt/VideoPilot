# M5 T5 正式基线证据

状态：最新候选 `5e71014` 的正式 DEV 三轮已完成；严格 Claim-Evidence 支持率提高到 28.57%～54.55%，但仍低于 85%，引用定位率也低于 95%；分阶段硬门阻止 TEST 重跑，M5 阻断。

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

## 正式基线后纠偏：Claim / required facts 计分

- 根因：旧实现逐个 Claim 检查题目的全部 required facts；正式题通常包含两个事实并由两个 Claim 分别回答，因此即使整份答案完整且每个 Claim 有合法引用，也会得到 `0/2`。
- 修复语义：先要求整份答案通过合格 Claim 覆盖全部 required facts，再只给自身覆盖至少一个 required fact 的 Claim 计分；任一事实遗漏时整份答案的 Claim 均不计支持。引用不能为空，allowed channel、冻结 Segment 和 forbidden claim 规则保持不变，85% 硬门与分母不变。
- RED：两个 Claim 分别覆盖两个 required facts 的用例由预期 `2` 得到实际 `0`。GREEN：该用例得到 `2/2`；新增“遗漏一个 required fact”反例保持 `0/1`，防止以局部事实通过完整性门。
- 定向测试 `EvaluationBaselineRunnerTest,EvaluationReportCalculatorTest` 为 9/9 通过；完整后端门为 396 tests、0 failure/error、1 个既有条件 skip，JaCoCo 与 ArchUnit 通过。
- 重新打包并重建 Compose server 后，同一系统账号 `m1-reviewer` 可读取评测工作台、冻结 `0.2.0` 和 `strictClaimEvidenceSupport` 报告，Console warning/error 为 0；未重算已发布历史报告，也未发起新外部调用。

## 正式基线后纠偏：宿主机 Qdrant 地址

- 根因：正式夹具运行在宿主机，本机 Compose 发布 Qdrant 到 `16333`，但宿主机进程未配置 `QDRANT_BASE_URL` 时回落到 `localhost:6333`；Compose server 自身一直正确使用容器网络 `qdrant:6333`。因此正式基线的 `VECTOR_STORE` 降级来自地址错误，不代表 MySQL 是向量真源；MySQL 仅保存可重建的耐久向量事实和有界 fallback。
- 修复：本机 `.env` 与受版本控制的 `.env.example` 均明确使用 `QDRANT_BASE_URL=http://localhost:16333`、`QDRANT_HEALTH_URL=http://localhost:16333/healthz` 和 `16333/16334` 发布端口；容器内地址保持不变。
- RED / GREEN：`HostEnvironmentExampleTest` 先得到实际 `6333`、预期 `16333` 的失败，修复后 1/1 通过。宿主机 `/healthz` 返回健康，容器内实际环境仍为 `http://qdrant:6333`。
- 真实 Qdrant `QdrantVectorIndexAdapterIT` 随机创建 collection、发布向量、按 owner/media/context 查询、删除后从耐久事实重建并再次查询，1/1 通过（31.770 秒），没有走 MySQL 相似度 fallback。
- 完整后端门为 397 tests、0 failure/error、1 个既有条件 skip，JaCoCo 与 ArchUnit 通过。
- 浏览器以 `m1-reviewer` 打开 READY Job 和问答区，Console warning/error 为 0。两次最小问答尝试均被既有 `BUDGET_EXHAUSTED` 硬门在外部调用前拒绝；未降低预算门、未产生费用，也未把该拒绝误记为 Qdrant 故障。

## READY 后问答预算期限纠偏

- 数据库复核确认前述拒绝均为 `BUDGET_EXHAUSTED:m4-limits-v3:DEADLINE`：两个 Job 分别约 17 小时和 20 小时，Answer 使用量分别为 18/20 和 0/20，且拒绝前没有新增供应商调用。因此根因不是 API Key 欠费，而是从 `analysis_job.created_at` 起算的 6 小时后台期限被错误用于 READY 后交互问答。
- ADR-0012 将 deadline 限定为后台解析、索引、Recovery、Retry 和 Replay；READY 后 Query Embedding 与 Answer 仍复用原 Job 的调用数、输入/输出和已知费用硬门，不提高阈值，不新增预算抽象，也不改写历史拒绝记录。
- RED：`ExecutionBudgetPolicyTest` 新场景先因缺少交互预算入口产生 7 个编译错误。GREEN：`ExecutionBudgetPolicyTest,RetrievalApplicationServiceTest,AnswerApplicationServiceTest` 共 23/23 通过，覆盖过期 Job 可交互、后台仍返回 `DEADLINE`，以及 CALL_COUNT、INPUT_UNITS、OUTPUT_UNITS、KNOWN_COST 继续生效。
- 后端完整门：`server\\mvnw.cmd test` 共 398 tests、0 failure/error、1 个既有条件 skip，JaCoCo 与 ArchUnit 通过。随后以已通过测试的新 JAR 重建本地 server 容器。
- Docker 集成门先在隔离的 `videopilot_m5_gate` 数据库执行 90 项：84 项通过、2 项既有条件 skip，4 项因宿主缺少 `ffprobe`、MinIO 健康地址未映射和未向基线测试注入现有供应商 Key 而失败。补齐这些运行前提后，仅重跑失败集合并得到 4/4 通过；临时数据库随每轮删除，既有 `videopilot_browser` 未参与，测试前备份的两份 M3 报告在 finally 中原样恢复。
- 真实浏览器在创建约 21 小时的 READY `free-media-licenses.webm` Job 上提交最小问题，得到 `ANSWERED` 和两条可跳转 OCR 引用；MySQL 中 `degraded_channels=[]`，Query Embedding 与 Answer 均为 `SUCCEEDED`，分别记录 19 input units，以及 1008 input / 161 output units。页面未显示向量库降级，容器日志没有 ERROR/WARN、`BUDGET_EXHAUSTED` 或 Qdrant 故障。该记录仅作为浏览器 Smoke，不替代冻结 TEST 的效果结论。

## 候选预算隔离与分阶段硬门

- ADR-0013 将正式评测调用按“同一 `series_id` + 同一 AnalysisJob”聚合。三次 repetition 共享原 `m4-limits-v3` 数值门，不同候选、历史人工问答和旧系列不再互相消耗；普通 READY 查询仍按 Job 聚合，历史记录不清零、不改写。
- RED 首先证明现有预算接口和 JDBC 聚合不认识评测系列；GREEN 覆盖历史 Job 已耗尽但新系列可用、普通交互仍被拦截、同系列跨 repetition 合并，以及其他系列和普通调用被排除。真实 MySQL `JdbcExecutionBudgetStoreIT` 2/2 通过。
- 正式入口改为显式 `m5.release.phase=DEV|TEST`。DEV 完成三轮后逐轮检查 Recall@3、严格 Claim 支持和引用定位三项门；TEST 入口从 MySQL 重新读取同一候选的三个 DEV Run，任何 Run/指标缺失或低于门槛时，在 TEST 导入和调用前停止。
- `M5ReleaseGateTest` 2/2 通过；不付费的 `M5ReleaseBaselineLocalIT` 预检 1/1 通过，确认五条来源、冻结 DEV fingerprint 和宿主机依赖可读取，外部模型调用为 0。

## 候选 `d9e1d51` 正式 DEV 结果

- 三个 Run `389a974b-c54b-4bcb-832a-23745b5db742`、`2e9bd463-996c-404f-aefc-00b52de32437`、`cd906e91-c44a-465c-a706-4e33814c0acb` 均完成 90/90 case，无 Run 级错误；正式命令耗时 13 分 03 秒。
- 三轮 Recall@3 均 100%，Hit@1 均 95.83%，MRR@3 均 97.92%，引用定位率均 100%，拒答正确率均 100%。
- 严格 Claim-Evidence 支持率分别为 `0/7`、`0/9`、`0/9`；可回答状态命中率分别为 20%、26.67%、26.67%。证据能正确定位，但已发布 Claim 仍不包含冻结 required facts 的精确完整覆盖。
- 每轮 108 次调用且无 `BUDGET_EXHAUSTED`，证明新候选系列不再继承历史 Job 调用数；三轮 price status 均保持 unavailable。
- 三份 DEV 报告 Schema 3/3 通过，敏感键命中 0。DEV 硬门以明确错误退出，TEST 阶段没有启动，历史 TEST 报告未覆盖。
- Compose server 恢复健康后，以 `m1-reviewer` 在浏览器读取冻结 DEV：最新三轮均显示 `COMPLETED 90/90`，第一轮报告显示 `72/72`、`0/7`、`7/7`、108 calls、成本 unknown；展开版本可见 commit `d9e1d51`。Console warning/error 为 0，浏览器未发起写操作。

## 候选 `5e71014` 正式 DEV 结果

- 三个 Run `014f0b57-197b-4ade-a2ab-677900d32364`、`7aa79c0d-46b9-43cb-b1cd-3df06f8a4d17`、`16812dda-5f49-4f07-a93d-ac8125ee0999` 均完成 90/90 case；execution fingerprint 为 `95d02ef85b2358f79e1603b9e9cdc90d0312d3e179c1191af9899fdcd0b21859`。
- 三轮 Recall@3 均 100%，Hit@1 均 95.83%，MRR@3 均 97.92%，拒答正确率均 100%；检索和不可回答判断没有回归。
- 严格 Claim-Evidence 支持率分别为 `4/14=28.57%`、`7/15=46.67%`、`6/11=54.55%`；引用定位率分别为 `14/17=82.35%`、`14/18=77.78%`、`11/13=84.62%`。模型更常生成答案且部分释义可正确计分，但逐 Claim 的精确引用和全部 required facts 覆盖仍不稳定。
- 可回答状态命中率分别为 40%、40%、33.33%；每轮 108 次调用中 106 次成功、2 次失败，无 `UNKNOWN` 或 `BUDGET_EXHAUSTED`。价格继续为 unavailable。
- 三份 DEV 报告 Schema 3/3 通过，敏感键命中 0。正式命令耗时 8 分 59 秒并在 DEV 硬门停止，TEST 未执行、历史 TEST 报告未覆盖。
- 容器化完整门为 407/407 快速测试、91 项集成测试零 failure/error（2 项发布开关 skip），JaCoCo/ArchUnit 通过；前端 115/115 和生产构建通过。Linux OCR 超时夹具修复另有 8/8 定向容器测试。
- Compose server 以新 JAR 恢复 healthy，HTTP 健康端点返回 200。浏览器只读验收受当前工具的 `ERR_BLOCKED_BY_CLIENT` 阻断，Chrome 控制通道不可用，因此本轮不宣称页面/Console 验收通过，也未发起浏览器写操作。
