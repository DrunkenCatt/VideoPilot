<script setup lang="ts">
import type { AnalysisJob, Session } from '../../api/contracts'
import AnalysisJobsPage from './AnalysisJobsPage.vue'

// Keeps unmigrated workspace, context, and answer routes stable until F6-F8.
defineProps<{
  session: Session
  jobId?: AnalysisJob['jobId'] | string
}>()
const emit = defineEmits<{ unauthenticated: []; 'select-job': [jobId: AnalysisJob['jobId']] }>()
</script>

<template>
  <AnalysisJobsPage
    :session="session"
    :job-id="jobId"
    @select-job="emit('select-job', $event)"
    @unauthenticated="emit('unauthenticated')"
  />
</template>
