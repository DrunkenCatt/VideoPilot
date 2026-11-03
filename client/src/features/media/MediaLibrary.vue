<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

import type { MediaAsset, MediaId, Session } from '../../api/contracts'
import { createMediaApi, type MediaApi } from '../../api/media'
import { ApiClientError } from '../../api/session'
import { mediaDurationLabel, mediaStatusLabel } from './mediaPresentation'

const props = withDefaults(defineProps<{
  session: Session
  uploaded?: MediaAsset | null
  selectedId?: MediaId | null
  api?: Pick<MediaApi, 'list' | 'get'>
  pollIntervalMs?: number
}>(), {
  uploaded: null,
  selectedId: null,
  api: undefined,
  pollIntervalMs: 2_000,
})
const emit = defineEmits<{ select: [media: MediaAsset]; unauthenticated: [] }>()
const api = props.api ?? createMediaApi()
const media = ref<MediaAsset[]>([])
const page = ref(1)
const totalPages = ref(0)
const totalItems = ref(0)
const loading = ref(true)
const errorMessage = ref('')
let pollTimer: ReturnType<typeof globalThis.setTimeout> | null = null
let disposed = false
let viewRevision = 0

function isPending(asset: MediaAsset) {
  return asset.status === 'UPLOADED' || asset.status === 'PROBING'
}

function bytes(value: number) {
  if (value < 1024) return `${value} B`
  if (value < 1024 ** 2) return `${(value / 1024).toFixed(1)} KiB`
  if (value < 1024 ** 3) return `${(value / 1024 ** 2).toFixed(1)} MiB`
  return `${(value / 1024 ** 3).toFixed(2)} GiB`
}

function handleError(error: unknown) {
  errorMessage.value = '无法读取媒体资源，请确认本地服务可用后重试。'
  if (error instanceof ApiClientError && error.code === 'AUTHENTICATION_REQUIRED') {
    emit('unauthenticated')
  }
}

function merge(asset: MediaAsset, prepend = false) {
  const index = media.value.findIndex((candidate) => candidate.mediaId === asset.mediaId)
  if (index >= 0) media.value.splice(index, 1, asset)
  else if (prepend) media.value.unshift(asset)
}

function schedulePoll() {
  if (pollTimer !== null) globalThis.clearTimeout(pollTimer)
  pollTimer = null
  if (disposed || !media.value.some(isPending)) return
  pollTimer = globalThis.setTimeout(() => void pollVisible(), props.pollIntervalMs)
}

async function pollVisible() {
  pollTimer = null
  const revision = viewRevision
  const pending = media.value.filter(isPending)
  if (pending.length === 0 || disposed) return
  try {
    const refreshed = await Promise.all(pending.map((asset) => api.get(asset.mediaId)))
    if (revision !== viewRevision || disposed) return
    refreshed.forEach((asset) => merge(asset))
    errorMessage.value = ''
  } catch (error) {
    if (revision === viewRevision && !disposed) handleError(error)
  } finally {
    if (revision === viewRevision && !disposed) schedulePoll()
  }
}

async function load(targetPage = page.value) {
  const revision = ++viewRevision
  if (pollTimer !== null) globalThis.clearTimeout(pollTimer)
  pollTimer = null
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await api.list(targetPage, 8)
    if (revision !== viewRevision || disposed) return
    page.value = result.pagination.page
    totalPages.value = result.pagination.totalPages
    totalItems.value = result.pagination.totalItems
    media.value = [...result.data]
    if (targetPage === 1 && props.uploaded) merge(props.uploaded, true)
  } catch (error) {
    if (revision === viewRevision && !disposed) handleError(error)
  } finally {
    if (revision === viewRevision && !disposed) {
      loading.value = false
      schedulePoll()
    }
  }
}

watch(() => props.uploaded, (asset) => {
  if (!asset) return
  viewRevision += 1
  if (pollTimer !== null) globalThis.clearTimeout(pollTimer)
  pollTimer = null
  loading.value = false
  page.value = 1
  merge(asset, true)
  totalItems.value = Math.max(totalItems.value, media.value.length)
  schedulePoll()
})

onMounted(() => void load())
onBeforeUnmount(() => {
  disposed = true
  if (pollTimer !== null) globalThis.clearTimeout(pollTimer)
})
</script>

<template>
  <section
    class="media-library"
    :aria-label="`${session.username} 的媒体资源`"
  >
    <div class="media-library__heading">
      <div>
        <p class="section-index">
          MEDIA LIBRARY
        </p>
        <h2>媒体资源</h2>
        <p>状态、时长和失败原因均来自服务端可信记录。</p>
      </div>
      <button
        class="text-button"
        data-test="refresh-media"
        :disabled="loading"
        @click="load()"
      >
        刷新
      </button>
    </div>

    <p
      v-if="errorMessage"
      class="form-error"
      role="alert"
    >
      {{ errorMessage }}
    </p>
    <p
      v-if="loading && media.length === 0"
      class="empty-copy"
      aria-live="polite"
    >
      正在读取媒体资源…
    </p>
    <p
      v-else-if="media.length === 0"
      class="empty-copy"
      data-test="media-empty"
    >
      还没有已上传的媒体。完成一次上传后，它会出现在这里。
    </p>
    <ul
      v-else
      class="media-grid"
    >
      <li
        v-for="asset in media"
        :key="asset.mediaId"
        :data-status="asset.status"
      >
        <div class="media-card__topline">
          <span class="media-status">{{ mediaStatusLabel(asset.status) }}</span>
          <span>{{ bytes(asset.contentLength) }}</span>
        </div>
        <h3>{{ asset.originalFilename }}</h3>
        <dl>
          <div><dt>可信时长</dt><dd>{{ mediaDurationLabel(asset.durationMs) }}</dd></div>
          <div><dt>媒体 ID</dt><dd>{{ asset.mediaId }}</dd></div>
        </dl>
        <p
          v-if="asset.failure"
          class="media-failure"
          role="status"
        >
          {{ asset.failure.summary }}
        </p>
        <p
          v-else-if="isPending(asset)"
          class="media-pending"
          aria-live="polite"
        >
          页面会有限频率读取当前可见资源，直到校验结束。
        </p>
        <button
          v-if="asset.status === 'READY'"
          data-test="select-media"
          :aria-pressed="selectedId === asset.mediaId"
          @click="emit('select', asset)"
        >
          {{ selectedId === asset.mediaId ? '已选用于分析' : '选择用于分析' }}
        </button>
      </li>
    </ul>

    <nav
      v-if="totalPages > 1"
      class="media-pagination"
      aria-label="媒体分页"
    >
      <button
        data-test="previous-media-page"
        :disabled="loading || page <= 1"
        @click="load(page - 1)"
      >
        上一页
      </button>
      <span>第 {{ page }} / {{ totalPages }} 页 · {{ totalItems }} 项</span>
      <button
        data-test="next-media-page"
        :disabled="loading || page >= totalPages"
        @click="load(page + 1)"
      >
        下一页
      </button>
    </nav>
  </section>
</template>
