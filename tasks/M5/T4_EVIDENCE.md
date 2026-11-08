# M5 T4 草稿准备证据

状态：草稿队列已准备，T4 发布退出门尚未通过。

## 素材

`benchmark/local/m5-t4/` 保存 4 条从 Wikimedia Commons 下载的短视频；该目录和原始媒体均被 Git 忽略。第 5 条复用 M3 已获项目负责人本地评测授权的 Redis 视频。每条素材的来源、许可、时长和 SHA-256 记录在 `benchmark/manifests/m5-t4-draft.json`，完整来源页不进入运行时消息或报告。

下载素材均小于 20 MiB，最长约 3 分钟；没有下载超过 1 小时的视频。`cv2` 读取的时长和 SHA-256 已在本地核对。

## 校验器

`benchmark/tools/validate_m5_dataset.py` 是无第三方依赖的最小校验器，覆盖重复 case ID、题量、精确/模糊/OCR 目标数量、双人批准、视频 split 泄漏、manifest 计数和 manifest 指纹。

当前题目队列仍待人工逐题核对，正式数据不能冻结；自动生成题不得直接计入分母。下一步先完成题目证据窗和人工双审，再把 `status`、`dataVersion` 和 fingerprint 一并冻结。

## 当前门状态

- 视频数量：5/5，权利字段已记录。
- 检索题：0/120，尚未生成正式计分题。
- 答案题：0/30，尚未生成正式计分题。
- 双人复核：0，不能宣称通过。
- test：已按视频隔离，但未解封。
