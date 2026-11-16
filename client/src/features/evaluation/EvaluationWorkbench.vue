<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import type {
  EvaluationCaseResult,
  EvaluationDataset,
  EvaluationExecutionVersions,
  EvaluationReport,
  EvaluationPromotionDecision,
  EvaluationRun,
  Session,
} from '../../api/contracts'
import { ApiClientError } from '../../api/session'
import ProductPageHeader from '../../components/ProductPageHeader.vue'
import {
  compareEvaluationReports,
  createEvaluationApi,
  evaluationExport,
  type EvaluationApi,
} from '../../api/evaluation'

const props = defineProps<{ session: Session, api?: EvaluationApi }>()
const emit = defineEmits<{ unauthenticated: [] }>()
const api = props.api ?? createEvaluationApi()
const datasets = ref<EvaluationDataset[]>([])
const selected = ref<EvaluationDataset | null>(null)
const runs = ref<EvaluationRun[]>([])
const activeRun = ref<EvaluationRun | null>(null)
const results = ref<EvaluationCaseResult[]>([])
const report = ref<EvaluationReport | null>(null)
const comparison = ref<ReturnType<typeof compareEvaluationReports> | null>(null)
const promotion = ref<EvaluationPromotionDecision | null>(null)
const executionMode = ref<'BASELINE' | 'AGENT'>('BASELINE')
const agentPolicyVersion = ref('agent-policy-v5-partial')
const compareBase = ref('')
const compareCandidate = ref('')
const loading = ref(true)
const busy = ref(false)
const error = ref('')
let datasetRevision = 0

const versionFields: { key: keyof EvaluationExecutionVersions, label: string }[] = [
  { key: 'codeCommit', label: 'Commit' }, { key: 'pipelineVersion', label: 'Pipeline' },
  { key: 'promptVersion', label: 'Prompt' }, { key: 'modelVersion', label: 'Model' },
  { key: 'embeddingVersion', label: 'Embedding' }, { key: 'chunkVersion', label: 'Chunk' },
  { key: 'retrievalFormulaVersion', label: 'Retrieval formula' },
  { key: 'evidenceGateVersion', label: 'Evidence gate' }, { key: 'limitsVersion', label: 'Limits' },
  { key: 'configurationVersion', label: 'Evaluation config' },
]
const versions = reactive<EvaluationExecutionVersions>(Object.fromEntries(
  versionFields.map((field) => [field.key, '']),
) as unknown as EvaluationExecutionVersions)

const runGuard = computed(() => {
  if (!selected.value) return '请选择数据集。'
  if (selected.value.approvedCaseCount !== selected.value.totalCaseCount) return '全部 case 批准后才能运行。'
  if (selected.value.split === 'TEST' && selected.value.status !== 'FROZEN') {
    return 'TEST 数据集必须冻结并全部批准，双人复核由服务端再次校验。'
  }
  if (versionFields.some((field) => !versions[field.key].trim())) return '填写全部冻结版本后才能运行。'
  if (executionMode.value === 'AGENT' && !agentPolicyVersion.value.trim()) return 'Agent 候选必须冻结 policy version。'
  return ''
})
const completedRuns = computed(() => runs.value.filter((run) => run.status === 'COMPLETED'))
const completedBaselines = computed(() => completedRuns.value.filter((run) => run.executionMode === 'BASELINE'))
const completedCandidates = computed(() => completedRuns.value.filter((run) => run.executionMode === 'AGENT'))

function handle(errorValue: unknown) {
  error.value = errorValue instanceof ApiClientError && errorValue.code === 'AUTHENTICATION_REQUIRED'
    ? '会话已过期，请重新登录。' : '评测数据暂时无法读取，请刷新重试。'
  if (errorValue instanceof ApiClientError && errorValue.code === 'AUTHENTICATION_REQUIRED') emit('unauthenticated')
}

async function loadRun(run: EvaluationRun) {
  activeRun.value = run
  const revision = datasetRevision
  try {
    const [trace, latestReport] = await Promise.all([api.results(run.runId), api.report(run.runId)])
    if (revision !== datasetRevision || activeRun.value?.runId !== run.runId) return
    results.value = trace
    report.value = latestReport
    if (versionFields.every((field) => !versions[field.key])) Object.assign(versions, latestReport.versions)
  } catch (errorValue) {
    if (revision === datasetRevision) handle(errorValue)
  }
}

async function chooseDataset(dataset: EvaluationDataset) {
  const revision = ++datasetRevision
  selected.value = dataset
  runs.value = []
  activeRun.value = null
  results.value = []
  report.value = null
  comparison.value = null
  promotion.value = null
  error.value = ''
  try {
    const values = await api.listRuns(dataset.datasetId)
    if (revision !== datasetRevision) return
    runs.value = values
    if (values[0]) await loadRun(values[0])
  } catch (errorValue) {
    if (revision === datasetRevision) handle(errorValue)
  }
}

async function refresh() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    if (selected.value) {
      await chooseDataset(selected.value)
    } else {
      datasets.value = await api.listDatasets()
      if (datasets.value[0]) await chooseDataset(datasets.value[0])
    }
  } catch (errorValue) {
    handle(errorValue)
  } finally {
    busy.value = false
  }
}

async function startRun() {
  if (!selected.value || runGuard.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const repetitions = (selected.value.answerCaseCount > 0 ? 3 : 2) as 2 | 3
    const created = await api.createRuns(selected.value.datasetId, {
      baselineType: executionMode.value === 'AGENT' ? 'AGENT_CANDIDATE' : 'BASELINE_V1',
      repetitions, versions: { ...versions }, executionMode: executionMode.value,
      agentPolicyVersion: executionMode.value === 'AGENT' ? agentPolicyVersion.value.trim() : null,
    }, globalThis.crypto.randomUUID(), props.session.csrfToken)
    runs.value = [...created, ...runs.value.filter((run) => !created.some((value) => value.runId === run.runId))]
    if (created[0]) await loadRun(created[0])
  } catch (errorValue) { handle(errorValue) } finally { busy.value = false }
}

async function compare() {
  if (!compareBase.value || !compareCandidate.value || compareBase.value === compareCandidate.value) return
  busy.value = true
  try {
    const [base, candidate, decision] = await Promise.all([
      api.report(compareBase.value), api.report(compareCandidate.value),
      api.comparison(compareBase.value, compareCandidate.value),
    ])
    comparison.value = compareEvaluationReports(base, candidate)
    promotion.value = decision
  } catch (errorValue) { handle(errorValue) } finally { busy.value = false }
}

function download() {
  if (!report.value) return
  const blob = new globalThis.Blob([JSON.stringify(evaluationExport(report.value, results.value), null, 2)], { type: 'application/json' })
  const link = globalThis.document.createElement('a')
  link.href = globalThis.URL.createObjectURL(blob)
  link.download = `videopilot-evaluation-${report.value.runId}.json`
  link.click()
  globalThis.URL.revokeObjectURL(link.href)
}

onMounted(async () => {
  try {
    datasets.value = await api.listDatasets()
    loading.value = false
    if (datasets.value[0]) await chooseDataset(datasets.value[0])
  } catch (errorValue) { handle(errorValue) } finally { loading.value = false }
})
</script>

<template>
  <main
    class="product-page evaluation-product-page"
  >
    <ProductPageHeader
      eyebrow="EVALUATION"
      title="评测中心"
      description="以冻结数据集比较检索、回答质量、延迟与成本，并由 Java 硬门决定是否可晋级。"
      :status="selected ? `${selected.dataVersion} · ${selected.status}` : '等待数据集'"
    >
      <template #actions>
        <button
          class="evaluation-refresh"
          :disabled="busy"
          @click="refresh"
        >
          {{ busy ? '读取中…' : '刷新数据' }}
        </button>
      </template>
    </ProductPageHeader>
    <p
      v-if="error"
      role="alert"
      class="form-error"
    >
      {{ error }}
    </p>
    <p
      v-if="loading"
      aria-live="polite"
    >
      正在读取评测真源…
    </p>
    <section
      v-else-if="datasets.length === 0"
      class="evaluation-empty"
    >
      <h2>尚无数据集</h2><p>先通过受控导入建立 dev 数据集，再回到这里发起基线运行。</p>
    </section>
    <template v-else>
      <nav
        class="dataset-rail"
        aria-label="评测数据集"
      >
        <button
          v-for="(dataset, index) in datasets"
          :key="dataset.datasetId"
          :data-test="`dataset-${index}`"
          :class="{ active: selected?.datasetId === dataset.datasetId }"
          @click="chooseDataset(dataset)"
        >
          <strong>{{ dataset.dataVersion }}</strong><span>{{ dataset.split }} · {{ dataset.status }}</span>
          <small>{{ dataset.approvedCaseCount }}/{{ dataset.totalCaseCount }} approved</small>
        </button>
      </nav>
      <section
        v-if="selected"
        class="evaluation-grid"
      >
        <section
          class="evaluation-metric-band"
          data-test="evaluation-metric-band"
          aria-label="当前评测关键指标"
        >
          <template v-if="report">
            <div
              v-for="metric in report.metrics"
              :key="metric.name"
              class="evaluation-metric"
            >
              <span>{{ metric.name }}</span><strong>{{ metric.value === null ? '—' : metric.value.toFixed(3) }}</strong><small>{{ metric.numerator }} / {{ metric.denominator }}</small>
            </div>
          </template>
          <p v-else>
            选择已完成运行后显示真实报告指标；运行中不会预填示例数字。
          </p>
        </section>
        <article class="evaluation-card dataset-card">
          <p class="section-index">
            DATASET
          </p><h2>{{ selected.dataVersion }}</h2>
          <dl>
            <div>
              <dt>Fingerprint</dt><dd class="mono">
                {{ selected.datasetFingerprint }}
              </dd>
            </div>
            <div><dt>Split / 状态</dt><dd>{{ selected.split }} / {{ selected.status }}</dd></div>
            <div><dt>复核</dt><dd>{{ selected.approvedCaseCount }} / {{ selected.totalCaseCount }} approved</dd></div>
            <div><dt>题目</dt><dd>{{ selected.retrievalCaseCount }} retrieval · {{ selected.answerCaseCount }} answer</dd></div>
          </dl>
        </article>
        <article class="evaluation-card run-card">
          <p class="section-index">
            CANDIDATE
          </p><h2>冻结执行身份</h2>
          <div class="version-grid">
            <label
              v-for="field in versionFields"
              :key="field.key"
            >
              <span>{{ field.label }}</span><input
                v-model.trim="versions[field.key]"
                :name="field.key"
                autocomplete="off"
              >
            </label>
          </div>
          <div class="candidate-controls">
            <label>执行模式<select v-model="executionMode"><option value="BASELINE">非 Agent 基线</option><option value="AGENT">Agent 候选</option></select></label>
            <label v-if="executionMode === 'AGENT'">Policy version<input
              v-model.trim="agentPolicyVersion"
              name="agentPolicyVersion"
              autocomplete="off"
            ></label>
          </div>
          <p class="guard-note">
            {{ runGuard || `将运行 ${selected.answerCaseCount > 0 ? 3 : 2} 次；服务端再次执行全部硬门。` }}
          </p>
          <button
            data-test="start-run"
            :disabled="Boolean(runGuard) || busy"
            @click="startRun"
          >
            发起 dev 运行
          </button>
        </article>
        <article class="evaluation-card runs-card">
          <p class="section-index">
            RUNS
          </p><h2>运行与恢复</h2>
          <p v-if="runs.length === 0">
            尚无运行记录。
          </p>
          <button
            v-for="run in runs"
            v-else
            :key="run.runId"
            class="run-row"
            @click="loadRun(run)"
          >
            <span><strong>{{ run.status }}</strong> · repetition {{ run.repetitionIndex }}/{{ run.repetitionCount }}</span>
            <span>{{ run.completedCaseCount }} / {{ run.totalCaseCount }}</span><small class="mono">{{ run.runId }}</small>
          </button>
        </article>
        <article
          v-if="activeRun"
          class="evaluation-card trace-card"
        >
          <p class="section-index">
            TRACE
          </p><h2>逐题去敏 Trace</h2>
          <p class="mono fingerprint">
            {{ activeRun.executionFingerprint }}
          </p>
          <details
            v-for="item in results"
            :key="item.caseId"
          >
            <summary><span>{{ item.caseId }}</span><strong>{{ item.outcome }}</strong></summary>
            <dl>
              <div><dt>Rank</dt><dd>{{ item.rank ?? '—' }}</dd></div><div><dt>Claim / 引用</dt><dd>{{ item.claimCount }} / {{ item.citationCount }}</dd></div>
              <div><dt>支持 / 定位</dt><dd>{{ item.supportedClaimCount }} / {{ item.localizedCitationCount }}</dd></div>
              <div><dt>延迟</dt><dd>{{ item.latencyMs }} ms</dd></div><div><dt>错误</dt><dd>{{ item.errorCode ?? '—' }}</dd></div>
            </dl>
          </details><p v-if="results.length === 0">
            当前尚无逐题结果，刷新可恢复最新进度。
          </p>
        </article>
        <article
          v-if="report"
          class="evaluation-card report-card"
          data-test="evaluation-report-panel"
        >
          <p class="section-index">
            REPORT
          </p><div class="card-title">
            <h2>最近报告</h2><button
              class="text-button"
              @click="download"
            >
              导出 JSON
            </button>
          </div>
          <dl>
            <div>
              <dt>模式</dt><dd>
                {{ report.executionMode }}<template v-if="report.agentPolicyVersion">
                  / {{ report.agentPolicyVersion }}
                </template>
              </dd>
            </div>
            <div><dt>P95 延迟</dt><dd>{{ report.operations.p95LatencyMs }} ms</dd></div><div><dt>Token</dt><dd>{{ report.usage.inputUnits }} in / {{ report.usage.outputUnits }} out</dd></div>
            <div><dt>调用</dt><dd>{{ report.usage.totalCalls }}</dd></div><div><dt>平均成本</dt><dd>{{ report.operations.averageCost ?? 'UNKNOWN' }}</dd></div>
            <div><dt>未知价格调用</dt><dd>{{ report.operations.unavailablePriceCalls }}</dd></div>
          </dl>
          <details v-if="Object.keys(report.operations.pathDistribution).length">
            <summary>Agent 路径分布</summary>
            <dl>
              <div
                v-for="(count, path) in report.operations.pathDistribution"
                :key="path"
              >
                <dt>{{ path }}</dt><dd>{{ count }}</dd>
              </div>
            </dl>
          </details>
          <details class="version-trace">
            <summary>全部执行版本</summary>
            <dl>
              <div
                v-for="field in versionFields"
                :key="field.key"
              >
                <dt>{{ field.label }}</dt><dd class="mono">
                  {{ report.versions[field.key] }}
                </dd>
              </div>
            </dl>
          </details>
        </article>
        <article
          class="evaluation-card compare-card"
          data-test="evaluation-gate-panel"
        >
          <p class="section-index">
            RELEASE GATES
          </p><h2>发布硬门</h2>
          <p v-if="completedBaselines.length === 0 || completedCandidates.length === 0">
            需要同一冻结数据集上的已完成基线与 Agent 候选。
          </p>
          <template v-else>
            <label>基线<select v-model="compareBase"><option value="">选择运行</option><option
              v-for="run in completedBaselines"
              :key="run.runId"
              :value="run.runId"
            >{{ run.runId }}</option></select></label>
            <label>候选<select v-model="compareCandidate"><option value="">选择运行</option><option
              v-for="run in completedCandidates"
              :key="run.runId"
              :value="run.runId"
            >{{ run.runId }}</option></select></label>
            <button
              :disabled="busy || !compareBase || !compareCandidate || compareBase === compareCandidate"
              @click="compare"
            >
              只计算差值
            </button>
          </template>
          <div
            v-if="comparison"
            class="comparison"
          >
            <p
              v-for="metric in comparison.metricDeltas"
              :key="metric.name"
            >
              <span>{{ metric.name }}</span><strong>{{ metric.delta === null ? 'UNKNOWN' : metric.delta.toFixed(3) }}</strong>
            </p>
            <p><span>latencyMs</span><strong>{{ comparison.usageDeltas.latencyMs }}</strong></p><p><span>tokens in/out</span><strong>{{ comparison.usageDeltas.inputUnits }} / {{ comparison.usageDeltas.outputUnits }}</strong></p>
            <p><span>calls</span><strong>{{ comparison.usageDeltas.totalCalls }}</strong></p><p><span>cost</span><strong>{{ comparison.usageDeltas.estimatedCost ?? 'UNKNOWN' }}</strong></p>
            <template v-if="promotion">
              <p><span>Java 晋级裁决</span><strong>{{ promotion.promotionEligible ? 'ELIGIBLE' : 'KEEP_DISABLED' }}</strong></p>
              <p><span>原因</span><strong>{{ promotion.reason }}</strong></p>
              <p><span>硬门通过率差</span><strong>{{ promotion.hardGatePassRateDelta.toFixed(3) }}</strong></p>
              <p><span>规则 1 / 规则 2</span><strong>{{ promotion.ruleOneMet }} / {{ promotion.ruleTwoMet }}</strong></p>
              <p><span>M5 原门 / 成本已知</span><strong>{{ promotion.m5GatesMet }} / {{ promotion.costKnown }}</strong></p>
            </template>
          </div>
        </article>
      </section>
    </template>
  </main>
</template>

<style scoped>
.evaluation-product-page { min-width: 0; }
.evaluation-refresh { min-height: 2.5rem; padding: 0 0.9rem; border: 1px solid var(--color-border); border-radius: var(--radius-control); color: var(--color-text); background: var(--color-surface); cursor: pointer; }
.dataset-rail { display: flex; min-width: 0; max-width: 100%; gap: 0.5rem; overflow-x: auto; padding: 0 0 1rem; }
.dataset-rail button { min-width: 12rem; padding: 0.75rem; border: 1px solid var(--color-border); border-radius: 0.65rem; color: var(--color-text); background: var(--color-surface); text-align: left; cursor: pointer; }
.dataset-rail button.active { border-color: #b9cff1; background: var(--color-surface-selected); }
.dataset-rail span, .dataset-rail small { display: block; margin-top: 0.25rem; color: var(--color-text-muted); font-size: 0.62rem; }
.evaluation-grid { display: grid; grid-template-columns: minmax(0, 1.75fr) minmax(18rem, 0.9fr); grid-template-areas: 'metrics metrics' 'report gate' 'runs gate' 'trace dataset' 'trace setup'; gap: 1rem; }
.evaluation-metric-band { display: grid; grid-area: metrics; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 0.75rem; }
.evaluation-metric-band > p { grid-column: 1 / -1; margin: 0; padding: 1.25rem; border: 1px solid var(--color-border); border-radius: var(--radius-card); color: var(--color-text-muted); background: var(--color-surface); }
.evaluation-metric { display: grid; gap: 0.4rem; padding: 1rem; border: 1px solid var(--color-border); border-radius: var(--radius-card); background: var(--color-surface); box-shadow: var(--shadow-card); }
.evaluation-metric span, .evaluation-metric small { color: var(--color-text-muted); font-size: 0.62rem; }
.evaluation-metric strong { font-size: 1.55rem; }
.evaluation-card { min-width: 0; padding: 1rem; border: 1px solid var(--color-border); border-radius: var(--radius-card); background: var(--color-surface); box-shadow: var(--shadow-card); }
.dataset-card { grid-area: dataset; }
.run-card { grid-area: setup; }
.runs-card { grid-area: runs; }
.trace-card { grid-area: trace; }
.report-card { grid-area: report; }
.compare-card { grid-area: gate; align-self: start; }
.evaluation-card h2 { margin: 0.2rem 0 1rem; font-size: 1rem; }
.section-index { margin: 0; color: var(--color-primary); font-size: 0.58rem; font-weight: 750; letter-spacing: 0.08em; }
.evaluation-card dl { margin: 0; }
.evaluation-card dl div { display: grid; grid-template-columns: 7rem minmax(0, 1fr); gap: 0.75rem; padding: 0.55rem 0; border-top: 1px solid var(--color-border); }
.evaluation-card dt { color: var(--color-text-muted); }
.evaluation-card dd { margin: 0; overflow-wrap: anywhere; }
.mono { font-family: 'IBM Plex Mono', 'Cascadia Mono', monospace; font-size: 0.68rem; }
.card-title { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
.version-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0.5rem; }
.version-grid label, .compare-card label, .candidate-controls label { display: grid; gap: 0.25rem; color: var(--color-text-muted); font-size: 0.64rem; }
.version-grid input, .compare-card select, .candidate-controls input, .candidate-controls select { width: 100%; min-height: 2.35rem; border: 1px solid var(--color-border); border-radius: 0.45rem; background: #fff; }
.candidate-controls { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0.5rem; margin-top: 0.75rem; }
.guard-note { min-height: 2.8em; color: var(--color-text-muted); font-size: 0.68rem; }
.run-row { display: grid; width: 100%; grid-template-columns: minmax(0, 1fr) auto; gap: 0.35rem 1rem; margin: 0.5rem 0; padding: 0.75rem; border: 1px solid var(--color-border); border-radius: 0.6rem; color: var(--color-text); background: #fff; text-align: left; cursor: pointer; }
.run-row small { grid-column: 1 / -1; color: var(--color-text-muted); }
.fingerprint { overflow-wrap: anywhere; }
details { padding: 0.65rem 0; border-top: 1px solid var(--color-border); }
summary { display: flex; justify-content: space-between; gap: 0.75rem; cursor: pointer; }
.comparison { margin-top: 1rem; }
.comparison p { display: flex; justify-content: space-between; gap: 0.75rem; margin: 0; padding: 0.55rem 0; border-top: 1px solid var(--color-border); }
.comparison p > * { min-width: 0; overflow-wrap: anywhere; }
.comparison strong { text-align: right; }
.evaluation-empty { padding: 2rem; border: 1px dashed var(--color-border-strong); border-radius: var(--radius-card); background: var(--color-surface); }
.text-button { border: 0; color: var(--color-primary); background: transparent; cursor: pointer; }

@media (max-width: 64rem) {
  .evaluation-grid { grid-template-columns: 1fr; grid-template-areas: 'metrics' 'report' 'gate' 'runs' 'trace' 'dataset' 'setup'; }
  .evaluation-metric-band { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 36rem) {
  .evaluation-metric-band, .version-grid, .candidate-controls { grid-template-columns: 1fr; }
  .evaluation-card { padding: 0.8rem; }
  .evaluation-card dl div { grid-template-columns: 1fr; gap: 0.25rem; }
  .dataset-rail button { min-width: 10rem; }
}
</style>
