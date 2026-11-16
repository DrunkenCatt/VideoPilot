<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import type { AnalysisJob, Session } from '../../api/contracts'
import { createJobsApi } from '../../api/jobs'
import { ApiClientError } from '../../api/session'
import VideoContextTimeline from '../context/VideoContextTimeline.vue'
import { stageLabel, statusLabel } from '../jobs/jobPresentation'

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
const question = ref('')
const loading = ref(false)
const errorMessage = ref('')
let revision = 0

const normalizedQuestion = computed(() => question.value.replace(/\s+/g, ' ').trim())

function validJobId(value: string): boolean {
  return /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value)
}

function messageFor(error: unknown): string {
  if (!(error instanceof ApiClientError)) return '无法连接本地服务，请确认后端已启动。'
  if (error.code === 'RESOURCE_NOT_FOUND') return '任务不存在，或当前账号无权访问。'
  if (error.code === 'AUTHENTICATION_REQUIRED') return '会话已过期，请重新登录。'
  return error.retryable ? '工作台服务暂时不可用，请稍后重试。' : '无法读取当前任务。'
}

function statusMessage(current: AnalysisJob): string {
  if (current.status === 'READY') return '视频上下文、索引与证据入口均已就绪。'
  if (current.status === 'FAILED') return current.failure?.summary ?? '解析失败，请前往解析任务查看原因。'
  if (current.status === 'CONTEXT_BUILDING') return '正在构建视频上下文，完成后即可播放和提问。'
  return `当前状态：${statusLabel(current.status)}；阶段：${stageLabel(current.currentStage)}。`
}

async function load(jobId: string | undefined) {
  const currentRevision = ++revision
  job.value = null
  question.value = ''
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

async function ask() {
  if (!job.value || job.value.status !== 'READY' || !normalizedQuestion.value) return
  await router.push({
    name: 'answers',
    params: { jobId: job.value.jobId },
    query: { question: normalizedQuestion.value },
  })
}

watch(() => props.jobId, (jobId) => void load(jobId), { immediate: true })
</script>

<template>
  <main class="context-page workspace-page">
    <header class="context-page__heading">
      <div>
        <p class="kicker">
          VIDEO WORKSPACE
        </p>
        <h1>视频工作台</h1>
      </div>
      <p>播放器、时间片、分析目标与问答入口始终绑定 URL 中的当前任务，不跨视频拼接内容。</p>
    </header>

    <section
      v-if="!jobId"
      class="job-placeholder"
      data-test="workspace-empty"
    >
      <p class="section-index">
        NO CURRENT VIDEO
      </p>
      <h2>选择一个视频，开始可追溯分析</h2>
      <p>先从媒体资料库上传或选择视频；已有任务也可以从解析任务页继续。</p>
      <div class="workspace-empty__actions">
        <RouterLink to="/library">
          打开媒体资料库
        </RouterLink>
        <RouterLink to="/analysis">
          查看解析任务
        </RouterLink>
      </div>
    </section>
    <p
      v-else-if="loading"
      class="context-page__state"
      aria-live="polite"
    >
      正在读取当前视频工作区…
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
        aria-labelledby="workspace-current-job"
      >
        <div>
          <p class="section-index">
            CURRENT VIDEO JOB
          </p>
          <h2 id="workspace-current-job">
            {{ job.goal }}
          </h2>
        </div>
        <span :data-status="job.status">{{ statusLabel(job.status) }}</span>
      </section>

      <div class="workspace-dashboard">
        <VideoContextTimeline
          :job="job"
          @unauthenticated="emit('unauthenticated')"
        />

        <aside
          class="workspace-dashboard__rail"
          aria-label="当前视频操作"
        >
          <section data-test="workspace-goal">
            <p class="section-index">
              ANALYSIS GOAL
            </p>
            <h3>当前分析目标</h3>
            <p>{{ job.goal }}</p>
          </section>

          <section>
            <p class="section-index">
              SERVER STATUS
            </p>
            <h3>真实处理状态</h3>
            <p data-test="workspace-status">
              {{ statusMessage(job) }}
            </p>
            <RouterLink :to="{ name: 'analysis-detail', params: { jobId: job.jobId } }">
              查看任务事件
            </RouterLink>
          </section>

          <form
            data-test="workspace-question-form"
            @submit.prevent="ask"
          >
            <label for="workspace-question">围绕当前视频提问</label>
            <textarea
              id="workspace-question"
              v-model="question"
              data-test="workspace-question"
              maxlength="1000"
              :disabled="job.status !== 'READY'"
              placeholder="例如：视频为什么把 MySQL 作为任务真源？"
            />
            <button
              type="submit"
              data-test="workspace-ask"
              :disabled="job.status !== 'READY' || !normalizedQuestion"
            >
              前往证据问答
            </button>
          </form>
        </aside>
      </div>
    </template>
  </main>
</template>
