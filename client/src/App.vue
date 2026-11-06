<script setup lang="ts">
import { onMounted, ref } from 'vue'

import type { Session } from './api/contracts'
import { ApiClientError, createSessionApi } from './api/session'
import JobWorkbench from './features/jobs/JobWorkbench.vue'

const sessionApi = createSessionApi()
const session = ref<Session | null>(null)
const username = ref('')
const password = ref('')
const status = ref<'loading' | 'ready' | 'submitting'>('loading')
const errorMessage = ref('')

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

async function login() {
  errorMessage.value = ''
  status.value = 'submitting'
  try {
    session.value = await sessionApi.login({ username: username.value, password: password.value })
    password.value = ''
  } catch (error) {
    errorMessage.value = messageFor(error)
    password.value = ''
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
  } catch (error) {
    errorMessage.value = messageFor(error)
  } finally {
    status.value = 'ready'
  }
}

function sessionExpired() {
  session.value = null
  errorMessage.value = '会话已过期，请重新登录。'
}
</script>

<template>
  <div class="app-frame">
    <header class="brand-bar">
      <a
        class="wordmark"
        href="/"
        aria-label="VideoPilot 首页"
      >VIDEOPILOT</a>
      <div
        v-if="session"
        class="session-actions"
      >
        <span>{{ session.username }}</span>
        <button
          class="text-button"
          data-test="logout"
          :disabled="status === 'submitting'"
          @click="logout"
        >
          {{ status === 'submitting' ? '正在退出…' : '退出' }}
        </button>
      </div>
      <span
        v-else
        class="environment"
      >LOCAL WORKSPACE</span>
    </header>

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

    <main
      v-else-if="!session"
      class="login-shell"
    >
      <section
        class="login-story"
        aria-labelledby="login-title"
      >
        <p class="kicker">
          教学长视频 · 可追溯工作台
        </p>
        <h1 id="login-title">
          回到视频，也回到证据。
        </h1>
        <p class="intro">
          登录后继续上传、解析与检索。每个答案最终都将关联可跳转的时间戳证据。
        </p>
        <div
          class="principle-list"
          aria-label="产品原则"
        >
          <p><strong>01</strong><span>本地优先，资源按账号隔离</span></p>
          <p><strong>02</strong><span>任务状态持久化，可恢复、可追踪</span></p>
          <p><strong>03</strong><span>结论必须回到原始视频证据</span></p>
        </div>
      </section>

      <section
        class="login-panel"
        aria-label="登录"
      >
        <div class="panel-heading">
          <p class="section-index">
            M1 / SESSION
          </p>
          <h2>进入工作台</h2>
          <p>使用管理员为本机配置的账号。</p>
        </div>
        <form @submit.prevent="login">
          <label for="username">
            <span>账号</span>
            <input
              id="username"
              v-model.trim="username"
              name="username"
              type="text"
              autocomplete="username"
              maxlength="100"
              required
              autofocus
            >
          </label>
          <label for="password">
            <span>密码</span>
            <input
              id="password"
              v-model="password"
              name="password"
              type="password"
              autocomplete="current-password"
              maxlength="256"
              required
            >
          </label>
          <p
            v-if="errorMessage"
            class="form-error"
            role="alert"
          >
            {{ errorMessage }}
          </p>
          <button
            type="submit"
            :disabled="status === 'submitting'"
          >
            <span>{{ status === 'submitting' ? '正在验证…' : '进入工作台' }}</span><span aria-hidden="true">→</span>
          </button>
        </form>
        <p class="security-note">
          会话保存在安全 Cookie 中，此页面不会保存密码。
        </p>
      </section>
    </main>

    <template v-else>
      <p
        v-if="errorMessage"
        class="form-error app-alert"
        role="alert"
      >
        {{ errorMessage }}
      </p>
      <JobWorkbench
        :session="session"
        @unauthenticated="sessionExpired"
      />
    </template>

    <footer><span>VideoPilot / M3</span><span>Local-first · Evidence-bound</span></footer>
  </div>
</template>
