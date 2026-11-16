<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import type { AnalysisJob, VideoContext, VideoSegment } from '../../api/contracts'
import { createContextApi, type ContextApi } from '../../api/context'
import { ApiClientError } from '../../api/session'
import { statusLabel } from '../jobs/jobPresentation'

const props = withDefaults(defineProps<{
  job: AnalysisJob
  api?: ContextApi
}>(), {
  api: () => createContextApi(),
})
const emit = defineEmits<{ unauthenticated: [] }>()

const context = ref<VideoContext | null>(null)
const segments = ref<VideoSegment[]>([])
const playbackUrl = ref('')
const loading = ref(false)
const errorMessage = ref('')
const currentTimeMs = ref(0)
const player = ref<globalThis.HTMLVideoElement | null>(null)
let revision = 0
let renewed = false

const activeSegmentId = computed(() => segments.value.find((segment) => (
  segment.startMs <= currentTimeMs.value && currentTimeMs.value < segment.endMs
))?.segmentId)

function formatTime(milliseconds: number): string {
  const seconds = Math.max(0, Math.floor(milliseconds / 1_000))
  const minutes = Math.floor(seconds / 60)
  return `${String(minutes).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}

function channelLabel(state: VideoContext['ocrState']): string {
  if (state === 'AVAILABLE') return '正常'
  if (state === 'DEGRADED') return '降级'
  if (state === 'FAILED') return '失败'
  return '处理中'
}

function safeMessage(error: unknown): string {
  if (!(error instanceof ApiClientError)) return '无法读取视频上下文，请确认本地服务可用。'
  if (error.code === 'AUTHENTICATION_REQUIRED') return '会话已过期，请重新登录。'
  if (error.code === 'RESOURCE_NOT_FOUND') return '视频上下文不可用或无权访问。'
  return error.retryable ? '上下文服务暂时不可用，请稍后重试。' : '无法读取视频上下文。'
}

async function renewPlayback(expected = context.value) {
  if (!expected) return
  const access = await props.api.access(expected.links.playback)
  if (context.value?.contextId === expected.contextId) playbackUrl.value = access.url
}

async function load() {
  const currentRevision = ++revision
  context.value = null
  segments.value = []
  playbackUrl.value = ''
  errorMessage.value = ''
  renewed = false
  if (props.job.status !== 'READY') return

  loading.value = true
  try {
    const loaded = await props.api.get(props.job.jobId)
    if (currentRevision !== revision) return
    context.value = loaded
    const [page] = await Promise.all([
      props.api.segments(loaded.links.segments, { pageSize: 100 }),
      renewPlayback(loaded),
    ])
    if (currentRevision === revision) segments.value = page.data
  } catch (error) {
    if (currentRevision !== revision) return
    errorMessage.value = safeMessage(error)
    if (error instanceof ApiClientError && error.code === 'AUTHENTICATION_REQUIRED') emit('unauthenticated')
  } finally {
    if (currentRevision === revision) loading.value = false
  }
}

function seek(segment: VideoSegment) {
  if (!player.value) return
  player.value.currentTime = segment.startMs / 1_000
  currentTimeMs.value = segment.startMs
  player.value.focus()
}

function updateTime(event: globalThis.Event) {
  currentTimeMs.value = Math.round((event.currentTarget as globalThis.HTMLVideoElement).currentTime * 1_000)
}

async function handlePlaybackError() {
  if (renewed) {
    errorMessage.value = '播放链接续签后仍不可用，请稍后重试。'
    return
  }
  renewed = true
  try {
    await renewPlayback()
  } catch (error) {
    errorMessage.value = safeMessage(error)
  }
}

watch(() => [props.job.jobId, props.job.status] as const, load, { immediate: true })
</script>

<template>
  <div
    class="workspace-context-panel"
    data-test="workspace-context-panel"
  >
    <section
      class="workspace-player product-surface"
      data-test="workspace-player-panel"
      aria-label="当前视频播放器"
    >
      <div
        v-if="job.status !== 'READY'"
        class="workspace-player__placeholder"
      >
        <span>{{ statusLabel(job.status) }}</span>
        <strong>视频上下文尚未就绪</strong>
        <p>解析完成后将在这里提供可定位的视频播放。</p>
      </div>
      <p
        v-else-if="loading"
        class="workspace-player__placeholder"
        aria-live="polite"
      >
        正在加载当前视频…
      </p>
      <video
        v-else-if="playbackUrl"
        ref="player"
        :src="playbackUrl"
        controls
        preload="metadata"
        @timeupdate="updateTime"
        @loadeddata="renewed = false"
        @error="handlePlaybackError"
      >
        当前浏览器不支持视频播放。
      </video>
      <p
        v-else
        class="workspace-player__placeholder"
      >
        {{ errorMessage || '当前视频暂时没有可用的播放链接。' }}
      </p>
      <div class="workspace-player__caption">
        <span>当前分析目标</span>
        <strong>{{ job.goal }}</strong>
      </div>
    </section>

    <section
      class="workspace-evidence product-surface"
      data-test="workspace-evidence-panel"
      aria-labelledby="workspace-evidence-title"
    >
      <header>
        <div>
          <h2 id="workspace-evidence-title">
            当前片段证据
          </h2>
          <p
            v-if="context"
            data-test="workspace-context-summary"
          >
            {{ context.segmentCount }} 个片段 · {{ formatTime(context.durationMs) }}
          </p>
        </div>
        <span v-if="context">OCR {{ channelLabel(context.ocrState) }}</span>
      </header>
      <p
        v-if="job.status !== 'READY'"
        class="workspace-evidence__state"
      >
        当前任务状态：{{ statusLabel(job.status) }}。片段证据将在上下文就绪后显示。
      </p>
      <p
        v-else-if="errorMessage"
        class="workspace-evidence__state workspace-evidence__state--error"
        role="alert"
      >
        {{ errorMessage }}
      </p>
      <p
        v-else-if="!loading && segments.length === 0"
        class="workspace-evidence__state"
      >
        当前上下文没有可展示的片段证据。
      </p>
      <ol
        v-else
        class="workspace-evidence__list"
      >
        <li
          v-for="segment in segments.slice(0, 4)"
          :key="segment.segmentId"
        >
          <button
            type="button"
            data-test="workspace-evidence-item"
            :aria-current="activeSegmentId === segment.segmentId ? 'true' : undefined"
            @click="seek(segment)"
          >
            <span>{{ formatTime(segment.startMs) }}</span>
            <span><strong>{{ segment.asrText || '仅包含视觉证据的片段' }}</strong><small>点击定位到视频证据</small></span>
          </button>
        </li>
      </ol>
    </section>
  </div>
</template>
