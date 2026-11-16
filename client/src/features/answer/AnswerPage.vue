<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import type { AnalysisJob, CitedEvidence, Session } from '../../api/contracts'
import { createJobsApi } from '../../api/jobs'
import { ApiClientError } from '../../api/session'
import ProductPageHeader from '../../components/ProductPageHeader.vue'
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
const route = useRoute()
const router = useRouter()
const job = ref<AnalysisJob | null>(null)
const loading = ref(false)
const errorMessage = ref('')
let revision = 0
const initialQuestion = computed(() => {
  const value = typeof route.query.question === 'string' ? route.query.question : ''
  return value.slice(0, 1_000)
})

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
  <main class="product-page answer-product-page">
    <ProductPageHeader
      eyebrow="EVIDENCE Q&A"
      title="证据问答"
      description="每条事实结论都绑定可核验的视频证据，证据不足时明确拒答。"
      :status="job ? statusLabel(job.status) : '等待选择视频'"
    />

    <p
      v-if="!jobId"
      class="workspace-product-state product-surface"
      data-test="answers-empty"
      role="status"
    >
      请先通过右上角“切换视频”选择一个视频工作区。
    </p>
    <p
      v-else-if="loading"
      class="workspace-product-state product-surface"
      aria-live="polite"
    >
      正在读取当前任务…
    </p>
    <p
      v-else-if="errorMessage"
      class="workspace-product-state workspace-product-state--error product-surface"
      role="alert"
    >
      {{ errorMessage }}
    </p>

    <template v-else-if="job">
      <AnswerWorkspace
        :job="job"
        :csrf-token="session.csrfToken"
        :initial-question="initialQuestion"
        @cite="openCitation"
        @unauthenticated="emit('unauthenticated')"
      />
    </template>
  </main>
</template>
