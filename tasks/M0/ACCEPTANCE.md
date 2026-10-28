# M0 验收记录

日期：2026-10-28  
环境：Windows 11、Java 21.0.12.1、Docker Desktop 29.2.1、Docker Compose 5.0.2、Node.js 22.12+

## 已执行

| 检查 | 结果 | 用时 | 证据摘要 |
| --- | --- | ---: | --- |
| `server\\mvnw.cmd test` | 通过 | 19.6 秒 | 22 项单元/契约/ArchUnit 测试通过，JaCoCo 行覆盖率不少于 80% |
| `server\\mvnw.cmd verify -Pintegration` | 通过 | 36.7 秒 | 22 项快速测试和 3 项真实依赖测试通过；Flyway V1 校验且重复迁移为 no-op |
| `client\\npm.cmd run check` | 通过 | 15.6 秒 | 类型、Lint、2 项测试、100% 当前可执行行覆盖率和生产构建通过 |
| `client\\npm.cmd audit --audit-level=high --registry=https://registry.npmjs.org` | 通过 | 约 4 秒 | 0 个已知漏洞；此前发现的 High/Critical 依赖已升级 |
| `docker compose config --quiet` | 通过 | 少于 1 秒 | Compose 语法和变量展开有效 |
| `scripts\\dev-up.ps1 -Build` / `dev-check.ps1` / `dev-down.ps1` | 通过 | 首次约 1 分钟 | 空库自动迁移；MySQL、Redis、RabbitMQ、MinIO、Qdrant 全部 UP；停止后 5 个命名卷保留 |
| benchmark Schema/示例校验 | 通过 | 少于 2 秒 | 4 个 JSON Schema 2020-12 及 4 个示例通过 `jsonschema` 校验；本地原始资料路径被忽略 |

本机默认端口存在其他服务占用，因此 Compose 验收使用 18080、33306、16379、15673、19000/19001、16333/16334；容器内服务端口和 CI 默认端口不变。

## 未完成的外部证明

| 检查 | 状态 | 原因与下一步 |
| --- | --- | --- |
| `gitleaks detect --redact` | 未执行 | 本机到 GitHub Release 和 Docker Hub 的连接均失败，无法取得 Gitleaks；`security-check.ps1` 会 fail-closed，CI 使用固定镜像执行 |
| OWASP Dependency-Check | 未完成 | 插件与测试可运行，但 NVD API 连续 8 次重试仍不可达，已中止；CI 在可联网环境执行并以 CVSS 7 阻断 |
| GitHub Actions CI | 未执行 | Workflow 已创建但本地不能证明远端运行结果；首次 push/PR 后必须全绿 |
| M0 人工退出确认 | 等待 | 在 CI 全绿并完成代码评审后，由项目负责人确认是否进入 M1 |

## 退出门结论

本地工程门、空业务环境、五依赖诊断、迁移、前端、覆盖率和 benchmark 合同已通过。由于秘密扫描、OWASP 漏洞库扫描和远端 CI 尚无成功证据，M0 保持“等待外部门禁与人工确认”，不得据此进入 M1。
