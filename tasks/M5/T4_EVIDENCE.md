# M5 T4 草稿准备证据

状态：真实题目与双审工作表已准备，T4 发布退出门尚未通过。

## 素材

`benchmark/local/m5-t4/` 保存 4 条从 Wikimedia Commons 下载的短视频；该目录和原始媒体均被 Git 忽略。第 5 条复用 M3 已获项目负责人本地评测授权的 Redis 视频。每条素材的来源、许可、授权说明、时长、语言、主题和 SHA-256 分别记录在 dev/test draft manifest，完整来源页不进入运行时消息或报告。

下载素材均小于 20 MiB，最长约 3 分钟；没有下载超过 1 小时的视频。`cv2` 读取的时长和 SHA-256 已在本地核对。

## 校验器

`benchmark/tools/validate_m5_dataset.py` 是无第三方运行依赖的最小校验器，覆盖重复 case/question、题量、精确/模糊/OCR 目标数量、证据窗、双人批准、视频 split 泄漏、manifest 计数和由 manifest + cases + review index 共同计算的指纹。`benchmark/tools/apply_m5_reviews.py` 只在两名不同 reviewer 都批准时标为 approved；分歧保持 `in_review`。

本地真实题目位于 `benchmark/local/m5-t4/{dev,test}-{retrieval,answer}-cases.jsonl`，双审表为 `reviewer-1.csv` 和 `reviewer-2.csv`。题目来自 Redis 已复核上下文、TeleSpeechASR 转写或每 10 秒关键帧 OCR；无音轨的 Audacity/ASL 样本没有伪造 ASR。正式数据仍待两名真人逐题核对，自动生成题不得直接计入分母。

## 当前门状态

- 视频数量：5/5，权利字段已记录。
- 检索题：120 条真实草稿；48 exact / 48 fuzzy / 24 OCR，0 条进入正式计分。
- 答案题：30 条真实草稿；25 answerable / 5 unanswerable，0 条进入正式计分。
- 双人复核：0，不能宣称通过。
- test：已按视频隔离，但未解封。

## 本次验证

- dev/test 两组 manifest + cases + review index 的发布校验：Schema、题量、比例、重复、证据窗、split 和 fingerprint 均通过；按预期只剩双审失败（dev 0/90、test 0/60、合计 0/150）。
- `python -m unittest -v test_apply_m5_reviews.py test_validate_m5_dataset.py`：7/7 通过；先观察到新合同测试失败，再完成最小实现。提交前反转“reviewer 必须不同”的判断，相关 3/3 测试均失败，恢复后 mutation 未存活。
- `python` + `jsonschema`：manifest Schema 通过；`git diff --check` 通过。
- `server\mvnw.cmd -q -Dtest=M5ContractTest test`：通过。
- `client\npm.cmd run check`：18 个文件、115 个测试通过，覆盖率 87.31% statements / 82.19% branches，构建通过。
- 本切片没有改动前端工作台；额外使用真实 headless Edge 登录 `m1-reviewer` 并打开评测工作台：9 个既有数据集可见；登录后的 Console error/warning 为 0；CDP 320x720 下 `innerWidth=clientWidth=scrollWidth=320`。
