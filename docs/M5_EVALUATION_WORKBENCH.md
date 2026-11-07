# M5 评测工作台

## 用户路径

登录后的顶部导航在既有视频工作台旁提供“评测”入口。页面直接读取 owner scoped 的 dataset、run、case result 和 report：数据集横轨负责选择，运行列表负责恢复，Trace 与报告只展示 T0 允许的脱敏字段。

创建运行使用当前 dataset 和十项完整执行版本。前端只做可解释的早期禁用；DEV 必须全部批准，TEST 还必须冻结。服务端仍是最终硬门，并重新校验批准、双人复核、版本完整性和真实 pipeline version。

## 比较与导出

- 比较读取两个服务端报告，只计算同名指标及 latency、Token、调用数、成本的差值；不生成“提升”“通过”等结论。
- 成本任一侧未知时差值保持 `UNKNOWN`。
- JSON 导出由当前服务端 report 与 case result 原样组成，不包含 question、answer、Prompt 正文、转录或模型原始响应。
- 指标公式、test 隔离和双人复核规则不在 TypeScript 重写。

## 恢复与响应式

dataset 下的运行列表由 MySQL 重建，刷新和重新挂载不依赖 localStorage 或 Redis。异步读取使用 dataset revision 丢弃迟到响应；401 回到共享会话过期边界。

布局在 900px 下改为单列，dataset 横轨自身滚动，不扩大页面宽度；320px 下版本表单与指标也改为单列。全局 app grid 使用 `minmax(0, 1fr)`，避免横向内容把外层工作台撑宽。
