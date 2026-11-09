# M5 T4 冻结数据集证据

状态：150 条真实题目已完成双审；两条无音轨素材已替换并通过真实解析；所有 expected hit 已绑定到已发布 Segment，`0.2.0` dev/test 数据集已冻结。

## 素材

`benchmark/local/m5-t4/` 保存 4 条从 Wikimedia Commons 下载的短视频；该目录和原始媒体均被 Git 忽略。第 5 条复用 M3 已获项目负责人本地评测授权的 Redis 视频。每条素材的来源、许可、授权说明、时长、语言、主题和 SHA-256 分别记录在 dev/test draft manifest，完整来源页不进入运行时消息或报告。

下载素材均小于 20 MiB，最长约 3 分钟；没有下载超过 1 小时的视频。`cv2` 读取的时长和 SHA-256 已在本地核对。

## 校验器

`benchmark/tools/validate_m5_dataset.py` 是无第三方运行依赖的最小校验器，覆盖重复 case/question、题量、精确/模糊/OCR 目标数量、证据窗、双人批准、视频 split 泄漏、manifest 计数和由 manifest + cases + review index 共同计算的指纹。`benchmark/tools/apply_m5_reviews.py` 只在两名不同 reviewer 都批准时标为 approved；分歧保持 `in_review`。

本地真实题目位于 `benchmark/local/m5-t4/{dev,test}-{retrieval,answer}-cases.jsonl`，双审表为 `reviewer-1.csv` 和 `reviewer-2.csv`。题目来自 Redis 已复核上下文、TeleSpeechASR 转写或每 10 秒关键帧 OCR；无音轨的 Audacity/ASL 样本没有伪造 ASR。`zzm` 与 `qq` 分别逐题完成 150/150 复核，全部批准；合并器验证 reviewer 身份不同后才写入 `reviewerCount=2`。

## 当前门状态

- 视频数量：5/5，权利字段已记录。
- 检索题：120 条已批准；48 exact / 48 fuzzy / 24 OCR。
- 答案题：30 条已批准；25 answerable / 5 unanswerable。
- 双人复核：`zzm` 150/150、`qq` 150/150；无分歧题。
- split：dev 3 条视频、72 retrieval + 18 answer；test 2 条视频、48 retrieval + 12 answer，视频无交叉。
- manifest：`benchmark/manifests/m5-v0.2.0-{dev,test}.json` 已于 `2026-11-09T13:30:45Z` 冻结；dev 指纹为 `98667e464bd7099736b91c32ee2ca1013df3d330c7d136bc3a7777be9e8c31ca`，test 指纹为 `d190636e0efaabd3cc9cb16a68854c2033a4079ca8e6feb1eeb4e9a36167e502`。
- M4 可靠性集：继续复用 `scripts/run-m4-reliability-gate.ps1` 的 20/20 恢复样本，不复制成视频题或改变其固定分母。

## 本次验证

- dev/test 两组冻结 manifest + cases + review index 的结构校验输出 `VALID`：Schema、题量、比例、重复、证据窗、split、双审、冻结时间和 fingerprint 全部通过。
- `python -m unittest -v test_freeze_m5_dataset.py test_apply_m5_reviews.py test_validate_m5_dataset.py`：11/11 通过；冻结合同测试先因缺少实现和状态校验失败，最小实现后通过；冻结输出使用排他创建，已有版本不能原地覆盖。
- 从 `videopilot_browser.video_segment` 核对 22 个唯一 expected segment ID，找到 22 个、缺失 0 个。
- `python` + `jsonschema`：manifest Schema 通过；`git diff --check` 通过。
- `server\mvnw.cmd test`：390 个测试，0 failure / 0 error，1 个既有 skip，JaCoCo 与 ArchUnit 通过。
- `client\npm.cmd run check`：18 个文件、115 个测试通过，覆盖率 87.31% statements / 82.19% branches，构建通过。
- `scripts/run-m4-reliability-gate.ps1`：独立 `videopilot_m4_gate` 数据库的 20/20 恢复、并发、Redis、RabbitMQ、Outbox 和 Qdrant 矩阵通过。
- 本切片没有改动前端工作台；额外使用真实 headless Edge 登录并打开评测工作台：9 个既有数据集可见；登录后的 Console error/warning 为 0；CDP 320px 下 `innerWidth=clientWidth=scrollWidth=320`。
- 冻结后使用真实浏览器重新打开评测工作台：既有冻结 dataset、完整 fingerprint、复核数、逐题去敏 Trace 和运行报告可见；Console error/warning 为 0；320px 视口下 `scrollWidth=clientWidth=305`，无横向溢出。本次 `0.2.0` manifest 的运行时导入留给 T5 基线步骤，未将文件冻结误写成已导入。

## 人工复核与抽检

两位 reviewer 对全部 150 条题逐题检查，因此同时覆盖每类题和每条视频，样本量不是抽样估计：`zzm` 150 条、`qq` 150 条。两份工作表均无空 decision/reviewer，全部为 `approved`，无 disputed/rejected。工作表、问题全文、转录和 OCR 留在 Git 忽略目录；Git 只提交计数、指纹和权利元数据。

## 替代素材验证与阻断解除

- `ffprobe` 实测 Audacity 只有 Theora video/data，ASL 只有 VP9 video，两者均无 audio stream。
- `VideoContextPipelineApplicationService` 在 transcript 为空时返回不可重试的 `ASR_EMPTY`；`VideoContextStore.ContextPublication` 也要求 ready context 的 ASR 状态为 `AVAILABLE`。
- 旧版 `author_real_cases.py` 曾使用 UUID5 占位；`0.2.0` 已改为使用下述真实 Job 发布的 Segment ID，不再生成占位证据。

2026-11-09 使用两条短时中文 Wikidata 教学视频替换无音轨素材：

- `wikidata-book-zh.webm`：156466 ms，真实浏览器 Job `bc1cec42-50b9-45b2-8361-891d8f07a040` 进入 `READY`，发布 3 个 Segment。
- `wikidata-qualifiers-zh.webm`：276637 ms，真实浏览器 Job `8f29953e-c507-4b00-b7ac-66a6dea88b37` 进入 `READY`，发布 5 个 Segment；5 次 ASR 输入合计 276637 ms、输出合计 524 Unicode 字符，10 次 OCR 输入合计 4099200 像素、输出合计 360 字符。

解析过程中发现并修复两项运行时硬门问题：ASR `output_units` 曾误写时长，以及全局预算曾混加毫秒、像素、字符和 Token。当前 `m4-limits-v3` 按 operation 分别聚合；ASR 另有 1200 字符/分钟单次上限，120 秒输入上限为 2400 字符。复用中的 Context 由后续 Job 发布时仍校验 owner、media、pipeline 和 version CAS。真实浏览器两条任务均 READY，限定符页面时间轴可见，Console warning/error 为 0。

补跑 `free-media-licenses.webm` 时，前两个 60 秒切片已得到有效转写，但最后 2367 ms 静音尾段由供应商以 HTTP 200 和空 `text` 返回，旧 `asr-v1` 将其误判为永久 `INVALID_RESPONSE`。`asr-v2` 现把显式空文本持久化为 `output_units=0` 的成功静音片段，并在对齐前过滤；全片无任何非空转写时仍由 `ASR_EMPTY` 拒绝。失败 Job `3f78e78c-c0de-49c4-b265-9db0d864e10a` 保留审计，修复后的 Job `b872daf1-306c-4988-a44e-c9c658aa7b53` 进入 `READY`；`jammed-garbage-disposal.webm` Job `1b3c5384-61b4-4723-ac7e-958c71e6def3` 也进入 `READY`。

静音尾段切片先新增失败测试，再完成最小实现；相关 19/19 定向单元测试和后端完整 390 个测试通过，1 个既有条件 skip，JaCoCo 与 ArchUnit 通过。真实 headless Edge 登录后打开修复 Job，页面显示 READY 和时间线，刷新后的 Console warning/error 为 0，桌面视口无横向溢出。

替换题目重新完成双审后，生成器保留 `zzm`/`qq` 的独立结论并重算 review index；冻结工具只接受全部双人批准且索引集合一致的数据。最终 22 个唯一 expected segment ID 全部存在于 MySQL 已发布 `video_segment`，T4 阻断已解除。
