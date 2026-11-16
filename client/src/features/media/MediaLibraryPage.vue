<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import type { MediaAsset, Session } from '../../api/contracts'
import { createJobsApi } from '../../api/jobs'
import { ApiClientError } from '../../api/session'
import ProductPageHeader from '../../components/ProductPageHeader.vue'
import MediaLibrary from './MediaLibrary.vue'
import MediaUploadPanel from './MediaUploadPanel.vue'

type JobsApi = Pick<ReturnType<typeof createJobsApi>, 'submit'>

const props = defineProps<{
  session: Session
  jobsApi?: JobsApi
}>()
const emit = defineEmits<{ unauthenticated: [] }>()
const router = useRouter()
const jobsApi = props.jobsApi ?? createJobsApi()
const uploadedMedia = ref<MediaAsset | null>(null)
const selectedMedia = ref<MediaAsset | null>(null)
const goal = ref('')
const submitting = ref(false)
const errorMessage = ref('')
const uploadOpen = ref(false)
const analysisOpen = ref(false)
let idempotencyKey: string | null = null

watch([() => selectedMedia.value?.mediaId, goal], () => {
  idempotencyKey = null
})

function messageFor(error: unknown): string {
  if (!(error instanceof ApiClientError)) return '无法连接本地服务，请确认后端已启动。'
  if (error.code === 'MEDIA_NOT_READY') return '该媒体尚未就绪，不能创建解析任务。'
  if (error.code === 'IDEMPOTENCY_KEY_REUSED') return '提交内容已经变化，请重新确认后再试。'
  if (error.code === 'AUTHENTICATION_REQUIRED') return '会话已过期，请重新登录。'
  return error.retryable ? '服务暂时不可用，请稍后重试。' : '任务创建失败，请检查分析目标。'
}

function uploaded(media: MediaAsset) {
  uploadedMedia.value = media
  uploadOpen.value = false
}

function selectMedia(media: MediaAsset) {
  selectedMedia.value = media
  errorMessage.value = ''
  analysisOpen.value = true
}

async function submit() {
  if (!selectedMedia.value || submitting.value) return
  const media = selectedMedia.value
  errorMessage.value = ''
  submitting.value = true
  idempotencyKey ??= globalThis.crypto.randomUUID()
  try {
    const job = await jobsApi.submit(
      { mediaId: media.mediaId, goal: goal.value.trim(), mode: 'STANDARD' },
      idempotencyKey,
      props.session.csrfToken,
    )
    idempotencyKey = null
    await router.push({ name: 'analysis-detail', params: { jobId: job.jobId } })
  } catch (error) {
    errorMessage.value = messageFor(error)
    if (error instanceof ApiClientError && error.code === 'AUTHENTICATION_REQUIRED') {
      emit('unauthenticated')
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="product-page media-product-page">
    <ProductPageHeader
      eyebrow="MEDIA LIBRARY"
      title="媒体资料库"
      description="管理本地上传的视频、媒体状态和可复用的分析入口。"
      status="媒体状态来自服务端"
    >
      <template #actions>
        <button
          class="product-primary-action"
          data-test="open-upload"
          type="button"
          @click="uploadOpen = true"
        >
          + 上传视频
        </button>
      </template>
    </ProductPageHeader>

    <MediaLibrary
      :session="session"
      :uploaded="uploadedMedia"
      :selected-id="selectedMedia?.mediaId"
      @select="selectMedia"
      @unauthenticated="emit('unauthenticated')"
    />

    <div
      v-if="uploadOpen"
      class="product-modal-backdrop"
      data-test="upload-dialog"
      role="presentation"
      @click.self="uploadOpen = false"
    >
      <section
        class="product-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="upload-dialog-title"
      >
        <header>
          <div>
            <p>UPLOAD VIDEO</p><h2 id="upload-dialog-title">
              上传本地视频
            </h2>
          </div><button
            type="button"
            aria-label="关闭上传"
            @click="uploadOpen = false"
          >
            ×
          </button>
        </header>
        <MediaUploadPanel
          :session="session"
          compact
          @uploaded="uploaded"
          @unauthenticated="emit('unauthenticated')"
        />
      </section>
    </div>

    <div
      v-if="analysisOpen"
      class="product-modal-backdrop"
      data-test="analysis-dialog"
      role="presentation"
      @click.self="analysisOpen = false"
    >
      <aside
        class="product-modal analysis-setup-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="submission-title"
      >
        <header>
          <div>
            <p>ANALYSIS SETUP</p><h2 id="submission-title">
              创建解析任务
            </h2>
          </div><button
            type="button"
            aria-label="关闭任务创建"
            @click="analysisOpen = false"
          >
            ×
          </button>
        </header>
        <p>只有通过服务端校验的媒体可以创建任务。媒体就绪不代表已经可以问答。</p>
        <form
          data-test="submit-job"
          @submit.prevent="submit"
        >
          <div
            class="selected-media"
            data-test="selected-media"
          >
            <span>已选媒体</span>
            <template v-if="selectedMedia">
              <strong>{{ selectedMedia.originalFilename }}</strong>
              <small>{{ selectedMedia.mediaId }}</small>
            </template>
            <p v-else>
              请从左侧选择一条状态为“可分析”的媒体。
            </p>
          </div>
          <label for="analysis-goal">
            <span>分析目标</span>
            <textarea
              id="analysis-goal"
              v-model="goal"
              required
              maxlength="4000"
              rows="5"
              placeholder="例如：梳理视频讲解的核心概念，并保留可追溯证据"
            />
          </label>
          <p
            v-if="errorMessage"
            class="form-error"
            role="alert"
          >
            {{ errorMessage }}
          </p>
          <button
            data-test="create-job"
            type="submit"
            :disabled="submitting || !selectedMedia"
          >
            <span>{{ submitting ? '正在创建…' : '创建解析任务' }}</span>
            <span aria-hidden="true">→</span>
          </button>
        </form>
      </aside>
    </div>
  </main>
</template>
