<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

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
const search = ref('')
const statusFilter = ref('ALL')
let pollTimer: ReturnType<typeof globalThis.setTimeout> | null = null
let disposed = false
let viewRevision = 0

const filteredMedia = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  return media.value.filter((asset) => (
    (statusFilter.value === 'ALL' || asset.status === statusFilter.value)
    && (!query || asset.originalFilename.toLocaleLowerCase().includes(query))
  ))
})

function isPending(asset: MediaAsset) {
  return asset.status === 'UPLOADED' || asset.status === 'PROBING'
}

function bytes(value: number) {
  if (value < 1024) return `${value} B`
  if (value < 1024 ** 2) return `${(value / 1024).toFixed(1)} KiB`
  if (value < 1024 ** 3) return `${(value / 1024 ** 2).toFixed(1)} MiB`
  return `${(value / 1024 ** 3).toFixed(2)} GiB`
}

function updatedLabel(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
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
    class="media-product-library"
    :aria-label="`${session.username} 的媒体资源`"
  >
    <div class="media-product-toolbar">
      <label>
        <span class="sr-only">搜索媒体</span>
        <input
          v-model="search"
          data-test="media-search"
          type="search"
          placeholder="搜索视频名称"
        >
      </label>
      <label>
        <span class="sr-only">媒体状态</span>
        <select
          v-model="statusFilter"
          data-test="media-status-filter"
        >
          <option value="ALL">全部状态</option>
          <option value="READY">可分析</option>
          <option value="UPLOADED">等待校验</option>
          <option value="PROBING">正在校验</option>
          <option value="FAILED">校验失败</option>
        </select>
      </label>
      <button
        class="media-product-toolbar__refresh"
        data-test="refresh-media"
        :disabled="loading"
        @click="load()"
      >
        {{ loading ? '正在刷新…' : '刷新列表' }}
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
    <p
      v-else-if="filteredMedia.length === 0"
      class="empty-copy product-surface"
      data-test="media-filter-empty"
    >
      没有匹配当前筛选条件的媒体。
    </p>
    <template v-else>
      <ul
        class="media-feature-grid"
        data-test="media-feature-grid"
      >
        <li
          v-for="(asset, index) in filteredMedia.slice(0, 3)"
          :key="asset.mediaId"
          class="product-surface"
          :data-status="asset.status"
        >
          <div
            class="media-feature-card__cover"
            :data-cover="index % 3"
          >
            <span>{{ mediaDurationLabel(asset.durationMs) }}</span>
          </div>
          <div class="media-feature-card__body">
            <div><h2>{{ asset.originalFilename }}</h2><span>{{ mediaStatusLabel(asset.status) }}</span></div>
            <p v-if="asset.failure">
              {{ asset.failure.summary }}
            </p>
            <p v-else>
              {{ bytes(asset.contentLength) }} · 更新于 {{ updatedLabel(asset.updatedAt) }}
            </p>
            <button
              v-if="asset.status === 'READY'"
              data-test="select-media"
              :aria-pressed="selectedId === asset.mediaId"
              @click="emit('select', asset)"
            >
              {{ selectedId === asset.mediaId ? '已选用于分析' : '创建解析任务' }}
            </button>
          </div>
        </li>
      </ul>

      <section class="media-recent product-surface">
        <header><h2>最近上传</h2><span>当前页 {{ filteredMedia.length }} 项</span></header>
        <div class="media-recent__scroll">
          <table data-test="media-table">
            <thead><tr><th>媒体</th><th>状态</th><th>大小</th><th>可信时长</th><th>最近更新</th></tr></thead>
            <tbody>
              <tr
                v-for="asset in filteredMedia"
                :key="asset.mediaId"
              >
                <td><strong>{{ asset.originalFilename }}</strong><small>{{ asset.mediaId }}</small></td>
                <td><span :data-status="asset.status">{{ mediaStatusLabel(asset.status) }}</span></td>
                <td>{{ bytes(asset.contentLength) }}</td>
                <td>{{ mediaDurationLabel(asset.durationMs) }}</td>
                <td>{{ updatedLabel(asset.updatedAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>

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
