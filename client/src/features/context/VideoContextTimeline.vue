<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'

import type { AnalysisJob, CitedEvidence, KeyframeReference, VideoContext, VideoSegment } from '../../api/contracts'
import { createContextApi, type ContextApi } from '../../api/context'
import { createMediaApi, type MediaApi } from '../../api/media'
import { ApiClientError } from '../../api/session'
import { stageLabel } from '../jobs/jobPresentation'
import { usePlaybackProgress } from '../media/usePlaybackProgress'

type CitationTarget = Pick<CitedEvidence, 'segmentId' | 'startMs' | 'endMs'>

const props = withDefaults(defineProps<{
  job: AnalysisJob
  api?: ContextApi
  mediaApi?: Pick<MediaApi, 'playbackProgress' | 'savePlaybackProgress'>
  csrfToken?: string
  seekTarget?: CitationTarget | null
}>(), {
  api: () => createContextApi(),
  mediaApi: () => createMediaApi(),
  csrfToken: '',
  seekTarget: null,
})
const emit = defineEmits<{ unauthenticated: [] }>()
const progress = usePlaybackProgress(
  props.mediaApi,
  () => props.csrfToken,
  () => emit('unauthenticated'),
)

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

const selectedSegment = computed(() => (
  segments.value.find((segment) => segment.ordinal === currentOrdinal.value) ?? segments.value[0] ?? null
))
const asrCharacterCount = computed(() => segments.value.reduce((total, segment) => total + (segment.asrText?.length ?? 0), 0))
const ocrObservationCount = computed(() => segments.value.reduce((total, segment) => total + segment.ocr.length, 0))

function formatTime(milliseconds: number): string {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1_000))
  const hours = Math.floor(totalSeconds / 3_600)
  const minutes = Math.floor((totalSeconds % 3_600) / 60)
  const seconds = totalSeconds % 60
  const clock = `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
  return hours > 0 ? `${String(hours).padStart(2, '0')}:${clock}` : clock
}

function formatConfidence(confidence: number): string {
  const percentage = confidence <= 1 ? confidence * 100 : confidence
  return `${Math.round(Math.min(100, Math.max(0, percentage)))}%`
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
  let initialCitation: CitationTarget | null = null
  await progress.flush().catch(() => undefined)
  progress.reset()
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
      props.csrfToken ? progress.restore(loadedContext.mediaId, loadedContext.durationMs) : Promise.resolve(),
    ])
    if (currentRevision !== revision) return
    segments.value = page.data
    nextCursor.value = page.nextCursor
    hasMore.value = page.hasMore
    initialCitation = props.seekTarget
  } catch (error) {
    if (currentRevision === revision) handleError(error)
  } finally {
    if (currentRevision === revision) loading.value = false
  }
  if (currentRevision === revision && initialCitation && !errorMessage.value) {
    await nextTick()
    await seekToCitation(initialCitation, currentRevision)
  }
}

async function seekToCitation(citation: CitationTarget, expectedRevision = revision) {
  const loadedContext = context.value
  if (!loadedContext) return
  if (!segments.value.some((segment) => segment.segmentId === citation.segmentId)) {
    try {
      const page = await props.api.segments(loadedContext.links.segments, {
        pageSize: 100,
        fromMs: citation.startMs,
        toMs: citation.endMs,
      })
      if (expectedRevision !== revision || context.value?.contextId !== loadedContext.contextId) return
      const known = new Set(segments.value.map((segment) => segment.segmentId))
      segments.value.push(...page.data.filter((segment) => !known.has(segment.segmentId)))
      segments.value.sort((left, right) => left.ordinal - right.ordinal)
    } catch (error) {
      if (expectedRevision !== revision || context.value?.contextId !== loadedContext.contextId) return
      pageError.value = safeMessage(error)
      if (error instanceof ApiClientError && error.code === 'AUTHENTICATION_REQUIRED') emit('unauthenticated')
      return
    }
  }
  if (expectedRevision === revision) {
    seek(citation.startMs)
    await nextTick()
    const row = globalThis.document.getElementById(`context-segment-${citation.segmentId}`)
    row?.scrollIntoView?.({ block: 'center' })
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
  const video = event.currentTarget as globalThis.HTMLVideoElement
  currentTimeMs.value = Math.round(video.currentTime * 1_000)
  if (context.value && props.csrfToken) {
    progress.track(context.value.mediaId, video.currentTime, context.value.durationMs)
  }
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
  if (player.value) progress.apply(player.value)
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
watch(() => props.seekTarget, (citation) => {
  if (citation) void seekToCitation(citation)
})
onBeforeUnmount(() => {
  void progress.flush().catch(() => undefined).finally(progress.dispose)
})
</script>

<template>
  <section
    class="context-product-workbench"
    aria-labelledby="context-title"
  >
    <p
      v-if="job.status !== 'READY'"
      class="workspace-product-state product-surface"
      data-test="context-processing"
      role="status"
    >
      服务端状态：{{ stageLabel(job.currentStage) }}。正在构建上下文，未完成片段不会显示为完成。
    </p>
    <p
      v-else-if="loading"
      class="workspace-product-state product-surface"
      aria-live="polite"
    >
      正在从服务端恢复视频上下文…
    </p>
    <p
      v-else-if="errorMessage"
      class="workspace-product-state workspace-product-state--error product-surface"
      data-test="context-error"
      role="alert"
    >
      {{ errorMessage }}
    </p>

    <template v-else-if="context">
      <div
        class="context-product-grid"
        data-test="context-product-grid"
      >
        <section class="context-timeline product-surface">
          <header>
            <div>
              <h2 id="context-title">
                多模态时间轴
              </h2><p>{{ job.goal }}</p>
            </div>
            <div><span>ASR {{ context.asrState === 'AVAILABLE' ? '正常' : '降级' }}</span><span>OCR {{ context.ocrState === 'AVAILABLE' ? '正常' : '降级' }}</span></div>
          </header>
          <div
            v-if="context.ocrState === 'DEGRADED'"
            class="channel-warning"
            data-test="channel-warning"
            role="status"
          >
            <strong>OCR 已降级</strong><span>语音时间轴仍可使用；缺失的画面文字不会伪装为已完成。</span>
          </div>
          <ol>
            <li
              v-for="segment in segments"
              :id="`context-segment-${segment.segmentId}`"
              :key="segment.segmentId"
              class="context-timeline__row"
              :data-segment-id="segment.segmentId"
              :data-test="`segment-row-${segment.ordinal}`"
            >
              <span class="context-timeline__time">{{ formatTime(segment.startMs) }}–{{ formatTime(segment.endMs) }}</span>
              <span
                class="context-timeline__marker"
                :class="{ 'context-timeline__marker--active': currentOrdinal === segment.ordinal }"
                aria-hidden="true"
              />
              <button
                class="context-timeline__item"
                :data-test="`segment-${segment.ordinal}`"
                :aria-current="currentOrdinal === segment.ordinal ? 'true' : undefined"
                type="button"
                @click="seek(segment.startMs)"
              >
                <span><strong>{{ segment.asrText || '仅包含视觉证据的片段' }}</strong><small>ASR {{ segment.asrText ? '可用' : '缺失' }} · OCR {{ segment.ocr.length }} 条 · 关键帧 {{ segment.keyframes.length }} 张</small></span>
              </button>
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
        </section>

        <aside class="context-product-rail">
          <section
            class="context-summary product-surface"
            data-test="context-summary-panel"
          >
            <header><h2>上下文概况</h2><span>{{ context.status }}</span></header>
            <dl>
              <div><dt>视频时长</dt><dd>{{ formatTime(context.durationMs) }}</dd></div>
              <div><dt>VideoSegment</dt><dd>{{ context.segmentCount }}</dd></div>
              <div><dt>ASR 字符</dt><dd>{{ asrCharacterCount }}</dd></div>
              <div><dt>OCR 观察</dt><dd>{{ ocrObservationCount }}</dd></div>
            </dl>
          </section>

          <section
            v-if="selectedSegment"
            class="current-segment product-surface"
            data-test="current-segment-panel"
          >
            <header><h2>当前片段详情</h2><span>{{ formatTime(selectedSegment.startMs) }}–{{ formatTime(selectedSegment.endMs) }}</span></header>
            <video
              ref="player"
              data-test="context-player"
              :src="playbackUrl || undefined"
              controls
              preload="metadata"
              title="教学视频播放器"
              @timeupdate="updateTime"
              @loadedmetadata="playbackLoaded"
              @pause="progress.flush"
              @error="handlePlaybackError"
            >当前浏览器不支持原生视频播放。</video>
            <div class="context-player-meta">
              <span>{{ formatTime(currentTimeMs) }} / {{ formatTime(context.durationMs) }}</span>
              <small
                v-if="progress.label.value"
                data-test="context-playback-progress"
              >{{ progress.label.value }}</small>
              <small
                v-else-if="progress.message.value"
                role="status"
              >{{ progress.message.value }}</small><button
                type="button"
                @click="renewPlayback()"
              >
                续签链接
              </button>
            </div>
            <p
              v-if="playbackError"
              class="context-inline-error"
              role="alert"
            >
              {{ playbackError }}
            </p>
            <section data-test="asr-layer">
              <h3>ASR · 语音</h3><p>{{ selectedSegment.asrText || '此时间窗没有可用语音文本。' }}</p>
            </section>
            <details
              open
              data-test="ocr-layer"
            >
              <summary><span>OCR · 画面</span><small>{{ selectedSegment.ocr.length }} 条</small></summary>
              <div
                class="current-segment__ocr-scroll"
                data-test="ocr-scroll"
              >
                <p
                  v-for="(observation, index) in selectedSegment.ocr"
                  :key="index"
                >
                  {{ observation.text }} · {{ formatConfidence(observation.confidence) }}
                </p><p v-if="selectedSegment.ocr.length === 0">
                  无已持久化画面文字。
                </p>
              </div>
            </details>
            <section data-test="keyframe-layer">
              <h3>FRAME · 关键帧</h3><div class="keyframe-buttons">
                <button
                  v-for="(frame, index) in selectedSegment.keyframes"
                  :key="frame.keyframeId"
                  :data-test="`keyframe-${selectedSegment.ordinal}-${index}`"
                  type="button"
                  @click="openKeyframe(frame)"
                >
                  {{ formatTime(frame.timestampMs) }}
                </button>
              </div><p v-if="selectedSegment.keyframes.length === 0">
                此时间窗没有关键帧。
              </p>
            </section>
          </section>
        </aside>
      </div>

      <aside
        v-if="selectedKeyframe"
        class="keyframe-preview"
        aria-live="polite"
      >
        <div>
          <p class="section-index">
            KEYFRAME · {{ formatTime(selectedKeyframe.timestampMs) }}
          </p><button
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
