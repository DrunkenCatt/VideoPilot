<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'

import type { LoginRequest } from '../../api/contracts'

const props = defineProps<{
  busy: boolean
  errorMessage: string
}>()

const emit = defineEmits<{
  submit: [credentials: LoginRequest]
}>()

const dialog = ref<globalThis.HTMLElement | null>(null)
const usernameInput = ref<globalThis.HTMLInputElement | null>(null)
const passwordInput = ref<globalThis.HTMLInputElement | null>(null)
const username = ref('')
const password = ref('')

function focusableElements(): globalThis.HTMLElement[] {
  if (!dialog.value) return []
  return Array.from(dialog.value.querySelectorAll<globalThis.HTMLElement>('input:not(:disabled), button:not(:disabled)'))
}

function handleKeydown(event: globalThis.KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    usernameInput.value?.focus()
    return
  }
  if (event.key !== 'Tab') return

  const elements = focusableElements()
  const first = elements[0]
  const last = elements.at(-1)
  if (!first || !last) return

  if (!elements.includes(globalThis.document.activeElement as globalThis.HTMLElement)) {
    event.preventDefault()
    ;(event.shiftKey ? last : first).focus()
    return
  }

  if (event.shiftKey && globalThis.document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && globalThis.document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

function submit() {
  if (props.busy) return
  emit('submit', { username: username.value.trim(), password: password.value })
}

onMounted(async () => {
  await nextTick()
  usernameInput.value?.focus()
})

watch(() => props.busy, async (busy, wasBusy) => {
  if (busy || !wasBusy) return
  password.value = ''
  await nextTick()
  passwordInput.value?.focus()
})
</script>

<template>
  <div class="login-modal-backdrop">
    <section
      ref="dialog"
      class="login-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="login-dialog-title"
      aria-describedby="login-dialog-description"
      @keydown="handleKeydown"
    >
      <header>
        <span
          class="login-dialog__mark"
          aria-hidden="true"
        >▶</span>
        <div>
          <p class="section-index">
            VIDEOPILOT / LOCAL
          </p>
          <h1 id="login-dialog-title">
            登录工作区
          </h1>
        </div>
      </header>
      <p id="login-dialog-description">
        使用管理员为本机配置的账号。登录后将回到当前页面。
      </p>

      <form @submit.prevent="submit">
        <label for="username">
          <span>账号</span>
          <input
            id="username"
            ref="usernameInput"
            v-model="username"
            name="username"
            type="text"
            autocomplete="username"
            maxlength="100"
            required
          >
        </label>
        <label for="password">
          <span>密码</span>
          <input
            id="password"
            ref="passwordInput"
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
          class="login-dialog__submit"
          type="submit"
          :disabled="busy"
        >
          {{ busy ? '正在验证…' : '登录' }}
        </button>
      </form>
      <p class="security-note">
        会话 Cookie 仅由浏览器发送；页面不保存密码。
      </p>
    </section>
  </div>
</template>
