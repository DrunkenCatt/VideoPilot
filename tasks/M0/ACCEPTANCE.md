# M0 验收记录

日期：2026-10-29
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

## 远端 CI 证明

GitHub Actions：[CI run 40837414790](https://github.com/DrunkenCatt/VideoPilot/actions/runs/40837414790)，提交 `5801f0a`。

| Job / 检查 | 状态 | 证据与结论 |
| --- | --- | --- |
| backend | 通过（39 秒） | Java 21、22 项测试、ArchUnit 和 JaCoCo 门通过 |
| frontend | 通过（26 秒） | 类型、Lint、测试、覆盖率、构建和 High/Critical npm audit 通过 |
| integration | 通过（1 分 27 秒） | 五项 Compose 依赖、Flyway 和真实边界集成测试通过 |
| Gitleaks | 通过 | 仓库秘密扫描未发现泄露 |
| OWASP Dependency-Check | 未通过（负责人接受） | CVSS 7 阻断仍发现依赖漏洞；报告作为 CI artifact `dependency-check-report` 保留，工作流和阈值未修改 |
| M0 人工退出确认 | 已确认 | 2026-10-29，项目负责人要求暂时忽略安全检查失败、结束 M0 并进入 M1 |

## 退出门结论

M0 的工程骨架、契约、空业务环境、五依赖诊断、迁移、前端、覆盖率、秘密扫描和 benchmark 合同均已取得通过证据。OWASP 高危依赖扫描仍失败，这是已知且未修复的安全债务，不等同于全绿 CI；项目负责人明确接受该例外并批准进入 M1，因此 M0 于 2026-10-29 收尾。后续依赖修复完成前，安全 Job 继续保持失败并保留 CVSS 7 阻断阈值。
