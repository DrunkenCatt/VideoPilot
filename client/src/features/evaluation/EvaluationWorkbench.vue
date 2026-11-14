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
const agentPolicyVersion = ref('agent-policy-v4-window')
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
    class="evaluation-shell"
    aria-labelledby="evaluation-title"
  >
    <header class="evaluation-heading">
      <div>
        <p class="section-index">
          M5 / EVALUATION
        </p><h1 id="evaluation-title">
          评测工作台
        </h1>
      </div>
      <button
        class="text-button"
        :disabled="busy"
        @click="refresh"
      >
        {{ busy ? '读取中…' : '刷新' }}
      </button>
    </header>
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
          <div class="metric-grid">
            <div
              v-for="metric in report.metrics"
              :key="metric.name"
            >
              <span>{{ metric.name }}</span><strong>{{ metric.value === null ? '—' : metric.value.toFixed(3) }}</strong><small>{{ metric.numerator }} / {{ metric.denominator }}</small>
            </div>
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
        <article class="evaluation-card compare-card">
          <p class="section-index">
            COMPARE
          </p><h2>运行差值</h2>
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
.evaluation-shell{width:min(1220px,calc(100% - 48px));margin:0 auto;padding:42px 0 64px}.evaluation-heading,.card-title{display:flex;align-items:center;justify-content:space-between;gap:20px}.evaluation-heading h1{margin:4px 0 0;font-size:clamp(2rem,5vw,4.6rem);letter-spacing:-.055em}.dataset-rail{display:flex;gap:10px;overflow-x:auto;padding:18px 0 22px}.dataset-rail button{min-width:210px;text-align:left;background:#f4f0e6;color:#1c2823;border:1px solid #c9c0ad;padding:14px}.dataset-rail button.active{background:#173d30;color:#fff;border-color:#173d30}.dataset-rail span,.dataset-rail small{display:block;margin-top:5px}.evaluation-grid{display:grid;grid-template-columns:repeat(12,minmax(0,1fr));gap:14px}.evaluation-card{min-width:0;background:#f7f3e9;border:1px solid #cec5b2;padding:22px}.dataset-card,.runs-card,.trace-card{grid-column:span 5}.run-card,.report-card,.compare-card{grid-column:span 7}.evaluation-card h2{margin:4px 0 18px;font-size:1.45rem}.evaluation-card dl{margin:0}.evaluation-card dl div{display:grid;grid-template-columns:120px minmax(0,1fr);gap:12px;padding:8px 0;border-top:1px solid #ddd4c2}.evaluation-card dt{color:#647069}.evaluation-card dd{margin:0;overflow-wrap:anywhere}.mono{font-family:"IBM Plex Mono","Courier New",monospace;font-size:.75rem}.version-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.version-grid label,.compare-card label{display:grid;gap:5px}.version-grid input,.compare-card select{width:100%;box-sizing:border-box}.guard-note{min-height:2.8em;color:#625f55}.run-row{display:grid;width:100%;gap:5px;margin:8px 0;text-align:left}.run-row span:nth-child(2){font-variant-numeric:tabular-nums}.fingerprint{overflow-wrap:anywhere}details{border-top:1px solid #ddd4c2;padding:10px 0}summary{display:flex;justify-content:space-between;gap:12px;cursor:pointer}.metric-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px}.metric-grid div{display:grid;background:#e7eadf;padding:12px}.metric-grid strong{font-size:1.5rem}.comparison{margin-top:16px}.comparison p{display:flex;justify-content:space-between;border-top:1px solid #ddd4c2;padding:8px 0;margin:0}.evaluation-empty{border:1px dashed #928c7d;padding:36px}.section-index{font-family:"IBM Plex Mono","Courier New",monospace;letter-spacing:.12em;font-size:.72rem}
.dataset-rail{min-width:0;max-width:100%}
.comparison p>*{min-width:0;overflow-wrap:anywhere}.comparison strong{text-align:right}
.candidate-controls{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:14px}.candidate-controls label{display:grid;gap:5px}.candidate-controls input,.candidate-controls select{width:100%;box-sizing:border-box}
@media(max-width:900px){.evaluation-card{grid-column:1/-1}.evaluation-shell{width:min(100% - 32px,760px)}.metric-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:480px){.evaluation-shell{width:calc(100% - 24px);padding-top:24px}.evaluation-heading{align-items:flex-start}.version-grid,.metric-grid{grid-template-columns:1fr}.evaluation-card{padding:16px}.evaluation-card dl div{grid-template-columns:1fr}.dataset-rail button{min-width:180px}}
@media(max-width:480px){.candidate-controls{grid-template-columns:1fr}}
</style>
