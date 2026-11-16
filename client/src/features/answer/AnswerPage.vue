<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import type { AnalysisJob, CitedEvidence, Session } from '../../api/contracts'
import { createJobsApi } from '../../api/jobs'
import { ApiClientError } from '../../api/session'
import { statusLabel } from '../jobs/jobPresentation'
import AnswerWorkspace from './AnswerWorkspace.vue'

type JobsApi = Pick<ReturnType<typeof createJobsApi>, 'get'>

const props = defineProps<{
  jobId?: AnalysisJob['jobId'] | string
  session: Session
  jobsApi?: JobsApi
}>()
const emit = defineEmits<{ unauthenticated: [] }>()
const jobsApi = props.jobsApi ?? createJobsApi()
const router = useRouter()
const job = ref<AnalysisJob | null>(null)
const loading = ref(false)
const errorMessage = ref('')
let revision = 0

function validJobId(value: string): boolean {
  return /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value)
}

function messageFor(error: unknown): string {
  if (!(error instanceof ApiClientError)) return '无法连接本地服务，请确认后端已启动。'
  if (error.code === 'RESOURCE_NOT_FOUND') return '任务不存在，或当前账号无权访问。'
  if (error.code === 'AUTHENTICATION_REQUIRED') return '会话已过期，请重新登录。'
  return error.retryable ? '问答服务暂时不可用，请稍后重试。' : '无法读取当前任务。'
}

async function load(jobId: string | undefined) {
  const currentRevision = ++revision
  job.value = null
  errorMessage.value = ''
  loading.value = false
  if (!jobId) return
  if (!validJobId(jobId)) {
    errorMessage.value = '链接中的任务标识无效，请重新选择视频。'
    return
  }

  loading.value = true
  try {
    const loaded = await jobsApi.get(`/api/v1/analysis-jobs/${encodeURIComponent(jobId)}`)
    if (currentRevision === revision) job.value = loaded
  } catch (error) {
    if (currentRevision !== revision) return
    errorMessage.value = messageFor(error)
    if (error instanceof ApiClientError && error.code === 'AUTHENTICATION_REQUIRED') {
      emit('unauthenticated')
    }
  } finally {
    if (currentRevision === revision) loading.value = false
  }
}

async function openCitation(citation: CitedEvidence) {
  if (!job.value) return
  await router.push({
    name: 'context',
    params: { jobId: job.value.jobId },
    query: {
      segmentId: citation.segmentId,
      fromMs: String(citation.startMs),
      toMs: String(citation.endMs),
    },
  })
}

watch(() => props.jobId, (jobId) => void load(jobId), { immediate: true })
</script>

<template>
  <main class="context-page answers-page">
    <header class="context-page__heading">
      <div>
        <p class="kicker">
          EVIDENCE ANSWERS
        </p>
        <h1>证据问答</h1>
      </div>
      <p>问题、历史与引用只属于 URL 中的当前视频任务。点击引用可回到同一视频的精确时间段。</p>
    </header>

    <p
      v-if="!jobId"
      class="context-page__state"
      data-test="answers-empty"
      role="status"
    >
      请先通过右上角“切换视频”选择一个视频工作区。
    </p>
    <p
      v-else-if="loading"
      class="context-page__state"
      aria-live="polite"
    >
      正在读取当前任务…
    </p>
    <p
      v-else-if="errorMessage"
      class="context-page__state context-page__state--error"
      role="alert"
    >
      {{ errorMessage }}
    </p>

    <template v-else-if="job">
      <section
        class="context-page__job"
        aria-labelledby="current-answer-job"
      >
        <div>
          <p class="section-index">
            CURRENT JOB
          </p>
          <h2 id="current-answer-job">
            {{ job.goal }}
          </h2>
        </div>
        <span :data-status="job.status">{{ statusLabel(job.status) }}</span>
      </section>
      <AnswerWorkspace
        :job="job"
        :csrf-token="session.csrfToken"
        @cite="openCitation"
        @unauthenticated="emit('unauthenticated')"
      />
    </template>
  </main>
</template>
