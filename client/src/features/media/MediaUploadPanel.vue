<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'

import type { MediaAsset, Session } from '../../api/contracts'
import { createMediaApi } from '../../api/media'
import { ApiClientError } from '../../api/session'
import { uploadMediaFile, validateUploadFile } from './mediaUpload'

type UploadState = 'idle' | 'ready' | 'hashing' | 'uploading' | 'retrying' | 'aborting' | 'aborted' | 'expired' | 'failed' | 'completed'

const props = defineProps<{
  session: Session
  uploader?: typeof uploadMediaFile
  compact?: boolean
}>()
const emit = defineEmits<{ unauthenticated: []; uploaded: [media: MediaAsset] }>()
const api = createMediaApi()
const file = ref<globalThis.File | null>(null)
const state = ref<UploadState>('idle')
const loadedBytes = ref(0)
const totalBytes = ref(0)
const detail = ref('选择一个本地教学视频。文件信息仅作上传提示，内容真实性由服务端校验。')
const errorMessage = ref('')
const uploaded = ref<MediaAsset | null>(null)
let controller: globalThis.AbortController | null = null

const busy = computed(() => ['hashing', 'uploading', 'retrying', 'aborting'].includes(state.value))
const statusText = computed(() => ({
  idle: '等待选择',
  ready: '可以上传',
  hashing: '正在计算 SHA-256',
  uploading: '正在上传',
  retrying: '正在重试分片',
  aborting: '正在中止',
  aborted: '已中止',
  expired: '会话已过期',
  failed: '上传失败',
  completed: '上传完成',
}[state.value]))

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`
  if (value < 1024 ** 2) return `${(value / 1024).toFixed(1)} KiB`
  if (value < 1024 ** 3) return `${(value / 1024 ** 2).toFixed(1)} MiB`
  return `${(value / 1024 ** 3).toFixed(2)} GiB`
}

function choose(event: globalThis.Event) {
  const selected = (event.target as globalThis.HTMLInputElement).files?.[0] ?? null
  file.value = selected
  uploaded.value = null
  loadedBytes.value = 0
  totalBytes.value = selected?.size ?? 0
  errorMessage.value = ''
  if (!selected) {
    state.value = 'idle'
    return
  }
  const validation = validateUploadFile(selected)
  if (validation) {
    state.value = 'failed'
    errorMessage.value = validation
    return
  }
  state.value = 'ready'
  detail.value = `${selected.name} · ${formatBytes(selected.size)} · ${selected.type || '未知 MIME'}`
}

async function start() {
  if (!file.value || busy.value || errorMessage.value) return
  errorMessage.value = ''
  uploaded.value = null
  loadedBytes.value = 0
  totalBytes.value = file.value.size
  controller = new globalThis.AbortController()
  try {
    const result = await (props.uploader ?? uploadMediaFile)(file.value, props.session.csrfToken, api, {
      signal: controller.signal,
      onHashing: () => {
        state.value = 'hashing'
        detail.value = '在浏览器中分块读取文件并计算声明摘要，不把视频载入应用服务器内存。'
      },
      onProgress: (loaded, total) => {
        state.value = 'uploading'
        loadedBytes.value = loaded
        totalBytes.value = total
        detail.value = `${formatBytes(loaded)} / ${formatBytes(total)}`
      },
      onRetry: (partNumber, attempt) => {
        state.value = 'retrying'
        detail.value = `第 ${partNumber} 个分片连接中断，正在进行第 ${attempt} 次尝试。`
      },
    })
    uploaded.value = result
    emit('uploaded', result)
    state.value = 'completed'
    loadedBytes.value = totalBytes.value
    detail.value = '对象上传完成，服务端正在校验内容；校验通过后即可提交分析。'
  } catch (error) {
    if (controller.signal.aborted || (error instanceof globalThis.DOMException && error.name === 'AbortError')) {
      state.value = 'aborted'
      detail.value = '本次上传已中止，未完成的媒体不能提交分析。'
    } else if (typeof error === 'object' && error !== null && 'code' in error && error.code === 'UPLOAD_EXPIRED') {
      state.value = 'expired'
      errorMessage.value = '上传会话已过期，请重新选择文件并创建新会话。'
    } else {
      state.value = 'failed'
      errorMessage.value = error instanceof ApiClientError && error.retryable
        ? '上传服务暂时不可用，有限重试已用尽，请稍后重新开始。'
        : '上传未完成，请检查文件或本地服务后重试。'
    }
    if (error instanceof ApiClientError && error.code === 'AUTHENTICATION_REQUIRED') emit('unauthenticated')
  } finally {
    controller = null
  }
}

function abortUpload() {
  if (!controller || !busy.value) return
  state.value = 'aborting'
  detail.value = '正在中止对象存储请求并通知服务端释放会话。'
  controller.abort()
}

onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <section
    class="upload-panel"
    :class="{ 'upload-panel--compact': compact }"
    aria-labelledby="upload-title"
  >
    <div class="upload-panel__intro">
      <p class="section-index">
        LOCAL VIDEO · T9F
      </p>
      <h2 id="upload-title">
        上传教学视频
      </h2>
      <p>视频直传对象存储；页面只持有当前分片的短期签名地址，不保存 MinIO 凭据。</p>
    </div>

    <div class="upload-control">
      <label for="media-file">选择视频</label>
      <input
        id="media-file"
        data-test="media-file"
        type="file"
        accept="video/*,.mkv,.webm"
        :disabled="busy"
        @change="choose"
      >
      <div class="upload-actions">
        <button
          data-test="start-upload"
          type="button"
          :disabled="!file || busy || Boolean(errorMessage) || state === 'completed'"
          @click="start"
        >
          {{ state === 'ready' ? '开始上传' : '重新上传' }}
        </button>
        <button
          v-if="busy"
          data-test="abort-upload"
          type="button"
          class="danger-button"
          :disabled="state === 'aborting'"
          @click="abortUpload"
        >
          中止上传
        </button>
      </div>
    </div>

    <div
      class="upload-readout"
      role="status"
    >
      <div class="upload-readout__heading">
        <strong data-test="upload-status">{{ statusText }}</strong>
        <span v-if="totalBytes">{{ Math.floor((loadedBytes / totalBytes) * 100) }}%</span>
      </div>
      <progress
        data-test="upload-progress"
        :value="loadedBytes"
        :max="totalBytes || 1"
      >
        {{ loadedBytes }} / {{ totalBytes }}
      </progress>
      <p>{{ detail }}</p>
      <p
        v-if="errorMessage"
        class="form-error"
        role="alert"
      >
        {{ errorMessage }}
      </p>
      <dl
        v-if="uploaded"
        class="upload-result"
      >
        <div><dt>媒体 ID</dt><dd>{{ uploaded.mediaId }}</dd></div>
        <div><dt>服务端状态</dt><dd>{{ uploaded.status }}</dd></div>
      </dl>
    </div>
  </section>
</template>
