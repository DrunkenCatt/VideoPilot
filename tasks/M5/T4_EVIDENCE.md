# M5 T4 草稿准备证据

状态：150 条真实题目已完成双审；两条无音轨素材已替换并通过真实解析，待将相关题目的 expected hit 绑定到已发布 Segment 后冻结。

## 素材

`benchmark/local/m5-t4/` 保存 4 条从 Wikimedia Commons 下载的短视频；该目录和原始媒体均被 Git 忽略。第 5 条复用 M3 已获项目负责人本地评测授权的 Redis 视频。每条素材的来源、许可、授权说明、时长、语言、主题和 SHA-256 分别记录在 dev/test draft manifest，完整来源页不进入运行时消息或报告。

下载素材均小于 20 MiB，最长约 3 分钟；没有下载超过 1 小时的视频。`cv2` 读取的时长和 SHA-256 已在本地核对。

## 校验器

`benchmark/tools/validate_m5_dataset.py` 是无第三方运行依赖的最小校验器，覆盖重复 case/question、题量、精确/模糊/OCR 目标数量、证据窗、双人批准、视频 split 泄漏、manifest 计数和由 manifest + cases + review index 共同计算的指纹。`benchmark/tools/apply_m5_reviews.py` 只在两名不同 reviewer 都批准时标为 approved；分歧保持 `in_review`。

本地真实题目位于 `benchmark/local/m5-t4/{dev,test}-{retrieval,answer}-cases.jsonl`，双审表为 `reviewer-1.csv` 和 `reviewer-2.csv`。题目来自 Redis 已复核上下文、TeleSpeechASR 转写或每 10 秒关键帧 OCR；无音轨的 Audacity/ASL 样本没有伪造 ASR。`zzm` 与 `qq` 分别逐题完成 150/150 复核，全部批准；合并器验证 reviewer 身份不同后才写入 `reviewerCount=2`。

## 当前门状态

- 视频数量：5/5，权利字段已记录。
- 检索题：120 条已审核草稿；48 exact / 48 fuzzy / 24 OCR，尚未进入正式计分。
- 答案题：30 条已审核草稿；25 answerable / 5 unanswerable，尚未进入正式计分。
- 双人复核：`zzm` 150/150、`qq` 150/150；无分歧题。
- split：dev 3 条视频、72 retrieval + 18 answer；test 2 条视频、48 retrieval + 12 answer，视频无交叉。
- manifest：保持 `draft`，待真实解析产物绑定后重算指纹并冻结。
- M4 可靠性集：继续复用 `scripts/run-m4-reliability-gate.ps1` 的 20/20 恢复样本，不复制成视频题或改变其固定分母。

## 本次验证

- dev/test 两组 manifest + cases + review index 的结构校验输出 `VALID`：Schema、题量、比例、重复、证据窗、split、双审和 fingerprint 全部通过。该校验不能替代运行时段 ID 存在性校验。
- `python -m unittest -v test_apply_m5_reviews.py test_validate_m5_dataset.py`：7/7 通过；先观察到新合同测试失败，再完成最小实现。提交前反转“reviewer 必须不同”的判断，相关 3/3 测试均失败，恢复后 mutation 未存活。
- `python` + `jsonschema`：manifest Schema 通过；`git diff --check` 通过。
- `server\mvnw.cmd test`：383 个测试，0 failure / 0 error，1 个既有 skip，JaCoCo 通过。
- `client\npm.cmd run check`：18 个文件、115 个测试通过，覆盖率 87.31% statements / 82.19% branches，构建通过。
- `scripts/run-m4-reliability-gate.ps1`：独立 `videopilot_m4_gate` 数据库的 20/20 恢复、并发、Redis、RabbitMQ、Outbox 和 Qdrant 矩阵通过。
- 本切片没有改动前端工作台；额外使用真实 headless Edge 登录并打开评测工作台：9 个既有数据集可见；登录后的 Console error/warning 为 0；CDP 320px 下 `innerWidth=clientWidth=scrollWidth=320`。

## 人工复核与抽检

两位 reviewer 对全部 150 条题逐题检查，因此同时覆盖每类题和每条视频，样本量不是抽样估计：`zzm` 150 条、`qq` 150 条。两份工作表均无空 decision/reviewer，全部为 `approved`，无 disputed/rejected。工作表、问题全文、转录和 OCR 留在 Git 忽略目录；Git 只提交计数、指纹和权利元数据。

## 原阻断与替代素材验证

- `ffprobe` 实测 Audacity 只有 Theora video/data，ASL 只有 VP9 video，两者均无 audio stream。
- `VideoContextPipelineApplicationService` 在 transcript 为空时返回不可重试的 `ASR_EMPTY`；`VideoContextStore.ContextPublication` 也要求 ready context 的 ASR 状态为 `AVAILABLE`。
- `author_real_cases.py` 使用 UUID5 从视频/时间窗生成 `expectedHits.segmentId`。这些 ID 尚未与 MySQL/Qdrant 中实际发布的 `VideoSegment` 绑定，不能作为真实检索基线冻结。

2026-11-09 使用两条短时中文 Wikidata 教学视频替换无音轨素材：

- `wikidata-book-zh.webm`：156466 ms，真实浏览器 Job `bc1cec42-50b9-45b2-8361-891d8f07a040` 进入 `READY`，发布 3 个 Segment。
- `wikidata-qualifiers-zh.webm`：276637 ms，真实浏览器 Job `8f29953e-c507-4b00-b7ac-66a6dea88b37` 进入 `READY`，发布 5 个 Segment；5 次 ASR 输入合计 276637 ms、输出合计 524 Unicode 字符，10 次 OCR 输入合计 4099200 像素、输出合计 360 字符。

解析过程中发现并修复两项运行时硬门问题：ASR `output_units` 曾误写时长，以及全局预算曾混加毫秒、像素、字符和 Token。当前 `m4-limits-v3` 按 operation 分别聚合；ASR 另有 1200 字符/分钟单次上限，120 秒输入上限为 2400 字符。复用中的 Context 由后续 Job 发布时仍校验 owner、media、pipeline 和 version CAS。真实浏览器两条任务均 READY，限定符页面时间轴可见，Console warning/error 为 0。

剩余阻断只有数据集绑定：必须用上述 8 个实际 Segment 重写相关题目并重新完成结构校验、双审索引一致性和冻结 fingerprint；不得沿用 UUID5 占位 ID。
