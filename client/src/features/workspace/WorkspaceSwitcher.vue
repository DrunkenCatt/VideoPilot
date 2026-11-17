<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import type { AnalysisJob, MediaAsset, PageResponse } from '../../api/contracts'
import { createJobsApi } from '../../api/jobs'
import { createMediaApi } from '../../api/media'
import { ApiClientError } from '../../api/session'
import { statusLabel } from '../jobs/jobPresentation'
import { useModalFocus } from '../../composables/useModalFocus'

type JobsQuery = Pick<ReturnType<typeof createJobsApi>, 'list' | 'get'>
type MediaQuery = Pick<ReturnType<typeof createMediaApi>, 'list' | 'get'>

const props = defineProps<{
  jobsApi?: JobsQuery
  mediaApi?: MediaQuery
}>()
const emit = defineEmits<{ unauthenticated: [] }>()

const jobsApi = props.jobsApi ?? createJobsApi()
const mediaApi = props.mediaApi ?? createMediaApi()
const route = useRoute()
const router = useRouter()
const jobs = ref<AnalysisJob[]>([])
const media = ref<MediaAsset[]>([])
const loading = ref(true)
const errorMessage = ref('')
const open = ref(false)
const dialog = ref<globalThis.HTMLElement | null>(null)
let revision = 0

const jobScopedRoutes = new Set(['workspace', 'context', 'answers'])
const routeJobId = computed(() => typeof route.params.jobId === 'string' ? route.params.jobId : '')
const currentJob = computed(() => jobs.value.find((job) => job.jobId === routeJobId.value) ?? null)
const currentMedia = computed(() => media.value.find((item) => item.mediaId === currentJob.value?.mediaId) ?? null)
const sortedJobs = computed(() => [...jobs.value].sort((left, right) => right.updatedAt.localeCompare(left.updatedAt)))
const groups = computed(() => media.value.map((item) => ({
  media: item,
  jobs: sortedJobs.value.filter((job) => job.mediaId === item.mediaId),
})))

function safeMessage(error: unknown): string {
  if (error instanceof ApiClientError && error.code === 'AUTHENTICATION_REQUIRED') return '会话已过期，请重新登录。'
  if (error instanceof ApiClientError && error.code === 'RESOURCE_NOT_FOUND') return '该任务已删除，或当前账号无权访问。'
  return '暂时无法读取最近视频，请稍后重试。'
}

function handleError(error: unknown): string {
  if (error instanceof ApiClientError && error.code === 'AUTHENTICATION_REQUIRED') emit('unauthenticated')
  return safeMessage(error)
}

function isJobId(value: string): boolean {
  return /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value)
}

function jobPath(jobId: string) {
  return `/api/v1/analysis-jobs/${encodeURIComponent(jobId)}`
}

async function addMissingMedia(currentRevision: number) {
  const known = new Set(media.value.map((item) => item.mediaId))
  const missingIds = [...new Set(jobs.value.map((job) => job.mediaId).filter((id) => !known.has(id)))]
  const results = await Promise.allSettled(missingIds.map((id) => mediaApi.get(id)))
  if (currentRevision !== revision) return
  for (const result of results) {
    if (result.status === 'fulfilled' && !media.value.some((item) => item.mediaId === result.value.mediaId)) {
      media.value.push(result.value)
    }
    if (result.status === 'rejected' && result.reason instanceof ApiClientError && result.reason.code === 'AUTHENTICATION_REQUIRED') {
      emit('unauthenticated')
    }
  }
}

async function resolveRoute(currentRevision: number) {
  const jobId = routeJobId.value
  if (!jobId) {
    if (!jobScopedRoutes.has(String(route.name))) return
    const latestReady = sortedJobs.value.find((job) => job.status === 'READY')
    if (latestReady && currentRevision === revision) {
      await router.replace({ name: route.name ?? 'workspace', params: { ...route.params, jobId: latestReady.jobId } })
    }
    return
  }
  if (!isJobId(jobId)) {
    errorMessage.value = '链接中的任务标识格式无效，请重新选择视频。'
    return
  }
  if (jobs.value.some((job) => job.jobId === jobId)) return

  try {
    const loaded = await jobsApi.get(jobPath(jobId))
    if (currentRevision !== revision || routeJobId.value !== jobId) return
    jobs.value.push(loaded)
    if (!media.value.some((item) => item.mediaId === loaded.mediaId)) {
      const loadedMedia = await mediaApi.get(loaded.mediaId)
      if (currentRevision === revision && routeJobId.value === jobId) media.value.push(loadedMedia)
    }
  } catch (error) {
    if (currentRevision !== revision || routeJobId.value !== jobId) return
    errorMessage.value = handleError(error)
  }
}

async function load() {
  const currentRevision = ++revision
  loading.value = true
  errorMessage.value = ''
  try {
    const [jobPage, mediaPage]: [PageResponse<AnalysisJob>, PageResponse<MediaAsset>] = await Promise.all([
      jobsApi.list(),
      mediaApi.list(1, 20),
    ])
    if (currentRevision !== revision) return
    jobs.value = jobPage.data
    media.value = mediaPage.data
    await addMissingMedia(currentRevision)
    await resolveRoute(currentRevision)
  } catch (error) {
    if (currentRevision === revision) errorMessage.value = handleError(error)
  } finally {
    if (currentRevision === revision) loading.value = false
  }
}

function openDialog() {
  open.value = true
}

function closeDialog() {
  open.value = false
}

const { handleKeydown } = useModalFocus(open, dialog, closeDialog)

async function select(job: AnalysisJob) {
  const name = jobScopedRoutes.has(String(route.name)) ? route.name : 'workspace'
  open.value = false
  await router.push({ name: name ?? 'workspace', params: { jobId: job.jobId } })
}

watch(() => route.params.jobId, () => {
  errorMessage.value = ''
  void resolveRoute(revision)
})

onMounted(load)
</script>

<template>
  <div class="workspace-switcher">
    <button
      type="button"
      class="workspace-switcher__trigger"
      data-test="workspace-switcher"
      :disabled="loading"
      @click="openDialog"
    >
      <span>{{ currentMedia?.originalFilename ?? currentJob?.goal ?? (loading ? '正在读取…' : '切换视频') }}</span>
      <small v-if="currentJob">{{ statusLabel(currentJob.status) }}</small>
    </button>
    <span
      v-if="errorMessage"
      class="workspace-switcher__error"
      role="status"
    >{{ errorMessage }}</span>

    <Teleport to="body">
      <div
        v-if="open"
        class="workspace-dialog-backdrop"
        @click.self="closeDialog"
      >
        <section
          ref="dialog"
          class="workspace-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="workspace-dialog-title"
          @keydown="handleKeydown"
        >
          <header>
            <div>
              <p class="section-index">
                CURRENT VIDEO
              </p>
              <h2 id="workspace-dialog-title">
                切换视频
              </h2>
            </div>
            <button
              type="button"
              aria-label="关闭切换视频"
              @click="closeDialog"
            >
              ×
            </button>
          </header>
          <p class="workspace-dialog__intro">
            按媒体查看最近解析任务。选择任务后，工作台、上下文和问答将共享同一个 Job。
          </p>

          <p
            v-if="groups.length === 0"
            class="empty-copy"
          >
            还没有可切换的视频。请先前往媒体资料库上传。
          </p>
          <div
            v-for="group in groups"
            :key="group.media.mediaId"
            class="workspace-media-group"
            data-test="media-group"
          >
            <div class="workspace-media-group__heading">
              <div><strong>{{ group.media.originalFilename }}</strong><small>{{ group.media.status }}</small></div>
              <RouterLink
                v-if="group.jobs.length === 0"
                :to="{ name: 'library' }"
                @click="closeDialog"
              >
                创建解析任务
              </RouterLink>
            </div>
            <button
              v-for="job in group.jobs"
              :key="job.jobId"
              type="button"
              class="workspace-job"
              data-test="workspace-job"
              :aria-current="job.jobId === currentJob?.jobId ? 'true' : undefined"
              @click="select(job)"
            >
              <span><strong>{{ job.goal }}</strong><small>{{ new Date(job.updatedAt).toLocaleString('zh-CN') }}</small></span>
              <span class="workspace-job__status">{{ statusLabel(job.status) }}</span>
            </button>
          </div>
        </section>
      </div>
    </Teleport>
  </div>
</template>
