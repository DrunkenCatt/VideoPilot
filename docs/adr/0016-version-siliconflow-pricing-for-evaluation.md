# ADR-0016：为离线评测冻结 SiliconFlow 价格版本

- 状态：Accepted
- 日期：2026-11-11

## 背景

M6 T4 必须比较非 Agent 与 Agent 候选的平均成本，并在价格未知时阻止自动晋级。SiliconFlow 的
OpenAI-compatible 响应返回 Token 用量但不返回账单金额；此前 `model_call` 因此只记录
`priceStatus=UNAVAILABLE`。项目负责人提供并确认了 SiliconFlow 官方价格页
`https://www.siliconflow.cn/pricing`，查看日期为 2026-11-11。

页面当日显示：`deepseek-ai/DeepSeek-V4-Flash` 在北京时间 02:00–08:00 的输入/输出价格为
1.50/4.50 元每百万 Token，其余时段为 3.00/9.00 元每百万 Token；缓存价格分别为
0.15/0.30 元每百万 Token。`BAAI/bge-m3` 标为免费。

## 决策

1. 新增只覆盖 MVP 固定模型的确定性价格目录，版本为
   `siliconflow-pricing-2026-11-11`，币种固定为 `CNY`。
2. DeepSeek 调用按 `model_call.started_at` 转为 `Asia/Shanghai` 后选择费率，使用供应商返回的
   输入/输出 Token 计算估算金额并保留 8 位小数。
3. 响应没有可核验的缓存命中 Token 数，因此不应用缓存折扣；缓存价格只作为来源快照记录，不能
   用总输入 Token 猜测缓存量。
4. `BAAI/bge-m3` 的成功调用记录 `0.00000000 CNY` 和 `AVAILABLE`。未知模型、无开始时间、非法
   Token 数或未纳入目录的能力继续记录 `UNAVAILABLE`，不能以零替代。
5. 该金额是离线比较用估算值，不是账单。价格变化必须新增目录版本并保留历史 `price_version`；
   不回写旧调用，也不根据当前价格重算历史评测。

## 后果

- M6 可对同一冻结数据集的候选运行计算平均成本，未知价格调用仍使成本规则失败关闭。
- 建索引和查询 Embedding 共用同一 BGE-M3 价格事实；Answer 调用按实际开始时段计价。
- ASR 仍没有本 ADR 所需的官方冻结价格事实，继续保持 `UNAVAILABLE`。
- 更新目录需要新的可追溯版本；不得静默修改既有版本常量或历史迁移。
