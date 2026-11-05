# M2 本地运行、恢复与验收手册

本手册只描述 M2 已验证的 VideoContext 能力：FFmpeg 音频切片与关键帧、SiliconFlow `TeleAI/TeleSpeechASR`、Tesseract OCR、MySQL 分片 Checkpoint、确定性时间对齐、owner-scoped 临时播放链接和浏览器统一时间轴。M2 不包含 `VideoChunk`、Embedding/Qdrant 索引、检索、问答或 Agent Loop。

## 环境与启动

需要 Docker Desktop、Node.js 22.12+；直接在宿主机运行后端测试时还需要 JDK 21。Compose server 镜像已安装 FFmpeg、FFprobe、Tesseract 和英文语言包，不要求宿主机单独安装这些二进制。

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
powershell -ExecutionPolicy Bypass -File .\scripts\check-toolchain.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\dev-up.ps1 -Build

cd client
npm ci
$env:VITE_API_PROXY_TARGET='http://localhost:8080'
npm run dev
```

后端默认地址为 `http://localhost:8080`，前端默认地址为 `http://localhost:5173`。`.env` 只用于本机且必须保持 Git 忽略；不要把 API Key、密码、临时播放 URL、完整转录或供应商响应写入命令输出、日志或验收文档。

## M2 关键配置

| 配置 | 示例/默认值 | 说明 |
| --- | --- | --- |
| `ANALYSIS_PIPELINE_VERSION` | `m2-context-v1` | 写入 Job、Context 和复用边界；变更后不会静默复用旧版本 |
| `AUDIO_SLICE_DURATION` | `60s` | ASR 持久化切片窗口，也是无词级时间戳时的可信时间边界 |
| `CONTEXT_DERIVATION_ENABLED` | `true` | 启用 FFmpeg 派生；关闭时不能完成 M2 主路径 |
| `ASR_ENABLED` | `false` | 设为 `true` 前必须配置 `SILICONFLOW_API_KEY` |
| `TELESPEECH_ASR_MODEL_VERSION` | `TeleAI/TeleSpeechASR` | 固定 M2 ASR 模型版本 |
| `OCR_ENABLED` | `false` | 设为 `true` 后使用本机/容器 Tesseract；OCR 故障允许降级 |
| `TESSDATA_DIRECTORY` | `/usr/share/tesseract-ocr/5/tessdata` | Compose 镜像中的语言包目录 |
| `TESSERACT_LANGUAGE` | `eng` | M2 已验证的语言；启用其他语言前需在镜像中安装对应包 |
| `MINIO_PUBLIC_ENDPOINT` | `http://localhost:9000` | 必须能被浏览器访问，用于短期播放/关键帧 URL |

FFmpeg、ASR 和 OCR 分别有超时、并发、队列、输入/输出大小和最大 attempt 配置，完整列表见 `.env.example`。不要通过放大这些值掩盖损坏输入或供应商故障；长视频首先调整 `FFMPEG_DERIVATION_TIMEOUT`，并同步评估磁盘和对象存储空间。

## 启用真实 ASR/OCR

在未跟踪的 `.env` 中设置：

```text
ASR_ENABLED=true
SILICONFLOW_API_KEY=<本机临时密钥>
OCR_ENABLED=true
```

重新构建并启动 server：

```powershell
docker compose up -d --build server
powershell -ExecutionPolicy Bypass -File .\scripts\dev-check.ps1
```

ASR 成功调用记录供应商/模型、attempt、耗时、本地音频毫秒和供应商 usage。供应商没有返回价格时，`price_status` 明确为 `UNAVAILABLE`，不会伪造零成本。Tesseract 调用记录二进制/语言包版本、图片像素、文本字符和耗时，本地成本明确为 `0 USD`。

## 浏览器验收路径

1. 使用 `.env` 中的本机账号登录，选择 READY 媒体并提交分析。
2. Job 依次显示消费、媒体检查和上下文构建；页面只展示服务端持久化状态，不估算百分比。
3. READY 后播放器显示 ASR、OCR 和关键帧统一时间轴。点击 Segment、文本或关键帧应定位到对应时间；原生按钮支持键盘 Enter/Space。
4. 刷新页面，确认所选 Job、事件、Context 和 Segment 从服务端恢复。
5. 点击“续签播放链接”，确认播放器继续使用新短链；浏览器和 API 响应不得出现 MinIO 长期凭据或 object key。
6. 在 320px、375px 和桌面视口检查布局，并确认 Console 没有 warning/error。

可用下列脚本执行不输出凭据/转录的长视频上传与 Job 轮询：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-m2-compose-sample.ps1 `
  -VideoPath .\benchmark\local\<authorized-video>.mp4
```

`benchmark/local/` 被 Git 忽略。只允许把自有、明确授权、公共领域或合成视频作为正式 benchmark；来源不明的视频可以做本机临时调试，但不能提交或据此宣称产品质量。

## 恢复与降级语义

- MySQL 是 Context、Checkpoint、调用审计和 Job 状态的唯一真源。Redis `FLUSHDB`、重启或短时不可达不会删除已完成事实。
- MQ 重投、同 Job 并发和应用重启会按输入指纹、数据库 CAS 与唯一约束复用 completed 分片，已完成 ASR/OCR 的外部调用增量必须为 0。
- 外部调用中途失联属于不确定结果，记录 `UNKNOWN` 并等待复核，不自动重复可能已计费的调用。
- OCR 局部或全通道失败时 Context 可凭 ASR 进入 READY，并把 OCR 状态标为 `DEGRADED`；ASR 不可用不能由 OCR 冒充完整转录。
- Recovery 恢复同一次 Job；Replay 是后续里程碑的受控新尝试，M2 页面没有手工 Replay 能力。

## 常见故障

| 现象 | 检查与处理 |
| --- | --- |
| Context 路径 404 | 确认 Job 属于当前 owner、server 使用最新镜像，并查看 `docker compose logs server` |
| FFmpeg/FFprobe 启动失败 | 在 server 容器中检查 `ffmpeg -version` / `ffprobe -version`；重新 `--build` |
| Tesseract 全部失败 | 检查 executable、`TESSDATA_DIRECTORY` 和语言包；预期结果应是 OCR DEGRADED，而不是伪造成功 |
| ASR 401/403 | 只检查 `.env` 中密钥是否注入；不要打印密钥或完整请求 |
| ASR 429/5xx | 保留稳定错误和 retryable 分类；不要在业务线程中无限 sleep/重试 |
| 播放 URL 无法访问 | `MINIO_PUBLIC_ENDPOINT` 必须是浏览器地址，不是容器内部主机名 |
| Redis 故障 | 先确认 MySQL Job/Checkpoint 仍在，再恢复 Redis；不要从 Redis 反推完成状态 |

## 质量门与退出证据

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

M2 的长视频耗时、调用计量、恢复/降级故障矩阵、浏览器结果和未覆盖的真实世界质量限制记录在 `tasks/M2/ACCEPTANCE.md`。这些工程证据不等同于真实课程上的 ASR/OCR 质量基线；M3 建设种子集时仍需引入权利明确的真实教学材料。
