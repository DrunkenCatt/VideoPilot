<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import type { AnalysisJob, JobEvent, MediaAsset, ReliabilitySummary, Session } from '../../api/contracts'
import { ApiClientError } from '../../api/session'
import { createJobsApi } from '../../api/jobs'
import { createOperationsApi, type OperationsApi } from '../../api/operations'
import { createMediaApi, type MediaApi } from '../../api/media'
import ProductPageHeader from '../../components/ProductPageHeader.vue'
import JobDetail from './JobDetail.vue'
import ReliabilityPanel from './ReliabilityPanel.vue'
import { stageLabel, statusLabel } from './jobPresentation'

const props = defineProps<{
  session: Session
  jobId?: AnalysisJob['jobId'] | string
  mediaApi?: Pick<MediaApi, 'get'>
  operationsApi?: Pick<OperationsApi, 'get'>
}>()
const emit = defineEmits<{ unauthenticated: []; 'select-job': [jobId: AnalysisJob['jobId']] }>()
const router = useRouter()
const api = createJobsApi()
const mediaApi = props.mediaApi ?? createMediaApi()
const operationsApi = props.operationsApi ?? createOperationsApi()

const jobs = ref<AnalysisJob[]>([])
const selected = ref<AnalysisJob | null>(null)
const events = ref<JobEvent[]>([])
const jobMedia = ref<MediaAsset | null>(null)
const jobMediaUnavailable = ref(false)
const loading = ref(true)
const canceling = ref(false)
const replaying = ref(false)
const errorMessage = ref('')
const reliabilitySummary = ref<ReliabilitySummary | null>(null)
const reliabilityLoading = ref(true)
const reliabilityError = ref('')
const search = ref('')
const statusFilter = ref('ALL')
const reliabilityOpen = ref(false)
let eventController: globalThis.AbortController | null = null
let mediaRevision = 0
let openRevision = 0
let mounted = false

const filteredJobs = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  return jobs.value.filter((job) => (
    (statusFilter.value === 'ALL' || job.status === statusFilter.value)
    && (!query || job.goal.toLocaleLowerCase().includes(query) || job.jobId.toLocaleLowerCase().includes(query))
  ))
})

const jobMetrics = computed(() => ({
  active: jobs.value.filter((job) => !['READY', 'FAILED', 'CANCELED', 'DEAD_LETTERED'].includes(job.status)).length,
  ready: jobs.value.filter((job) => job.status === 'READY').length,
  recovering: jobs.value.filter((job) => job.reliability.recoveryState !== 'NONE').length,
  attention: jobs.value.filter((job) => ['FAILED', 'DEAD_LETTERED'].includes(job.status)).length,
}))

function stageProgress(job: AnalysisJob): string {
  if (['READY', 'FAILED', 'CANCELED', 'DEAD_LETTERED'].includes(job.status)) return '100%'
  const stages = ['PROBING', 'EXTRACTING', 'TRANSCRIBING', 'OCR', 'ALIGNING', 'CONTEXT_BUILDING', 'INDEXING']
  const index = job.currentStage ? stages.indexOf(job.currentStage) : -1
  return `${Math.max(10, Math.round(((index + 1) / stages.length) * 100))}%`
}

function updatedLabel(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

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

async function openWorkspace(jobId: AnalysisJob['jobId']) {
  await router.push({ name: 'workspace', params: { jobId } })
}

async function closeDetail() {
  openRevision += 1
  eventController?.abort()
  selected.value = null
  jobMedia.value = null
  events.value = []
  await router.push({ name: 'analysis' })
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
  <main class="product-page jobs-product-page">
    <ProductPageHeader
      eyebrow="ANALYSIS JOBS"
      title="解析任务"
      description="查看持久化任务、阶段状态、失败原因与恢复动作。"
      status="MySQL 状态真源"
    >
      <template #actions>
        <RouterLink
          class="product-primary-action jobs-new-action"
          to="/library"
        >
          + 新建分析
        </RouterLink>
      </template>
    </ProductPageHeader>

    <p
      v-if="errorMessage"
      class="form-error workspace-error"
      role="alert"
    >
      {{ errorMessage }}
    </p>

    <section
      class="job-metrics"
      data-test="job-metrics"
      aria-label="当前列表任务统计"
    >
      <article class="product-surface">
        <span>活动任务</span><strong>{{ jobMetrics.active }}</strong><small>当前列表</small>
      </article>
      <article class="product-surface">
        <span>已就绪</span><strong>{{ jobMetrics.ready }}</strong><small>可进入工作台</small>
      </article>
      <article class="product-surface">
        <span>恢复中</span><strong>{{ jobMetrics.recovering }}</strong><small>自动或人工恢复</small>
      </article>
      <article class="product-surface">
        <span>需要关注</span><strong>{{ jobMetrics.attention }}</strong><small>失败或死信</small>
      </article>
    </section>

    <div class="jobs-product-toolbar">
      <input
        v-model="search"
        data-test="job-search"
        type="search"
        placeholder="搜索 Job ID 或分析目标"
      >
      <select
        v-model="statusFilter"
        aria-label="任务状态"
      >
        <option value="ALL">
          全部状态
        </option>
        <option value="READY">
          已就绪
        </option>
        <option value="FAILED">
          已失败
        </option>
        <option value="DEAD_LETTERED">
          需要人工处理
        </option>
        <option value="CONTEXT_BUILDING">
          构建上下文
        </option>
      </select>
      <button
        type="button"
        @click="reliabilityOpen = true"
      >
        可靠性面板
      </button>
    </div>

    <section
      class="jobs-product-list product-surface"
      aria-labelledby="jobs-product-title"
    >
      <header>
        <h2 id="jobs-product-title">
          分析任务
        </h2><span>当前列表 {{ jobs.length }} 项</span>
      </header>
      <p
        v-if="loading"
        class="empty-copy"
        aria-live="polite"
      >
        正在读取服务端任务…
      </p>
      <p
        v-else-if="jobs.length === 0"
        data-test="jobs-empty"
        class="empty-copy"
      >
        还没有分析任务。请先到媒体资料库为已就绪媒体创建任务。
      </p>
      <p
        v-else-if="filteredJobs.length === 0"
        class="empty-copy"
      >
        没有匹配当前筛选条件的任务。
      </p>
      <ul
        v-else
        data-test="job-table"
      >
        <li
          v-for="job in filteredJobs"
          :key="job.jobId"
        >
          <button
            data-test="open-job"
            :aria-current="selected?.jobId === job.jobId ? 'page' : undefined"
            @click="openJob(job.links.self)"
          >
            <span
              class="job-row__icon"
              aria-hidden="true"
            >{{ job.status === 'READY' ? '✓' : '▶' }}</span>
            <span class="job-row__identity"><strong>{{ job.goal }}</strong><small>{{ job.jobId }} · attempt {{ job.attempt + 1 }}</small></span>
            <span class="job-row__stage"><small>{{ stageLabel(job.currentStage) }}</small><i><b :style="{ width: stageProgress(job) }" /></i></span>
            <span
              class="job-row__status"
              :data-status="job.status"
            >{{ statusLabel(job.status) }}</span>
            <span class="job-row__updated"><small>最近更新</small>{{ updatedLabel(job.updatedAt) }}</span>
            <span class="job-row__action">查看详情</span>
          </button>
        </li>
      </ul>
    </section>

    <div
      v-if="selected"
      class="job-drawer-backdrop"
      data-test="job-detail-drawer"
      role="presentation"
      @click.self="closeDetail"
    >
      <aside
        class="job-drawer"
        role="dialog"
        aria-modal="true"
        aria-label="任务详情"
      >
        <header>
          <span>任务详情</span><button
            type="button"
            aria-label="关闭任务详情"
            @click="closeDetail"
          >
            ×
          </button>
        </header>
        <JobDetail
          :job="selected"
          :media="jobMedia"
          :media-unavailable="jobMediaUnavailable"
          :events="events"
          :action-busy="canceling || replaying"
          @cancel="cancel"
          @replay="replay"
          @open-workspace="openWorkspace"
        />
      </aside>
    </div>

    <div
      v-if="reliabilityOpen"
      class="product-modal-backdrop"
      role="presentation"
      @click.self="reliabilityOpen = false"
    >
      <section
        class="product-modal reliability-dialog"
        role="dialog"
        aria-modal="true"
        aria-label="可靠性面板"
      >
        <header>
          <div><p>RELIABILITY</p><h2>可靠性面板</h2></div><button
            type="button"
            aria-label="关闭可靠性面板"
            @click="reliabilityOpen = false"
          >
            ×
          </button>
        </header>
        <ReliabilityPanel
          :summary="reliabilitySummary"
          :loading="reliabilityLoading"
          :error="reliabilityError"
          @refresh="loadReliability"
        />
      </section>
    </div>
  </main>
</template>
