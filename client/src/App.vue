<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'

import type { CitedEvidence, LoginRequest, Session } from './api/contracts'
import { ApiClientError, createSessionApi } from './api/session'
import LoginDialog from './features/session/LoginDialog.vue'
import AppShell from './layout/AppShell.vue'

const sessionApi = createSessionApi()
const session = ref<Session | null>(null)
const status = ref<'loading' | 'ready' | 'submitting'>('loading')
const errorMessage = ref('')
const sessionEpoch = ref(0)
const route = useRoute()
const router = useRouter()
const pageTitle = computed(() => String(route.meta.title ?? '视频工作台'))
const citationSeekTarget = computed<Pick<CitedEvidence, 'segmentId' | 'startMs' | 'endMs'> | null>(() => {
  if (route.name !== 'context') return null
  const segmentId = typeof route.query.segmentId === 'string' ? route.query.segmentId : ''
  const startMs = typeof route.query.fromMs === 'string' ? Number(route.query.fromMs) : Number.NaN
  const endMs = typeof route.query.toMs === 'string' ? Number(route.query.toMs) : Number.NaN
  if (!segmentId || !Number.isSafeInteger(startMs) || !Number.isSafeInteger(endMs) || startMs < 0 || endMs < startMs) {
    return null
  }
  return { segmentId: segmentId as CitedEvidence['segmentId'], startMs, endMs }
})

function messageFor(error: unknown): string {
  if (!(error instanceof ApiClientError)) return '无法连接本地服务，请确认后端已启动。'
  if (error.code === 'INVALID_CREDENTIALS') return '账号或密码不正确，请重新输入。'
  if (error.code === 'RATE_LIMITED') return '尝试次数过多，请稍后再试。'
  if (error.code === 'DEPENDENCY_UNAVAILABLE') return '登录服务暂时不可用，请稍后重试。'
  return '请求未完成，请稍后重试。'
}

onMounted(async () => {
  try {
    session.value = await sessionApi.current()
  } catch (error) {
    errorMessage.value = messageFor(error)
  } finally {
    status.value = 'ready'
  }
})

async function login(credentials: LoginRequest) {
  errorMessage.value = ''
  status.value = 'submitting'
  try {
    session.value = await sessionApi.login(credentials)
  } catch (error) {
    errorMessage.value = messageFor(error)
  } finally {
    status.value = 'ready'
  }
}

async function logout() {
  if (!session.value) return
  errorMessage.value = ''
  status.value = 'submitting'
  try {
    await sessionApi.logout(session.value.csrfToken)
    session.value = null
    sessionEpoch.value += 1
  } catch (error) {
    errorMessage.value = messageFor(error)
  } finally {
    status.value = 'ready'
  }
}

function sessionExpired() {
  session.value = null
  sessionEpoch.value += 1
  errorMessage.value = '会话已过期，请重新登录。'
}

async function selectJob(jobId: string) {
  const routeName = String(route.name ?? '')
  if (routeName === 'analysis' || routeName === 'analysis-detail') {
    await router.push({ name: 'analysis-detail', params: { jobId } })
    return
  }
  const name = ['workspace', 'context', 'answers'].includes(routeName) ? routeName : 'workspace'
  await router.push({ name, params: { jobId } })
}
</script>

<template>
  <div class="app-frame">
    <main
      v-if="status === 'loading'"
      class="loading-shell"
      aria-live="polite"
    >
      <span
        class="loading-mark"
        aria-hidden="true"
      />
      <p>正在恢复本地会话…</p>
    </main>

    <AppShell
      v-else
      :session="session"
      :page-title="pageTitle"
      :busy="status === 'submitting'"
      :content-blocked="!session"
      @logout="logout"
      @unauthenticated="sessionExpired"
    >
      <template v-if="session">
        <p
          v-if="errorMessage"
          class="form-error app-alert"
          role="alert"
        >
          {{ errorMessage }}
        </p>
        <RouterView v-slot="{ Component }">
          <component
            :is="Component"
            :key="`${session.userId}:${sessionEpoch}`"
            :session="session"
            :job-id="typeof route.params.jobId === 'string' ? route.params.jobId : undefined"
            :seek-target="citationSeekTarget"
            @select-job="selectJob"
            @unauthenticated="sessionExpired"
            @logout="logout"
          />
        </RouterView>
      </template>
      <section
        v-else
        class="locked-workspace"
        aria-labelledby="locked-workspace-title"
      >
        <p class="section-index">
          LOCAL WORKSPACE
        </p>
        <h2 id="locked-workspace-title">
          继续你的视频工作区
        </h2>
        <p>登录后恢复当前页面。未验证时不会加载媒体、任务或问答数据。</p>
      </section>
    </AppShell>

    <LoginDialog
      v-if="status !== 'loading' && !session"
      :busy="status === 'submitting'"
      :error-message="errorMessage"
      @submit="login"
    />
  </div>
</template>
