<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import type { AnalysisJob, Session } from '../../api/contracts'
import { createJobsApi } from '../../api/jobs'
import { ApiClientError } from '../../api/session'
import ProductPageHeader from '../../components/ProductPageHeader.vue'
import { stageLabel, statusLabel } from '../jobs/jobPresentation'
import WorkspaceContextPanel from './WorkspaceContextPanel.vue'

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
  <main class="product-page workspace-product-page">
    <ProductPageHeader
      eyebrow="VIDEO WORKSPACE"
      title="视频工作台"
      description="围绕当前视频内容，开展可定位、可核验的分析与问答。"
      :status="job ? statusLabel(job.status) : '等待选择视频'"
    />

    <section
      v-if="!jobId"
      class="workspace-product-empty product-surface"
      data-test="workspace-empty"
    >
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
      class="workspace-product-state product-surface"
      aria-live="polite"
    >
      正在读取当前视频工作区…
    </p>
    <p
      v-else-if="errorMessage"
      class="workspace-product-state workspace-product-state--error product-surface"
      role="alert"
    >
      {{ errorMessage }}
    </p>

    <template v-else-if="job">
      <div
        class="workspace-product-grid"
        data-test="workspace-product-grid"
      >
        <WorkspaceContextPanel
          :job="job"
          @unauthenticated="emit('unauthenticated')"
        />

        <section
          class="workspace-question-card product-surface"
          data-test="workspace-question-card"
          aria-labelledby="workspace-question-title"
        >
          <div>
            <h2 id="workspace-question-title">
              询问这段视频
            </h2>
            <p>回答仅使用当前视频的可定位证据；信息不足时会明确说明。</p>
          </div>
          <form
            data-test="workspace-question-form"
            @submit.prevent="ask"
          >
            <label
              class="sr-only"
              for="workspace-question"
            >围绕当前视频提问</label>
            <input
              id="workspace-question"
              v-model="question"
              data-test="workspace-question"
              maxlength="1000"
              :disabled="job.status !== 'READY'"
              placeholder="例如：视频为什么把 MySQL 作为任务真源？"
            >
            <button
              type="submit"
              data-test="workspace-ask"
              :disabled="job.status !== 'READY' || !normalizedQuestion"
            >
              发送 →
            </button>
          </form>
          <p
            class="workspace-question-card__goal"
            data-test="workspace-goal"
          >
            当前目标：{{ job.goal }}
          </p>
        </section>

        <section
          class="workspace-progress-card product-surface"
          data-test="workspace-progress-card"
          aria-labelledby="workspace-progress-title"
        >
          <div>
            <p>分析任务</p>
            <h2 id="workspace-progress-title">
              {{ statusLabel(job.status) }}
            </h2>
          </div>
          <strong>{{ job.status === 'READY' ? '100%' : stageLabel(job.currentStage) }}</strong>
          <p data-test="workspace-status">
            {{ statusMessage(job) }}
          </p>
          <span class="workspace-progress-card__track"><i :style="{ width: job.status === 'READY' ? '100%' : '42%' }" /></span>
          <RouterLink :to="{ name: 'analysis-detail', params: { jobId: job.jobId } }">
            查看任务事件 →
          </RouterLink>
        </section>
      </div>
    </template>
  </main>
</template>
