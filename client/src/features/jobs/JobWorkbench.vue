<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

import type { AnalysisJob, JobEvent, MediaAsset, ReliabilitySummary, Session } from '../../api/contracts'
import { ApiClientError } from '../../api/session'
import { createJobsApi } from '../../api/jobs'
import { createOperationsApi, type OperationsApi } from '../../api/operations'
import { createMediaApi, type MediaApi } from '../../api/media'
import MediaUploadPanel from '../media/MediaUploadPanel.vue'
import MediaLibrary from '../media/MediaLibrary.vue'
import JobDetail from './JobDetail.vue'
import ReliabilityPanel from './ReliabilityPanel.vue'
import { statusLabel } from './jobPresentation'

const props = defineProps<{
  session: Session
  jobId?: AnalysisJob['jobId'] | string
  mediaApi?: Pick<MediaApi, 'get'>
  operationsApi?: Pick<OperationsApi, 'get'>
}>()
const emit = defineEmits<{ unauthenticated: []; 'select-job': [jobId: AnalysisJob['jobId']] }>()
const api = createJobsApi()
const mediaApi = props.mediaApi ?? createMediaApi()
const operationsApi = props.operationsApi ?? createOperationsApi()

const jobs = ref<AnalysisJob[]>([])
const selected = ref<AnalysisJob | null>(null)
const events = ref<JobEvent[]>([])
const selectedMedia = ref<MediaAsset | null>(null)
const jobMedia = ref<MediaAsset | null>(null)
const jobMediaUnavailable = ref(false)
const uploadedMedia = ref<MediaAsset | null>(null)
const goal = ref('')
const loading = ref(true)
const submitting = ref(false)
const canceling = ref(false)
const replaying = ref(false)
const errorMessage = ref('')
const reliabilitySummary = ref<ReliabilitySummary | null>(null)
const reliabilityLoading = ref(true)
const reliabilityError = ref('')
let idempotencyKey: string | null = null
let eventController: globalThis.AbortController | null = null
let mediaRevision = 0
let openRevision = 0
let mounted = false

watch([() => selectedMedia.value?.mediaId, goal], () => {
  idempotencyKey = null
})

function safeMessage(error: unknown): string {
  if (!(error instanceof ApiClientError)) return '无法连接本地服务，请确认后端已启动。'
  if (error.code === 'MEDIA_NOT_READY') return '该媒体尚未就绪，不能提交分析。'
  if (error.code === 'IDEMPOTENCY_KEY_REUSED') return '本次提交意图已变更，请重新提交。'
  if (error.code === 'RESOURCE_NOT_FOUND') return '任务不存在，或当前账号无权访问。'
  if (error.code === 'JOB_NOT_CANCELABLE') return '任务已进入不可取消状态，已从服务端重新读取。'
  if (error.code === 'CONFLICT') return '任务状态已变化，已从服务端重新读取。'
  if (error.code === 'AUTHENTICATION_REQUIRED') return '会话已过期，请重新登录。'
  return error.retryable ? '服务暂时不可用，请稍后重试。' : '请求未完成，请检查输入。'
}

function handleError(error: unknown) {
  errorMessage.value = safeMessage(error)
  if (error instanceof ApiClientError && error.code === 'AUTHENTICATION_REQUIRED') emit('unauthenticated')
}

function replaceJob(job: AnalysisJob) {
  const index = jobs.value.findIndex((candidate) => candidate.jobId === job.jobId)
  if (index >= 0) jobs.value.splice(index, 1, job)
  else jobs.value.unshift(job)
  selected.value = job
}

async function follow(job: AnalysisJob) {
  eventController?.abort()
  const controller = new globalThis.AbortController()
  eventController = controller
  try {
    await api.followEvents(job.links.events, {
      signal: controller.signal,
      onEvent: (event) => {
        if (selected.value?.jobId !== event.jobId) return
        if (!events.value.some((existing) => existing.sequence === event.sequence)) {
          events.value.push(event)
          events.value.sort((left, right) => left.sequence - right.sequence)
        }
        selected.value = { ...selected.value, status: event.status, currentStage: event.stage }
      },
    })
    if (controller.signal.aborted || eventController !== controller) return
    if (selected.value?.jobId === job.jobId) replaceJob(await api.get(job.links.self))
  } catch (error) {
    if (!controller.signal.aborted) handleError(error)
  }
}

async function openJob(selfLink: string, announce = true) {
  const revision = ++openRevision
  eventController?.abort()
  errorMessage.value = ''
  events.value = []
  try {
    const existing = jobs.value.find((job) => job.links.self === selfLink)
    const job = existing ?? await api.get(selfLink)
    if (revision !== openRevision) return
    selected.value = job
    if (announce) emit('select-job', job.jobId)
    void loadJobMedia(job)
    void follow(job)
  } catch (error) {
    if (revision === openRevision) handleError(error)
  }
}

async function loadJobMedia(job: AnalysisJob) {
  const revision = ++mediaRevision
  jobMedia.value = null
  jobMediaUnavailable.value = false
  try {
    const media = await mediaApi.get(job.mediaId)
    if (revision === mediaRevision && selected.value?.jobId === job.jobId) jobMedia.value = media
  } catch (error) {
    if (revision !== mediaRevision || selected.value?.jobId !== job.jobId) return
    jobMediaUnavailable.value = true
    if (error instanceof ApiClientError && error.code === 'AUTHENTICATION_REQUIRED') {
      handleError(error)
    }
  }
}

async function submit() {
  if (!selectedMedia.value) return
  const media = selectedMedia.value
  errorMessage.value = ''
  submitting.value = true
  idempotencyKey ??= globalThis.crypto.randomUUID()
  try {
    const job = await api.submit(
      { mediaId: media.mediaId, goal: goal.value.trim(), mode: 'STANDARD' },
      idempotencyKey,
      props.session.csrfToken,
    )
    idempotencyKey = null
    replaceJob(job)
    jobMedia.value = media
    jobMediaUnavailable.value = false
    mediaRevision += 1
    emit('select-job', job.jobId)
    events.value = []
    void follow(job)
  } catch (error) {
    handleError(error)
  } finally {
    submitting.value = false
  }
}

function selectMedia(media: MediaAsset) {
  selectedMedia.value = media
}

function uploaded(media: MediaAsset) {
  uploadedMedia.value = media
}

async function cancel() {
  if (!selected.value) return
  errorMessage.value = ''
  canceling.value = true
  eventController?.abort()
  try {
    const job = await api.cancel(selected.value.links.cancellations, props.session.csrfToken)
    replaceJob(job)
    void follow(job)
  } catch (error) {
    handleError(error)
    if (selected.value) {
      try {
        replaceJob(await api.get(selected.value.links.self))
      } catch (refreshError) {
        handleError(refreshError)
      }
    }
  } finally {
    canceling.value = false
  }
}

async function replay(reason: string) {
  if (!selected.value || replaying.value) return
  const replayedJob = selected.value
  errorMessage.value = ''
  replaying.value = true
  eventController?.abort()
  try {
    await api.replay(
      replayedJob.jobId,
      { expectedVersion: replayedJob.version, confirmed: true, reason },
      globalThis.crypto.randomUUID(),
      props.session.csrfToken,
    )
    const refreshed = await api.get(replayedJob.links.self)
    replaceJob(refreshed)
    events.value = []
    void follow(refreshed)
  } catch (error) {
    handleError(error)
    try {
      replaceJob(await api.get(replayedJob.links.self))
    } catch (refreshError) {
      handleError(refreshError)
    }
  } finally {
    replaying.value = false
  }
}

async function loadReliability() {
  reliabilityLoading.value = true
  reliabilityError.value = ''
  try {
    reliabilitySummary.value = await operationsApi.get()
  } catch (error) {
    reliabilityError.value = '可靠性摘要暂时不可用；任务主流程不受影响。'
    if (error instanceof ApiClientError && error.code === 'AUTHENTICATION_REQUIRED') emit('unauthenticated')
  } finally {
    reliabilityLoading.value = false
  }
}

onMounted(async () => {
  void loadReliability()
  try {
    const page = await api.list()
    jobs.value = page.data
    mounted = true
    if (props.jobId) {
      await openJob(`/api/v1/analysis-jobs/${encodeURIComponent(props.jobId)}`, false)
    }
  } catch (error) {
    handleError(error)
  } finally {
    loading.value = false
    mounted = true
  }
})

watch(() => props.jobId, (jobId) => {
  if (!mounted) return
  if (!jobId) {
    openRevision += 1
    eventController?.abort()
    selected.value = null
    jobMedia.value = null
    events.value = []
    return
  }
  void openJob(`/api/v1/analysis-jobs/${encodeURIComponent(jobId)}`, false)
})

onBeforeUnmount(() => eventController?.abort())
</script>

<template>
  <main class="jobs-shell">
    <header class="jobs-heading">
      <div>
        <p class="kicker">
          M3 · EVIDENCE ANSWERS
        </p>
        <h1>视频分析工作台</h1>
      </div>
      <p>视频、时间线与问答均来自已持久化事实；答案必须回到可跳转的证据。</p>
    </header>

    <p
      v-if="errorMessage"
      class="form-error workspace-error"
      role="alert"
    >
      {{ errorMessage }}
    </p>

    <ReliabilityPanel
      :summary="reliabilitySummary"
      :loading="reliabilityLoading"
      :error="reliabilityError"
      @refresh="loadReliability"
    />

    <MediaUploadPanel
      :session="session"
      @uploaded="uploaded"
      @unauthenticated="emit('unauthenticated')"
    />

    <MediaLibrary
      :session="session"
      :uploaded="uploadedMedia"
      :selected-id="selectedMedia?.mediaId"
      @select="selectMedia"
      @unauthenticated="emit('unauthenticated')"
    />

    <div class="jobs-layout">
      <aside
        class="jobs-rail"
        aria-label="提交和最近任务"
      >
        <section
          class="submission-panel"
          aria-labelledby="submission-title"
        >
          <p class="section-index">
            READY MEDIA
          </p>
          <h2 id="submission-title">
            提交分析
          </h2>
          <p>从媒体资源中选择一条已通过服务端校验的资源，再填写分析目标。</p>
          <form
            data-test="submit-job"
            @submit.prevent="submit"
          >
            <div
              class="selected-media"
              data-test="selected-media"
            >
              <span>已选媒体</span>
              <template v-if="selectedMedia">
                <strong>{{ selectedMedia.originalFilename }}</strong>
                <small>{{ selectedMedia.mediaId }}</small>
              </template>
              <p v-else>
                请先在媒体资源中选择一条“可分析”资源。
              </p>
            </div>
            <label for="analysis-goal"><span>分析目标</span><textarea
              id="analysis-goal"
              v-model="goal"
              required
              maxlength="4000"
              rows="4"
            /></label>
            <button
              type="submit"
              :disabled="submitting || !selectedMedia"
            >
              <span>{{ submitting ? '正在提交…' : '创建分析任务' }}</span><span aria-hidden="true">→</span>
            </button>
          </form>
        </section>

        <section
          class="recent-jobs"
          aria-labelledby="recent-jobs-title"
        >
          <div class="rail-heading">
            <h2 id="recent-jobs-title">
              最近任务
            </h2><span>{{ jobs.length }}</span>
          </div>
          <p
            v-if="loading"
            aria-live="polite"
          >
            正在读取服务端任务…
          </p>
          <p
            v-else-if="jobs.length === 0"
            data-test="jobs-empty"
            class="empty-copy"
          >
            还没有分析任务。使用上方的已就绪媒体开始。
          </p>
          <ul
            v-else
            class="job-list"
          >
            <li
              v-for="job in jobs"
              :key="job.jobId"
            >
              <button
                data-test="open-job"
                :aria-current="selected?.jobId === job.jobId ? 'page' : undefined"
                @click="openJob(job.links.self)"
              >
                <span>{{ job.goal }}</span><small>{{ statusLabel(job.status) }}</small>
              </button>
            </li>
          </ul>
        </section>
      </aside>

      <JobDetail
        v-if="selected"
        :job="selected"
        :media="jobMedia"
        :media-unavailable="jobMediaUnavailable"
        :events="events"
        :action-busy="canceling || replaying"
        :csrf-token="session.csrfToken"
        @cancel="cancel"
        @replay="replay"
        @unauthenticated="emit('unauthenticated')"
      />
      <section
        v-else
        class="job-placeholder"
        aria-labelledby="placeholder-title"
      >
        <p class="section-index">
          NO JOB SELECTED
        </p>
        <h2 id="placeholder-title">
          选择一条任务查看服务端时间线
        </h2>
        <p>刷新页面后，任务和事件将从 MySQL 重新读取。</p>
      </section>
    </div>
  </main>
</template>
