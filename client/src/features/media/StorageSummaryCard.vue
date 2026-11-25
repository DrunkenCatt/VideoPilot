<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { createMediaApi, type MediaApi } from '../../api/media'
import { ApiClientError } from '../../api/session'
import type { MediaStorageSummary } from '../../api/contracts'

const props = defineProps<{ api?: Pick<MediaApi, 'storageSummary'> }>()
const emit = defineEmits<{ unauthenticated: [] }>()

const api = props.api ?? createMediaApi()
const summary = ref<MediaStorageSummary | null>(null)
const loading = ref(true)
const failed = ref(false)
const percentage = computed(() => {
  if (!summary.value?.capacityBytes) return null
  return Math.min(100, Math.round((summary.value.usedBytes / summary.value.capacityBytes) * 100))
})

function formatBytes(value: number): string {
  if (value === 0) return '0 B'
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
  const unitIndex = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1)
  const amount = value / 1024 ** unitIndex
  return `${new Intl.NumberFormat('zh-CN', { maximumFractionDigits: amount < 10 ? 1 : 0 }).format(amount)} ${units[unitIndex]}`
}

async function loadSummary() {
  loading.value = true
  failed.value = false
  try {
    summary.value = await api.storageSummary()
  } catch (error) {
    summary.value = null
    failed.value = true
    if (error instanceof ApiClientError && error.status === 401) emit('unauthenticated')
  } finally {
    loading.value = false
  }
}

onMounted(loadSummary)
</script>

<template>
  <section
    class="storage-summary"
    aria-labelledby="storage-summary-title"
    :aria-busy="loading"
  >
    <div class="storage-summary__heading">
      <strong id="storage-summary-title">本地存储</strong>
      <span v-if="percentage !== null">{{ percentage }}%</span>
      <span v-else-if="summary">未配置</span>
    </div>

    <p
      v-if="loading"
      role="status"
    >
      正在读取存储信息…
    </p>
    <div
      v-else-if="summary"
      class="storage-summary__content"
    >
      <div
        v-if="percentage !== null"
        class="storage-summary__meter"
        role="meter"
        aria-label="本地存储使用率"
        :aria-valuenow="percentage"
        aria-valuemin="0"
        aria-valuemax="100"
      >
        <span :style="{ width: `${percentage}%` }" />
      </div>
      <p data-test="storage-usage">
        {{ formatBytes(summary.usedBytes) }}<template v-if="summary.capacityBytes !== null">
          / {{ formatBytes(summary.capacityBytes) }}
        </template>
      </p>
      <small>{{ summary.mediaCount }} 个视频<template v-if="summary.capacityBytes === null"> · 容量未配置</template></small>
    </div>
    <div
      v-else-if="failed"
      class="storage-summary__error"
      role="status"
    >
      <p>存储信息暂不可用</p>
      <button
        data-test="storage-retry"
        type="button"
        @click="loadSummary"
      >
        重试
      </button>
    </div>
  </section>
</template>
