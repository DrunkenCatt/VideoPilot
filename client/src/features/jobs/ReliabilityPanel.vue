<script setup lang="ts">
import type { ReliabilitySummary } from '../../api/contracts'

defineProps<{
  summary: ReliabilitySummary | null
  loading: boolean
  error: string
}>()
defineEmits<{ refresh: [] }>()

function duration(milliseconds: number): string {
  if (milliseconds < 1_000) return `${milliseconds} ms`
  return `${(milliseconds / 1_000).toFixed(1)} s`
}

function cost(value: number | null, currency: string | null): string {
  if (value === null) return '价格未知'
  return `${currency ?? '—'} ${value.toFixed(4)}`
}
</script>

<template>
  <section
    class="operations-panel"
    aria-labelledby="operations-title"
    data-test="reliability-operations"
  >
    <div class="operations-heading">
      <div>
        <p class="section-index">
          M4 · RELIABILITY WINDOW
        </p>
        <h2 id="operations-title">
          可靠性
        </h2>
        <p>过去 24 小时的持久化任务与队列事实，只读且按当前账号聚合。</p>
      </div>
      <button
        type="button"
        :disabled="loading"
        @click="$emit('refresh')"
      >
        {{ loading ? '读取中…' : '刷新' }}
      </button>
    </div>

    <p
      v-if="error"
      class="operations-warning"
      role="status"
    >
      {{ error }}
    </p>
    <p
      v-if="loading && !summary"
      class="operations-empty"
      aria-live="polite"
    >
      正在读取可靠性摘要…
    </p>
    <template v-else-if="summary">
      <p
        v-if="summary.degradedSources.length"
        class="operations-warning"
        role="status"
      >
        部分数据源不可用：{{ summary.degradedSources.join(' / ') }}。不可用值不会显示为 0。
      </p>
      <div class="operations-totals">
        <article>
          <span>OUTBOX 待发布</span>
          <strong>{{ summary.pendingOutbox ?? '不可用' }}</strong>
        </article>
        <article>
          <span>QUEUE 积压</span>
          <strong>{{ summary.queueBacklog ?? '不可用' }}</strong>
        </article>
        <article>
          <span>失败分类</span>
          <strong>{{ summary.failureCounts.reduce((total, item) => total + item.count, 0) }}</strong>
        </article>
        <article>
          <span>供应商调用</span>
          <strong>{{ summary.providerCalls.reduce((total, item) => total + item.totalCalls, 0) }}</strong>
        </article>
      </div>

      <div class="operations-columns">
        <section>
          <h3>失败与慢阶段</h3>
          <p
            v-if="!summary.failureCounts.length && !summary.stageDurations.length"
            class="operations-empty"
          >
            窗口内暂无失败或阶段样本。
          </p>
          <dl
            v-else
            class="operations-list"
          >
            <template
              v-for="failure in summary.failureCounts"
              :key="failure.code"
            >
              <dt>{{ failure.code }}</dt><dd>{{ failure.count }}</dd>
            </template>
            <template
              v-for="stage in summary.stageDurations"
              :key="stage.stage"
            >
              <dt>{{ stage.stage }}</dt><dd>P50 {{ duration(stage.p50Ms) }} · P95 {{ duration(stage.p95Ms) }}</dd>
            </template>
          </dl>
        </section>
        <section>
          <h3>供应商与成本</h3>
          <p
            v-if="!summary.providerCalls.length"
            class="operations-empty"
          >
            窗口内暂无模型调用。
          </p>
          <article
            v-for="provider in summary.providerCalls"
            :key="provider.provider"
            class="provider-row"
          >
            <strong>{{ provider.provider }}</strong>
            <span>{{ provider.totalCalls }} 次 / {{ provider.failedCalls }} 失败</span>
            <small>输入 {{ provider.inputUnits }} · 输出 {{ provider.outputUnits }} · {{ cost(provider.estimatedCost, provider.currency) }}</small>
          </article>
        </section>
      </div>
      <p class="operations-freshness">
        数据生成于 {{ new Date(summary.generatedAt).toLocaleString('zh-CN') }} ·
        窗口截止 {{ new Date(summary.windowEnd).toLocaleString('zh-CN') }}
      </p>
    </template>
  </section>
</template>
