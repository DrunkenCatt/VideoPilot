# ADR-0011：模型计量按操作类型分别聚合和限额

## Status

Accepted

## Date

2026-11-09

## Context

ADR-0010 修正 ASR 输出语义后，真实 156 秒中文视频越过原来的输出误杀，但在
OCR 阶段触发 `BUDGET_INPUT_UNITS`。审计记录显示当前 Job 只有 36466 毫秒 ASR
输入和三张各 409920 像素的 OCR 输入；旧策略把毫秒与像素直接求和，得到
1266226 并与无单位的 1000000 比较。

同样的问题还存在于字符、Token 和像素输出。直接提高全局阈值只能暂时掩盖某个
素材，并不能形成可解释、可标定或可复现的预算。

## Decision

执行限制版本升级为 `m4-limits-v3`。历史 v1/v2 调用和失败 Trace 不改写。

- deadline、已知费用上限和调用次数仍属于整个 Job；调用次数按操作类型计数。
- `input_units` 与 `output_units` 继续保留在统一 `model_call` 审计表，但预算聚合先按
  `operation_type` 分组，再与同类型阈值比较。
- `RETRIEVAL_EMBEDDING` 与索引 `EMBEDDING` 共享同一 Embedding 预算，其余操作不得
  跨类型相加。
- 默认输入上限覆盖一条 90 分钟 MVP 教学视频及现有 Adapter 单次资源门：ASR
  5400000 毫秒、OCR 21600000000 像素、Embedding 1000000 Token、Answer
  100000 Token。
- 默认输出上限为：ASR 108000 字符、OCR 54000000 字符、Embedding 1（当前不产生
  输出计量）、Answer 24000 Token。ASR 仍优先受 ADR-0010 的 1200 字符/分钟单次门
  约束；Operation 总门不能放宽单次异常响应。

这些阈值来自“90 分钟发布素材上界 × 已冻结单次资源门/调用频率”的保守容量，
不是供应商价格换算。实际费用仍使用独立的 `estimated_cost` 和未知价格语义。

## Alternatives Considered

- 将全局输入上限提高到数十亿：继续混合不同量纲，阈值无法解释。
- 把像素换算成任意“通用点数”：缺少供应商价格或资源权重依据，会制造伪精度。
- 删除输入/输出门，只保留费用和次数：价格未知时会失去确定性资源保护。
- 针对当前视频关闭 OCR：违反 M5 多模态数据集与故障降级要求。

## Consequences

- ASR 毫秒、OCR 像素、Embedding/Answer Token 不再互相消耗预算。
- 运维汇总仍可展示原始总量，但该总量仅用于观察，不能作为硬门裁决。
- 新增操作类型时必须同时定义调用、输入和输出上限，否则配置启动或预算检查失败。
- v1、v2、v3 的预算结果必须通过 `limitsVersion` 区分，不能直接横向比较。
- 相同媒体、Pipeline 和输入指纹的 `BUILDING` Context 可由后续 Job 复用并完成发布；
  发布仍要求当前 Job 与 Context 的 owner、媒体和 Pipeline 一致，并保留 Context version
  CAS。`source_job_id` 只表示最初创建者，不再兼任发布权限。

## Validation

- 单元测试证明 ASR 36466 毫秒与 OCR 1229760 像素不会相加触发输入门，并分别验证
  各操作边界仍会失败。
- MySQL 集成测试证明调用按操作持久化聚合，索引与查询 Embedding 合并到同一预算。
- 持久化集成测试证明后续 Job 能完成复用中的 Context，同时继续受 owner、媒体、Pipeline
  和 version CAS 约束。
- 使用真实浏览器提交两条中文教学视频，确认超过 120 秒的素材完整进入 READY，页面
  无 Console error/warning，并从 `model_call` 追溯各操作原始计量。
