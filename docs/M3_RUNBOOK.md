# M3 运行手册：混合检索与有证据问答

本手册只覆盖已在 M3 验证的能力：本地教学视频经过 Context、5 分钟 Chunk、BGE-M3/Qdrant 索引后，执行三路混合检索，并由 SiliconFlow DeepSeek 生成候选回答；Java 证据门最终发布带时间戳引用或明确拒答。

## 1. 启动前提

- JDK 21、Node.js 22.12+、Docker Desktop 和 FFmpeg；Compose 镜像内已包含 FFmpeg 与 Tesseract `chi_sim+eng`。
- 从 `.env.example` 复制本地 `.env`，填写 `SILICONFLOW_API_KEY`。该文件受 Git 忽略；不要把真实 Key 写进 YAML、日志、命令输出或提交。
- 完整中文视频解析需设置 `ASR_ENABLED=true`、`OCR_ENABLED=true`、`TESSERACT_LANGUAGE=chi_sim+eng` 和 `ANALYSIS_PIPELINE_VERSION=m3-evidence-qa-v1`。
- SiliconFlow Key 必须同时有 TeleSpeechASR、`BAAI/bge-m3` 和 `deepseek-ai/DeepSeek-V4-Flash` 的可用额度。HTTP 402 是供应商账户/额度结果，不应被应用伪装为解析或证据拒答。

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\check-toolchain.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\dev-up.ps1 -Build
powershell -ExecutionPolicy Bypass -File .\scripts\dev-check.ps1
```

后端为 `http://localhost:8080`，前端开发服务器默认为 `http://localhost:5173`。服务启动后先确认 `docker compose ps` 中六个服务均健康。

## 2. 最小验收流程

1. 登录本地账号并上传教学视频；等待媒体探测为“可分析”。
2. 使用 M3 pipeline 创建 Job。Job 必须真实经过 `CONTEXT_BUILDING -> INDEXING -> READY`。
3. READY 页面应显示唯一播放器、ASR/OCR/关键帧时间线和证据问答区。
4. 提交可回答问题。只有每条 Claim 至少绑定一条硬门通过的 Citation 才显示 `ANSWERED`。
5. 点击或用 Enter 激活引用；播放器应 seek 到证据时间窗并高亮 Segment。
6. 提交视频未覆盖的问题；应显示明确拒答，而不是伪造答案或通用依赖错误。
7. 刷新页面；已发布回答、拒答、引用和降级事实应从 MySQL 恢复。

## 3. 固定版本与资源上限

| 项目 | M3 值 |
| --- | --- |
| Pipeline | `m3-evidence-qa-v1` |
| Chunk | `chunk-5m-v1`，单窗最多 300,000 ms |
| Index schema | `m3-index-schema-v1` |
| Embedding | SiliconFlow `BAAI/bge-m3` / `siliconflow-bge-m3-1024-v1` / 1024 维 |
| Qdrant | `videopilot_chunks_m3_bge_m3_1024_v1` / cosine |
| Retrieval | `hybrid-0.60-0.25-0.15-v1` |
| LLM | SiliconFlow `deepseek-ai/DeepSeek-V4-Flash` |
| Prompt | `answer-evidence-v1` |

Embedding 默认单批 4 条、单条 6,000 字符/8,192 Token、总字符 100,000、响应 8 MiB、并发 1、阶段尝试 3 次。检索候选上限 50，query 上限 1,000 字符。回答最多 1,200 output tokens、20,000 Prompt 字符、64 KiB 响应、并发 1、最低证据分 0.20。

任何版本或限制变化都必须更新输入指纹和 Trace；不能在原版本名下静默改变行为。

## 4. MySQL 与 Qdrant 边界

MySQL 保存 `video_chunk`、`video_chunk_embedding`、`video_index`、`model_call`、Answer/Claim/Citation 和阶段事实，是唯一真源。Qdrant 只保存可删除、可重建的查询索引；Redis 丢失也不能改变业务完成事实。

Qdrant payload 只包含 owner/media/context/index、Chunk UUID 和时间窗，不保存完整 Prompt 或模型原始响应。稳定 Chunk UUID 同时作为 point ID，因此重复 upsert 幂等。

collection 删除后的重建边界已由 `QdrantVectorIndexAdapterIT` 验证：再次执行同一索引 application stage 时，Adapter 建立相同 schema 并从 MySQL 向量事实 upsert，Embedding 调用增量为 0。M3 尚未提供公开的 Repair/Replay UI；生产式人工 Replay 属于 M4。在 M3 本地环境发生 collection 丢失时，先恢复 Qdrant，保持 MySQL 不变，并使用受控测试/维护入口重新执行索引阶段，不能手工伪造 `video_index=READY`。

## 5. 降级与错误语义

| 故障 | 系统结果 | 操作 |
| --- | --- | --- |
| Qdrant 不可达/collection 缺失 | `VECTOR_STORE` 降级；从 MySQL 向量执行有界内存 cosine，并保留 keyword/OCR | 恢复 Qdrant；不要删除 MySQL Chunk/Embedding |
| query Embedding 不可用 | `SEMANTIC` 降级；保留 keyword/OCR | 检查 SiliconFlow 状态、Key 和额度；不要把结果标成完整语义检索 |
| 索引 Embedding 可重试失败 | 阶段按有界尝试处理 | 查看稳定错误码；达到上限后保留明确降级 |
| Embedding/LLM UNKNOWN | 保持 UNKNOWN/待复核，不自动重发 | 先核对 `model_call` 和供应商侧结果，避免重复费用 |
| DeepSeek timeout/坏 JSON | 依赖失败；不包装为“视频无答案” | 检查 timeout、供应商状态和响应合同 |
| 无命中/低分/引用硬门失败 | 明确 `REFUSED` | 这是安全结果；不要放宽硬门让 Demo 通过 |

## 6. 诊断

```powershell
docker compose ps
powershell -ExecutionPolicy Bypass -File .\scripts\dev-check.ps1
docker compose logs --tail 200 server
docker compose logs --tail 200 qdrant
```

健康接口只返回组件 UP/DOWN，不返回连接串或内部异常。模型调用只检查 `operation_type`、状态、稳定错误码、Token、延迟和 `price_status`；不要导出 Prompt、原始响应或完整转录。

若 `.env` 已修改，必须重建或重建 server 容器，单纯重启不会把新增环境值写入旧容器：

```powershell
docker compose up -d --build --force-recreate server
```

## 7. 基线与质量门

本地工程种子不会进入 Git，报告是脱敏输出。运行前使用一次性数据库并显式传候选 commit：

```powershell
cd server
.\mvnw.cmd -Dtest=RetrievalBenchmarkLocalIT -Dbenchmark.commit=<候选commit> test -Pintegration
```

报告位于 `benchmark/reports/m3-t3-baseline.json` 与 `benchmark/reports/m3-t6-answer-baseline.json`。小样本、单视频、单复核者结果只能称为工程基线。远端 timeout、拒答和引用失败必须如实保留，不能重试到绿或修改标注。

任务结束执行：

```powershell
cd server
.\mvnw.cmd test
.\mvnw.cmd verify -Pintegration

cd ..\client
npm run check
npm audit --audit-level=high

cd ..
docker compose config --quiet
powershell -ExecutionPolicy Bypass -File .\scripts\security-check.ps1
git diff --check
```

实际执行结果、环境、失败和未执行项记录在 `tasks/M3/ACCEPTANCE.md`；不能用 suppression、降低覆盖率或删除断言换取通过。
