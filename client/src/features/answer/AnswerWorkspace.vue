<script setup lang="ts">
import { ref, watch } from 'vue'

import { createAnswersApi, type AnswersApi } from '../../api/answers'
import type {
  AnalysisJob,
  AnswerResponse,
  CitedEvidence,
  DegradedChannel,
  RefusalReason,
} from '../../api/contracts'
import { ApiClientError } from '../../api/session'

const props = withDefaults(defineProps<{
  job: AnalysisJob
  csrfToken: string
  api?: AnswersApi
  timeoutMs?: number
}>(), {
  api: () => createAnswersApi(),
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
const errorMessage = ref('')
let revision = 0
let idempotencyKey: string | null = null
let requestController: globalThis.AbortController | null = null

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

async function load() {
  const currentRevision = ++revision
  requestController?.abort()
  requestController = null
  answers.value = []
  question.value = ''
  idempotencyKey = null
  errorMessage.value = ''
  submitting.value = false
  if (props.job.status !== 'READY') return

  loading.value = true
  try {
    const page = await props.api.list(props.job.jobId)
    if (currentRevision === revision) answers.value = page.data
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
    const answer = await props.api.ask(
      jobId,
      { question: normalized },
      idempotencyKey,
      props.csrfToken,
      controller.signal,
    )
    if (currentRevision !== revision || props.job.jobId !== jobId) return
    publish(answer)
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
          <small>{{ question.trim().length }} / 1000</small>
          <button
            data-test="ask-submit"
            type="submit"
            :disabled="submitting || !question.trim()"
          >
            {{ submitting ? '正在核验…' : '提交问题' }}
          </button>
        </div>
      </form>

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
