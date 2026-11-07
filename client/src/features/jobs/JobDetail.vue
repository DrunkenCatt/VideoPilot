<script setup lang="ts">
import { ref, watch } from 'vue'

import type { AnalysisJob, CitedEvidence, JobEvent, MediaAsset } from '../../api/contracts'
import AnswerWorkspace from '../answer/AnswerWorkspace.vue'
import VideoContextTimeline from '../context/VideoContextTimeline.vue'
import { mediaDurationLabel, mediaStatusLabel } from '../media/mediaPresentation'
import { canCancel, stageLabel, statusLabel } from './jobPresentation'

const props = defineProps<{
  job: AnalysisJob
  media: MediaAsset | null
  mediaUnavailable: boolean
  events: JobEvent[]
  actionBusy: boolean
  csrfToken: string
}>()

defineEmits<{ cancel: [], replay: [reason: string], unauthenticated: [] }>()

const seekTarget = ref<CitedEvidence | null>(null)
const replayOpen = ref(false)
const replayReason = ref('')

watch(() => props.job.jobId, () => {
  replayOpen.value = false
  replayReason.value = ''
})

function cite(citation: CitedEvidence) {
  seekTarget.value = { ...citation }
}
</script>

<template>
  <section
    class="job-detail"
    aria-labelledby="job-detail-title"
  >
    <div class="job-detail__heading">
      <div>
        <p class="section-index">
          PERSISTED JOB
        </p>
        <h2 id="job-detail-title">
          {{ job.goal }}
        </h2>
      </div>
      <p
        class="status-stamp"
        :data-status="job.status"
        data-test="job-status"
      >
        {{ statusLabel(job.status) }}
      </p>
    </div>

    <p
      class="sr-only"
      aria-live="polite"
    >
      任务状态：{{ statusLabel(job.status) }}
    </p>

    <dl class="job-facts">
      <div><dt>阶段</dt><dd>{{ stageLabel(job.currentStage) }}</dd></div>
      <div><dt>执行尝试</dt><dd>{{ job.attempt + 1 }}</dd></div>
      <div><dt>持久化版本</dt><dd>v{{ job.version }}</dd></div>
    </dl>

    <dl
      v-if="media"
      class="job-facts job-media-facts"
      data-test="job-media-facts"
    >
      <div><dt>媒体文件</dt><dd>{{ media.originalFilename }}</dd></div>
      <div><dt>可信时长</dt><dd>{{ mediaDurationLabel(media.durationMs) }}</dd></div>
      <div><dt>媒体状态</dt><dd>{{ mediaStatusLabel(media.status) }}</dd></div>
      <div><dt>媒体 ID</dt><dd>{{ media.mediaId }}</dd></div>
    </dl>
    <p
      v-else-if="mediaUnavailable"
      class="media-failure"
      data-test="job-media-unavailable"
      role="status"
    >
      任务已恢复，但媒体详情暂时无法读取。
    </p>
    <p
      v-else
      class="empty-copy"
      aria-live="polite"
    >
      正在读取任务关联的媒体事实…
    </p>

    <div
      v-if="job.reliability.recoveryState !== 'NONE'"
      class="job-reliability"
      data-test="job-reliability"
      role="status"
    >
      <strong v-if="job.reliability.recoveryState === 'RETRY_WAIT'">系统将在稍后自动重试</strong>
      <strong v-else-if="job.reliability.recoveryState === 'AUTO_RECOVERING'">系统正在自动恢复</strong>
      <strong v-else-if="job.reliability.operatorAction === 'MANUAL_REPLAY'">自动重试已用尽，需要人工处理</strong>
      <strong v-else>该任务无法自动恢复</strong>
      <p v-if="job.reliability.nextRetryAt">
        预计重试：{{ new Date(job.reliability.nextRetryAt).toLocaleString('zh-CN') }}
      </p>
      <small v-if="job.reliability.reasonCode">{{ job.reliability.reasonCode }}</small>
      <button
        v-if="job.reliability.operatorAction === 'MANUAL_REPLAY' && !replayOpen"
        class="danger-button"
        data-test="open-replay"
        :disabled="actionBusy"
        @click="replayOpen = true"
      >
        人工重放
      </button>
      <form
        v-if="job.reliability.operatorAction === 'MANUAL_REPLAY' && replayOpen"
        class="replay-confirmation"
        data-test="replay-confirmation"
        @submit.prevent="$emit('replay', replayReason.trim())"
      >
        <strong>确认发起新的执行尝试？</strong>
        <p>将复用可验证的已有产物；无法确认的阶段会重新执行，可能产生模型调用。</p>
        <label for="replay-reason">操作原因</label>
        <textarea
          id="replay-reason"
          v-model="replayReason"
          required
          maxlength="500"
          rows="3"
        />
        <div>
          <button
            type="button"
            :disabled="actionBusy"
            @click="replayOpen = false"
          >
            返回
          </button>
          <button
            class="danger-button"
            data-test="confirm-replay"
            type="submit"
            :disabled="actionBusy || !replayReason.trim()"
          >
            {{ actionBusy ? '正在提交…' : '确认重放' }}
          </button>
        </div>
      </form>
    </div>

    <div
      v-if="job.failure"
      class="job-failure"
      role="alert"
    >
      <strong>{{ job.failure.retryable ? '可重试故障' : '任务终止' }}</strong>
      <p>{{ job.failure.summary }}</p>
      <small>{{ job.failure.code }}</small>
    </div>

    <div class="evidence-answer-layout">
      <VideoContextTimeline
        :job="job"
        :seek-target="seekTarget"
        @unauthenticated="$emit('unauthenticated')"
      />
      <AnswerWorkspace
        :job="job"
        :csrf-token="csrfToken"
        @cite="cite"
        @unauthenticated="$emit('unauthenticated')"
      />
    </div>

    <div class="timeline-heading">
      <div>
        <p class="section-index">
          SERVER EVENTS
        </p>
        <h3>持久化事件</h3>
      </div>
      <button
        v-if="canCancel(job.status)"
        class="danger-button"
        data-test="cancel-job"
        :disabled="actionBusy"
        @click="$emit('cancel')"
      >
        {{ actionBusy ? '正在提交…' : '取消任务' }}
      </button>
    </div>

    <ol
      class="event-list"
      data-test="job-events"
    >
      <li
        v-if="events.length === 0"
        class="event-empty"
      >
        等待 MySQL 持久化事件…
      </li>
      <li
        v-for="event in events"
        :key="event.sequence"
      >
        <span class="event-sequence">{{ String(event.sequence).padStart(2, '0') }}</span>
        <div>
          <strong>{{ statusLabel(event.status) }}</strong>
          <p>{{ event.message }}</p>
          <time :datetime="event.occurredAt">{{ new Date(event.occurredAt).toLocaleString('zh-CN') }}</time>
        </div>
      </li>
    </ol>
  </section>
</template>
