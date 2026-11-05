<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import type { AnalysisJob, KeyframeReference, VideoContext, VideoSegment } from '../../api/contracts'
import { createContextApi, type ContextApi } from '../../api/context'
import { ApiClientError } from '../../api/session'
import { stageLabel } from '../jobs/jobPresentation'

const props = withDefaults(defineProps<{
  job: AnalysisJob
  api?: ContextApi
}>(), {
  api: () => createContextApi(),
})
const emit = defineEmits<{ unauthenticated: [] }>()

const context = ref<VideoContext | null>(null)
const segments = ref<VideoSegment[]>([])
const nextCursor = ref<number | null>(null)
const hasMore = ref(false)
const loading = ref(false)
const loadingMore = ref(false)
const errorMessage = ref('')
const pageError = ref('')
const playbackUrl = ref('')
const playbackError = ref('')
const currentTimeMs = ref(0)
const selectedKeyframe = ref<KeyframeReference | null>(null)
const keyframeUrl = ref('')
const keyframeError = ref('')
const player = ref<globalThis.HTMLVideoElement | null>(null)
let revision = 0
let playbackAutoRenewed = false
let keyframeAutoRenewed = false

const currentOrdinal = computed(() => {
  const time = currentTimeMs.value
  return segments.value.find((segment, index) => (
    segment.startMs <= time
      && (time < segment.endMs || (index === segments.value.length - 1 && time === segment.endMs))
  ))?.ordinal
})

function formatTime(milliseconds: number): string {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1_000))
  const hours = Math.floor(totalSeconds / 3_600)
  const minutes = Math.floor((totalSeconds % 3_600) / 60)
  const seconds = totalSeconds % 60
  const clock = `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
  return hours > 0 ? `${String(hours).padStart(2, '0')}:${clock}` : clock
}

function safeMessage(error: unknown): string {
  if (!(error instanceof ApiClientError)) return '无法读取视频上下文，请确认本地服务可用。'
  if (error.code === 'AUTHENTICATION_REQUIRED') return '会话已过期，请重新登录。'
  if (error.code === 'RESOURCE_NOT_FOUND') return '视频上下文不可用或无权访问。'
  if (error.code === 'CONFLICT') return '上下文仍在处理中，请稍后从服务端刷新。'
  return error.retryable ? '上下文服务暂时不可用，请稍后重试。' : '无法读取视频上下文。'
}

function handleError(error: unknown) {
  errorMessage.value = safeMessage(error)
  if (error instanceof ApiClientError && error.code === 'AUTHENTICATION_REQUIRED') emit('unauthenticated')
}

async function renewPlayback(expectedContext = context.value) {
  if (!expectedContext) return
  playbackError.value = ''
  try {
    const access = await props.api.access(expectedContext.links.playback)
    if (context.value?.contextId === expectedContext.contextId) playbackUrl.value = access.url
  } catch (error) {
    if (context.value?.contextId !== expectedContext.contextId) return
    playbackError.value = safeMessage(error)
    if (error instanceof ApiClientError && error.code === 'AUTHENTICATION_REQUIRED') emit('unauthenticated')
  }
}

async function load() {
  const currentRevision = ++revision
  context.value = null
  segments.value = []
  nextCursor.value = null
  hasMore.value = false
  playbackUrl.value = ''
  errorMessage.value = ''
  pageError.value = ''
  selectedKeyframe.value = null
  keyframeUrl.value = ''
  playbackAutoRenewed = false
  loadingMore.value = false
  if (props.job.status !== 'READY') return

  loading.value = true
  try {
    const loadedContext = await props.api.get(props.job.jobId)
    if (currentRevision !== revision) return
    context.value = loadedContext
    const [page] = await Promise.all([
      props.api.segments(loadedContext.links.segments, { pageSize: 100 }),
      renewPlayback(loadedContext),
    ])
    if (currentRevision !== revision) return
    segments.value = page.data
    nextCursor.value = page.nextCursor
    hasMore.value = page.hasMore
  } catch (error) {
    if (currentRevision === revision) handleError(error)
  } finally {
    if (currentRevision === revision) loading.value = false
  }
}

async function loadMore() {
  if (!context.value || !hasMore.value || nextCursor.value === null || loadingMore.value) return
  const contextId = context.value.contextId
  loadingMore.value = true
  pageError.value = ''
  try {
    const page = await props.api.segments(context.value.links.segments, {
      cursor: nextCursor.value,
      pageSize: 100,
    })
    if (context.value?.contextId !== contextId) return
    const known = new Set(segments.value.map((segment) => segment.segmentId))
    segments.value.push(...page.data.filter((segment) => !known.has(segment.segmentId)))
    nextCursor.value = page.nextCursor
    hasMore.value = page.hasMore
  } catch (error) {
    if (context.value?.contextId !== contextId) return
    pageError.value = safeMessage(error)
    if (error instanceof ApiClientError && error.code === 'AUTHENTICATION_REQUIRED') emit('unauthenticated')
  } finally {
    if (context.value?.contextId === contextId) loadingMore.value = false
  }
}

function seek(milliseconds: number) {
  const video = player.value
  if (!video) return
  video.currentTime = milliseconds / 1_000
  currentTimeMs.value = milliseconds
  video.focus()
}

function updateTime(event: globalThis.Event) {
  currentTimeMs.value = Math.round((event.currentTarget as globalThis.HTMLVideoElement).currentTime * 1_000)
}

async function openKeyframe(frame: KeyframeReference) {
  selectedKeyframe.value = frame
  keyframeUrl.value = ''
  keyframeError.value = ''
  keyframeAutoRenewed = false
  seek(frame.timestampMs)
  try {
    const access = await props.api.access(frame.access)
    if (selectedKeyframe.value?.keyframeId === frame.keyframeId) keyframeUrl.value = access.url
  } catch (error) {
    keyframeError.value = safeMessage(error)
    if (error instanceof ApiClientError && error.code === 'AUTHENTICATION_REQUIRED') emit('unauthenticated')
  }
}

async function handlePlaybackError() {
  if (playbackAutoRenewed) {
    playbackError.value = '播放链接续签后仍不可用，请稍后重试。'
    return
  }
  playbackAutoRenewed = true
  await renewPlayback()
}

function playbackLoaded() {
  playbackAutoRenewed = false
  playbackError.value = ''
}

function keyframeLoaded() {
  keyframeAutoRenewed = false
  keyframeError.value = ''
}

async function handleKeyframeError() {
  if (!selectedKeyframe.value || keyframeAutoRenewed) {
    keyframeError.value = '关键帧链接续签后仍不可用。'
    return
  }
  keyframeAutoRenewed = true
  const frame = selectedKeyframe.value
  try {
    const access = await props.api.access(frame.access)
    if (selectedKeyframe.value?.keyframeId === frame.keyframeId) keyframeUrl.value = access.url
  } catch (error) {
    keyframeError.value = safeMessage(error)
  }
}

watch(() => [props.job.jobId, props.job.status] as const, load, { immediate: true })
</script>

<template>
  <section
    class="context-workbench"
    aria-labelledby="context-title"
  >
    <div class="context-heading">
      <div>
        <p class="section-index">
          VIDEO CONTEXT
        </p>
        <h3 id="context-title">
          播放器与统一时间轴
        </h3>
      </div>
      <p
        v-if="context"
        class="context-count"
      >
        {{ segments.length }} / {{ context.segmentCount }} SEGMENTS
      </p>
    </div>

    <p
      v-if="job.status !== 'READY'"
      class="context-state"
      data-test="context-processing"
      role="status"
    >
      服务端状态：{{ stageLabel(job.currentStage) }}。正在构建上下文，未完成片段不会显示为完成。
    </p>
    <p
      v-else-if="loading"
      class="context-state"
      aria-live="polite"
    >
      正在从服务端恢复视频上下文…
    </p>
    <p
      v-else-if="errorMessage"
      class="context-state context-state--error"
      data-test="context-error"
      role="alert"
    >
      {{ errorMessage }}
    </p>

    <template v-else-if="context">
      <div class="context-player-frame">
        <video
          ref="player"
          data-test="context-player"
          :src="playbackUrl || undefined"
          controls
          preload="metadata"
          title="教学视频播放器"
          @timeupdate="updateTime"
          @loadedmetadata="playbackLoaded"
          @error="handlePlaybackError"
        >
          当前浏览器不支持原生视频播放。
        </video>
        <div class="context-player-meta">
          <span>{{ formatTime(currentTimeMs) }} / {{ formatTime(context.durationMs) }}</span>
          <button
            type="button"
            :disabled="!context"
            @click="renewPlayback()"
          >
            续签播放链接
          </button>
        </div>
        <p
          v-if="playbackError"
          class="context-inline-error"
          role="alert"
        >
          {{ playbackError }}
        </p>
      </div>

      <div
        v-if="context.ocrState === 'DEGRADED'"
        class="channel-warning"
        data-test="channel-warning"
        role="status"
      >
        <strong>OCR 已降级</strong>
        <span>语音时间轴仍可使用；缺失的画面文字不会伪装为已完成。</span>
      </div>

      <ol class="segment-list">
        <li
          v-for="segment in segments"
          :key="segment.segmentId"
          class="segment-card"
          :class="{ 'segment-card--active': currentOrdinal === segment.ordinal }"
        >
          <button
            class="segment-time"
            :data-test="`segment-${segment.ordinal}`"
            :aria-current="currentOrdinal === segment.ordinal ? 'true' : undefined"
            type="button"
            @click="seek(segment.startMs)"
          >
            <span>{{ formatTime(segment.startMs) }}</span>
            <small>— {{ formatTime(segment.endMs) }}</small>
          </button>

          <div class="segment-layers">
            <section data-test="asr-layer">
              <h4>ASR · 语音</h4>
              <button
                v-if="segment.asrText"
                type="button"
                @click="seek(segment.startMs)"
              >
                {{ segment.asrText }}
              </button>
              <p v-else>
                此时间窗没有可用语音文本。
              </p>
            </section>

            <section data-test="ocr-layer">
              <h4>OCR · 画面</h4>
              <button
                v-for="(observation, index) in segment.ocr"
                :key="`${segment.segmentId}-ocr-${index}`"
                type="button"
                @click="seek(segment.startMs)"
              >
                {{ observation.text }}
                <small>{{ Math.round(observation.confidence * 100) }}%</small>
              </button>
              <p v-if="segment.ocr.length === 0">
                无已持久化画面文字。
              </p>
            </section>

            <section data-test="keyframe-layer">
              <h4>FRAME · 关键帧</h4>
              <div class="keyframe-buttons">
                <button
                  v-for="(frame, index) in segment.keyframes"
                  :key="frame.keyframeId"
                  :data-test="`keyframe-${segment.ordinal}-${index}`"
                  type="button"
                  @click="openKeyframe(frame)"
                >
                  {{ formatTime(frame.timestampMs) }}
                </button>
              </div>
              <p v-if="segment.keyframes.length === 0">
                此时间窗没有关键帧。
              </p>
            </section>
          </div>
        </li>
      </ol>

      <button
        v-if="hasMore"
        class="load-more-segments"
        data-test="load-more-segments"
        type="button"
        :disabled="loadingMore"
        @click="loadMore"
      >
        {{ loadingMore ? '正在读取…' : '读取后续时间轴' }}
      </button>
      <p
        v-if="pageError"
        class="context-inline-error"
        role="alert"
      >
        {{ pageError }}
      </p>

      <aside
        v-if="selectedKeyframe"
        class="keyframe-preview"
        aria-live="polite"
      >
        <div>
          <p class="section-index">
            KEYFRAME · {{ formatTime(selectedKeyframe.timestampMs) }}
          </p>
          <button
            type="button"
            @click="selectedKeyframe = null"
          >
            关闭预览
          </button>
        </div>
        <img
          v-if="keyframeUrl"
          data-test="keyframe-preview"
          :src="keyframeUrl"
          :alt="`视频 ${formatTime(selectedKeyframe.timestampMs)} 的关键帧`"
          @load="keyframeLoaded"
          @error="handleKeyframeError"
        >
        <p v-if="keyframeError || !keyframeUrl">
          {{ keyframeError || '正在签发关键帧链接…' }}
        </p>
      </aside>
    </template>
  </section>
</template>
