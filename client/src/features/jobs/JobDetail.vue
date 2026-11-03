<script setup lang="ts">
import type { AnalysisJob, JobEvent, MediaAsset } from '../../api/contracts'
import { mediaDurationLabel, mediaStatusLabel } from '../media/mediaPresentation'
import { canCancel, stageLabel, statusLabel } from './jobPresentation'

defineProps<{
  job: AnalysisJob
  media: MediaAsset | null
  mediaUnavailable: boolean
  events: JobEvent[]
  actionBusy: boolean
}>()

defineEmits<{ cancel: [] }>()
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
      v-if="job.failure"
      class="job-failure"
      role="alert"
    >
      <strong>{{ job.failure.retryable ? '可重试故障' : '任务终止' }}</strong>
      <p>{{ job.failure.summary }}</p>
      <small>{{ job.failure.code }}</small>
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
