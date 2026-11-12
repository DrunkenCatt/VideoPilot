<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'

import { createAgentsApi, type AgentsApi } from '../../api/agents'
import { createAnswersApi, type AnswersApi } from '../../api/answers'
import type {
  AgentRun,
  AgentRunId,
  AnalysisJob,
  AnswerResponse,
  CitedEvidence,
  DegradedChannel,
  GoalAnalysisResult,
  RefusalReason,
} from '../../api/contracts'
import { ApiClientError } from '../../api/session'

const props = withDefaults(defineProps<{
  job: AnalysisJob
  csrfToken: string
  api?: AnswersApi
  agentApi?: AgentsApi
  agentAvailable?: boolean
  goalAnalysisAvailable?: boolean
  agentPollMs?: number
  timeoutMs?: number
}>(), {
  api: () => createAnswersApi(),
  agentApi: () => createAgentsApi(),
  agentAvailable: import.meta.env.VITE_AGENT_ENABLED === 'true',
  goalAnalysisAvailable: import.meta.env.VITE_GOAL_ANALYSIS_ENABLED === 'true',
  agentPollMs: 1_000,
  timeoutMs: 35_000,
})
const emit = defineEmits<{
  cite: [citation: CitedEvidence]
  unauthenticated: []
}>()

const answers = ref<AnswerResponse[]>([])
const question = ref('')
const loading = ref(false)
const submitting = ref(false)
const useAgent = ref(false)
const agentRun = ref<AgentRun | null>(null)
const goalAnalysis = ref<GoalAnalysisResult | null>(null)
const errorMessage = ref('')
let revision = 0
let idempotencyKey: string | null = null
let requestController: globalThis.AbortController | null = null
let pollTimer: ReturnType<typeof globalThis.setTimeout> | null = null
let goalPollTimer: ReturnType<typeof globalThis.setTimeout> | null = null

const terminalAgentStatuses = new Set<AgentRun['status']>(['COMPLETED', 'REFUSED', 'FAILED'])
const terminalGoalStates = new Set<GoalAnalysisResult['state']>(['DISABLED', 'ANSWERED', 'REFUSED', 'FAILED'])
const agentRunQueryParameter = 'agentRun'

const terminationLabels: Record<string, string> = {
  COMPLETED: '已完成并通过确定性证据门。',
  NEEDS_CLARIFICATION: '需要补充更具体的问题后再运行。',
  NO_SUFFICIENT_EVIDENCE: '视频证据不足，Agent 已稳定拒答。',
  OWNER_OR_JOB_REJECTED: '当前账号无权访问该任务，或任务尚未就绪。',
  STEP_BUDGET_EXHAUSTED: '步骤预算已用尽。',
  DEADLINE_EXCEEDED: '运行时间预算已用尽。',
  INPUT_TOKEN_BUDGET_EXHAUSTED: '输入 Token 预算已用尽。',
  OUTPUT_TOKEN_BUDGET_EXHAUSTED: '输出 Token 预算已用尽。',
  COST_BUDGET_EXHAUSTED: '已知费用预算已用尽。',
  UNKNOWN_COST: '存在无法确定费用的调用，已停止继续执行。',
  ANSWER_ATTEMPT_BUDGET_EXHAUSTED: '答案修复次数已用尽。',
  UNKNOWN_OUTCOME: '上一次高成本调用结果未知，未自动重放。',
}

const refusalLabels: Record<RefusalReason, string> = {
  NO_SUFFICIENT_EVIDENCE: '视频证据不足，无法可靠回答。',
  EVIDENCE_BELOW_THRESHOLD: '候选证据相关度过低，已拒绝生成答案。',
  CITATION_VALIDATION_FAILED: '候选答案未通过引用核验，未发布事实结论。',
  QUESTION_OUT_OF_SCOPE: '该问题超出当前视频内容范围。',
}

const degradationLabels: Record<DegradedChannel, string> = {
  VECTOR_STORE: '向量库不可用',
  SEMANTIC: '语义召回不可用',
  OCR: 'OCR 证据不完整',
}

function formatTime(milliseconds: number): string {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1_000))
  const hours = Math.floor(totalSeconds / 3_600)
  const minutes = Math.floor((totalSeconds % 3_600) / 60)
  const seconds = totalSeconds % 60
  const clock = `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
  return hours > 0 ? `${String(hours).padStart(2, '0')}:${clock}` : clock
}

function safeMessage(error: unknown): string {
  if (error instanceof globalThis.DOMException && error.name === 'AbortError') return '请求超时，尚未获得可发布答案。你可以使用原问题重试。'
  if (!(error instanceof ApiClientError)) return '无法连接本地问答服务，请确认后端已启动。'
  if (error.code === 'AUTHENTICATION_REQUIRED') return '会话已过期，请重新登录。'
  if (error.code === 'RESOURCE_NOT_FOUND') return '任务不存在，或当前账号无权访问。'
  if (error.code === 'CONFLICT') return '该问题仍在处理中，请稍后刷新已发布结果。'
  if (error.code === 'IDEMPOTENCY_KEY_REUSED') return '问题内容已经变更，请重新提交。'
  if (error.code === 'DEPENDENCY_UNAVAILABLE') return '问答依赖暂时不可用；这不是证据不足，也不会发布为拒答。'
  return error.retryable ? '问答服务暂时不可用，请稍后重试。' : '问题未提交，请检查输入。'
}

function handleError(error: unknown) {
  errorMessage.value = safeMessage(error)
  if (error instanceof ApiClientError && error.code === 'AUTHENTICATION_REQUIRED') emit('unauthenticated')
}

function publish(answer: AnswerResponse) {
  const existing = answers.value.findIndex((candidate) => candidate.answerId === answer.answerId)
  if (existing >= 0) answers.value.splice(existing, 1, answer)
  else answers.value.unshift(answer)
}

function withoutGoalAnswer(values: AnswerResponse[]): AnswerResponse[] {
  const goalAnswerId = goalAnalysis.value?.answer?.answerId
  return goalAnswerId ? values.filter((answer) => answer.answerId !== goalAnswerId) : values
}

function waitForPoll(): Promise<void> {
  return new Promise((resolve) => {
    pollTimer = globalThis.setTimeout(resolve, props.agentPollMs)
  })
}

function waitForGoalPoll(): Promise<void> {
  return new Promise((resolve) => {
    goalPollTimer = globalThis.setTimeout(resolve, props.agentPollMs)
  })
}

function rememberedAgentRunId(): AgentRunId | null {
  const value = new globalThis.URL(globalThis.location.href).searchParams.get(agentRunQueryParameter)
  return value ? value as AgentRunId : null
}

function rememberAgentRun(run: AgentRun) {
  agentRun.value = run
  const url = new globalThis.URL(globalThis.location.href)
  url.searchParams.set(agentRunQueryParameter, run.runId)
  globalThis.history.replaceState(globalThis.history.state, '', `${url.pathname}${url.search}${url.hash}`)
}

function forgetAgentRun() {
  const url = new globalThis.URL(globalThis.location.href)
  url.searchParams.delete(agentRunQueryParameter)
  globalThis.history.replaceState(globalThis.history.state, '', `${url.pathname}${url.search}${url.hash}`)
}

async function restoreAnswers(currentRevision: number, jobId: AnalysisJob['jobId']) {
  const page = await props.api.list(jobId)
  if (currentRevision === revision && props.job.jobId === jobId) answers.value = withoutGoalAnswer(page.data)
}

async function restoreGoalAnalysis(currentRevision: number, jobId: AnalysisJob['jobId']) {
  if (!props.agentAvailable || !props.goalAnalysisAvailable) return
  let current = await props.agentApi.getGoalAnalysisResult(jobId)
  if (currentRevision !== revision || props.job.jobId !== jobId) return
  goalAnalysis.value = current
  answers.value = withoutGoalAnswer(answers.value)
  while (!terminalGoalStates.has(current.state)) {
    await waitForGoalPoll()
    if (currentRevision !== revision || props.job.jobId !== jobId) return
    current = await props.agentApi.getGoalAnalysisResult(jobId)
    if (currentRevision !== revision || props.job.jobId !== jobId) return
    goalAnalysis.value = current
    answers.value = withoutGoalAnswer(answers.value)
  }
}

async function pollAgent(initial: AgentRun, currentRevision: number, jobId: AnalysisJob['jobId']) {
  if (currentRevision !== revision || props.job.jobId !== jobId) return
  let current = initial
  rememberAgentRun(current)
  while (!terminalAgentStatuses.has(current.status)) {
    if (currentRevision !== revision || props.job.jobId !== jobId) return
    await waitForPoll()
    if (currentRevision !== revision || props.job.jobId !== jobId) return
    current = await props.agentApi.get(current.runId)
    if (currentRevision !== revision || props.job.jobId !== jobId) return
    rememberAgentRun(current)
  }
  if (current.status === 'COMPLETED' || current.status === 'REFUSED') {
    await restoreAnswers(currentRevision, jobId)
  }
}

async function load() {
  const currentRevision = ++revision
  requestController?.abort()
  requestController = null
  if (pollTimer !== null) globalThis.clearTimeout(pollTimer)
  pollTimer = null
  if (goalPollTimer !== null) globalThis.clearTimeout(goalPollTimer)
  goalPollTimer = null
  answers.value = []
  agentRun.value = null
  goalAnalysis.value = null
  useAgent.value = false
  question.value = ''
  idempotencyKey = null
  errorMessage.value = ''
  submitting.value = false
  if (props.job.status !== 'READY') return

  loading.value = true
  try {
    const page = await props.api.list(props.job.jobId)
    if (currentRevision === revision) answers.value = page.data
    await restoreGoalAnalysis(currentRevision, props.job.jobId)
    const runId = props.agentAvailable ? rememberedAgentRunId() : null
    if (runId && currentRevision === revision) {
      const persisted = await props.agentApi.get(runId)
      if (persisted.jobId === props.job.jobId) await pollAgent(persisted, currentRevision, props.job.jobId)
      else forgetAgentRun()
    }
  } catch (error) {
    if (currentRevision === revision) handleError(error)
  } finally {
    if (currentRevision === revision) loading.value = false
  }
}

async function submit() {
  const normalized = question.value.replace(/\s+/g, ' ').trim()
  if (!normalized || submitting.value || props.job.status !== 'READY') return
  const currentRevision = revision
  const jobId = props.job.jobId
  errorMessage.value = ''
  submitting.value = true
  idempotencyKey ??= globalThis.crypto.randomUUID()
  const controller = new globalThis.AbortController()
  requestController = controller
  const timeout = globalThis.setTimeout(() => controller.abort(), props.timeoutMs)
  try {
    if (props.agentAvailable && useAgent.value) {
      const run = await props.agentApi.create(jobId, normalized, idempotencyKey, props.csrfToken)
      await pollAgent(run, currentRevision, jobId)
    } else {
      const answer = await props.api.ask(
        jobId,
        { question: normalized },
        idempotencyKey,
        props.csrfToken,
        controller.signal,
      )
      if (currentRevision !== revision || props.job.jobId !== jobId) return
      publish(answer)
    }
    if (currentRevision !== revision || props.job.jobId !== jobId) return
    question.value = ''
    idempotencyKey = null
  } catch (error) {
    if (currentRevision === revision && props.job.jobId === jobId) handleError(error)
  } finally {
    globalThis.clearTimeout(timeout)
    if (requestController === controller) requestController = null
    if (currentRevision === revision && props.job.jobId === jobId) submitting.value = false
  }
}

watch(question, () => {
  if (!submitting.value) idempotencyKey = null
})
watch(() => [props.job.jobId, props.job.status] as const, load, { immediate: true })
onBeforeUnmount(() => {
  requestController?.abort()
  if (pollTimer !== null) globalThis.clearTimeout(pollTimer)
  if (goalPollTimer !== null) globalThis.clearTimeout(goalPollTimer)
})
</script>

<template>
  <section
    class="answer-workspace"
    aria-labelledby="answer-title"
  >
    <header class="answer-heading">
      <div>
        <p class="section-index">
          VERIFIED ANSWERS
        </p>
        <h3 id="answer-title">
          证据问答
        </h3>
      </div>
      <span>每条结论都必须绑定时间戳</span>
    </header>

    <p
      v-if="job.status !== 'READY'"
      class="answer-state"
      role="status"
    >
      视频索引尚未就绪，暂时不能提问。
    </p>
    <template v-else>
      <section
        v-if="goalAnalysisAvailable && goalAnalysis"
        class="goal-analysis"
        data-test="goal-analysis-result"
        aria-labelledby="goal-analysis-title"
      >
        <header>
          <div>
            <p class="section-index">
              ANALYSIS GOAL
            </p>
            <h4 id="goal-analysis-title">
              目标分析结果
            </h4>
          </div>
          <strong :data-status="goalAnalysis.state">{{ goalAnalysis.state }}</strong>
        </header>
        <p class="goal-analysis__goal">
          {{ job.goal }}
        </p>
        <p
          v-if="goalAnalysis.state === 'DISABLED'"
          class="answer-state"
        >
          未启用自动目标分析。
        </p>
        <p
          v-else-if="goalAnalysis.state === 'PENDING'"
          class="answer-state"
        >
          等待后台创建目标分析运行…
        </p>
        <p
          v-else-if="goalAnalysis.state === 'REQUESTED' || goalAnalysis.state === 'RUNNING'"
          class="answer-state"
        >
          正在基于视频证据生成目标分析结果…
        </p>
        <p
          v-else-if="goalAnalysis.state === 'REFUSED'"
          class="answer-refusal"
          role="status"
        >
          {{ terminationLabels[goalAnalysis.run?.terminationReason ?? 'NO_SUFFICIENT_EVIDENCE'] ?? '目标分析未产生可发布答案。' }}
        </p>
        <p
          v-else-if="goalAnalysis.state === 'FAILED'"
          class="answer-state answer-state--error"
          role="alert"
        >
          {{ terminationLabels[goalAnalysis.run?.terminationReason ?? 'FAILED'] ?? `目标分析失败：${goalAnalysis.run?.terminationReason ?? 'FAILED'}` }}
        </p>
        <ol
          v-else-if="goalAnalysis.answer?.status === 'ANSWERED'"
          class="claim-list"
        >
          <li
            v-for="(claim, claimIndex) in goalAnalysis.answer.claims"
            :key="claim.claimId"
          >
            <div class="claim-copy">
              <span>{{ String(claimIndex + 1).padStart(2, '0') }}</span>
              <p>{{ claim.text }}</p>
            </div>
            <div class="citation-list">
              <button
                v-for="evidence in claim.citations"
                :key="evidence.citationId"
                type="button"
                data-test="goal-cited-evidence"
                @click="emit('cite', evidence)"
              >
                <span class="citation-meta">{{ evidence.channel }} · {{ formatTime(evidence.startMs) }}–{{ formatTime(evidence.endMs) }}</span>
                <q>{{ evidence.quote }}</q>
                <span class="citation-action">跳到视频证据 →</span>
              </button>
            </div>
          </li>
        </ol>
      </section>

      <form
        class="answer-form"
        data-test="ask-form"
        @submit.prevent="submit"
      >
        <label for="answer-question">
          <span>围绕当前视频提问</span>
          <textarea
            id="answer-question"
            v-model="question"
            data-test="answer-question"
            rows="3"
            maxlength="1000"
            required
            placeholder="例如：Redis 的 String 能保存哪些类型的数据？"
          />
        </label>
        <div class="answer-form__actions">
          <div>
            <small>{{ question.trim().length }} / 1000</small>
            <label
              v-if="agentAvailable"
              class="agent-toggle"
            >
              <input
                v-model="useAgent"
                data-test="agent-toggle"
                type="checkbox"
                :disabled="submitting"
              >
              实验 Agent（默认关闭）
            </label>
          </div>
          <button
            data-test="ask-submit"
            type="submit"
            :disabled="submitting || !question.trim()"
          >
            {{ submitting ? '正在核验…' : useAgent ? '启动 Agent' : '提交问题' }}
          </button>
        </div>
      </form>

      <section
        v-if="agentRun"
        class="agent-run"
        data-test="agent-run"
        aria-labelledby="agent-run-title"
      >
        <header>
          <div>
            <p class="section-index">
              EXPERIMENTAL TRACE
            </p>
            <h4 id="agent-run-title">
              Agent 运行回放
            </h4>
          </div>
          <strong :data-status="agentRun.status">{{ agentRun.status }}</strong>
        </header>
        <p
          v-if="agentRun.terminationReason"
          class="agent-termination"
          data-test="agent-termination"
        >
          {{ terminationLabels[agentRun.terminationReason] ?? `运行已终止：${agentRun.terminationReason}` }}
        </p>
        <dl
          class="agent-budget"
          data-test="agent-budget"
        >
          <div><dt>步骤</dt><dd>{{ agentRun.usage.steps }} / {{ agentRun.budget.maxSteps }}</dd></div>
          <div><dt>答案尝试</dt><dd>{{ agentRun.usage.answerAttempts }} / {{ agentRun.budget.maxAnswerAttempts }}</dd></div>
          <div><dt>Token</dt><dd>{{ agentRun.usage.inputTokens + agentRun.usage.outputTokens }}</dd></div>
          <div><dt>已知费用</dt><dd>¥{{ Number(agentRun.usage.knownCost).toFixed(4) }}</dd></div>
          <div><dt>费用完整性</dt><dd>{{ agentRun.usage.unknownCostCalls > 0 ? `未知费用调用 ${agentRun.usage.unknownCostCalls} 次` : '无未知费用' }}</dd></div>
        </dl>
        <details
          v-if="agentRun.steps.length || agentRun.toolCalls.length"
          data-test="agent-trace"
        >
          <summary>查看去敏执行轨迹</summary>
          <ol>
            <li
              v-for="step in agentRun.steps"
              :key="step.stepNumber"
            >
              <strong>{{ String(step.stepNumber).padStart(2, '0') }} · {{ step.action }}</strong>
              <span>{{ step.observationSummary }}</span>
            </li>
          </ol>
          <p
            v-if="agentRun.toolCalls.some((call) => call.status === 'UNKNOWN')"
            class="agent-unknown"
          >
            检测到 UNKNOWN 调用；系统不会自动重放该高成本步骤。
          </p>
        </details>
      </section>

      <p
        v-if="submitting"
        class="answer-state answer-state--pending"
        data-test="answer-pending"
        role="status"
        aria-live="polite"
      >
        正在检索并核验证据。完成前不会显示未经验证的模型文本。
      </p>
      <p
        v-if="errorMessage"
        class="answer-state answer-state--error"
        data-test="answer-error"
        role="alert"
      >
        {{ errorMessage }}
      </p>
      <p
        v-if="loading"
        class="answer-state"
        aria-live="polite"
      >
        正在恢复已发布问答…
      </p>
      <p
        v-else-if="answers.length === 0 && !submitting && !errorMessage"
        class="answer-empty"
        role="status"
      >
        还没有已发布问答。答案会在通过证据硬门后出现在这里。
      </p>

      <ol
        v-else
        class="answer-list"
        aria-label="已发布问答"
      >
        <li
          v-for="(answer, answerIndex) in answers"
          :key="answer.answerId"
          class="answer-card"
          :data-status="answer.status"
        >
          <article>
            <header class="answer-card__header">
              <span>Q{{ String(answers.length - answerIndex).padStart(2, '0') }}</span>
              <div>
                <h4>{{ answer.question }}</h4>
                <time :datetime="answer.createdAt">{{ new Date(answer.createdAt).toLocaleString('zh-CN') }}</time>
              </div>
            </header>

            <div
              v-if="answer.degradedChannels.length"
              class="answer-degraded"
              data-test="answer-degraded"
              role="status"
            >
              <strong>降级完成</strong>
              <span
                v-for="channel in answer.degradedChannels"
                :key="channel"
              >{{ degradationLabels[channel] }}</span>
            </div>

            <p
              v-if="answer.status === 'REFUSED'"
              class="answer-refusal"
              data-test="answer-refused"
              role="status"
            >
              <strong>明确拒答</strong>
              <span>{{ refusalLabels[answer.refusalReason] }}</span>
            </p>

            <ol
              v-else
              class="claim-list"
            >
              <li
                v-for="(claim, claimIndex) in answer.claims"
                :key="claim.claimId"
                data-test="answer-claim"
              >
                <div class="claim-copy">
                  <span>{{ String(claimIndex + 1).padStart(2, '0') }}</span>
                  <p>{{ claim.text }}</p>
                </div>
                <div class="citation-list">
                  <button
                    v-for="evidence in claim.citations"
                    :key="evidence.citationId"
                    type="button"
                    data-test="cited-evidence"
                    @click="emit('cite', evidence)"
                  >
                    <span class="citation-meta">{{ evidence.channel }} · {{ formatTime(evidence.startMs) }}–{{ formatTime(evidence.endMs) }}</span>
                    <q>{{ evidence.quote }}</q>
                    <span class="citation-action">跳到视频证据 →</span>
                  </button>
                </div>
              </li>
            </ol>
          </article>
        </li>
      </ol>
    </template>
  </section>
</template>
