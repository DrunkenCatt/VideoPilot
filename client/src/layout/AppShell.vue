<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import type { Session } from '../api/contracts'
import WorkspaceSwitcher from '../features/workspace/WorkspaceSwitcher.vue'
import { navigationItems } from '../router'

withDefaults(defineProps<{
  session: Session | null
  pageTitle: string
  busy?: boolean
  contentBlocked?: boolean
}>(), {
  busy: false,
  contentBlocked: false,
})

defineEmits<{ logout: []; login: []; unauthenticated: [] }>()

const route = useRoute()
const drawerOpen = ref(false)
const accountMenuOpen = ref(false)
const activeName = computed(() => String(route.meta.navigationName ?? route.name ?? ''))
const currentJobId = computed(() => typeof route.params.jobId === 'string' ? route.params.jobId : undefined)

function navigationTarget(name: string) {
  if (currentJobId.value && ['workspace', 'context', 'answers'].includes(name)) {
    return { name, params: { jobId: currentJobId.value } }
  }
  return { name }
}

watch(() => route.fullPath, () => {
  drawerOpen.value = false
  accountMenuOpen.value = false
})
</script>

<template>
  <div class="app-shell">
    <a
      class="skip-link"
      href="#main-content"
    >跳到主要内容</a>
    <button
      v-if="drawerOpen"
      class="sidebar-backdrop"
      data-test="sidebar-backdrop"
      type="button"
      aria-label="关闭主导航"
      @click="drawerOpen = false"
    />

    <aside
      id="app-sidebar"
      class="app-sidebar"
      data-test="app-sidebar"
      :data-open="String(drawerOpen)"
    >
      <RouterLink
        class="app-brand"
        :to="navigationTarget('workspace')"
        aria-label="VideoPilot 视频工作台"
      >
        <span
          class="app-brand__mark"
          aria-hidden="true"
        >▶</span>
        <span><strong>VideoPilot</strong><small>Evidence Studio</small></span>
      </RouterLink>

      <nav
        class="primary-navigation"
        aria-label="主导航"
      >
        <p>工作空间</p>
        <RouterLink
          v-for="item in navigationItems.slice(0, 5)"
          :key="item.name"
          :to="navigationTarget(item.name)"
          :aria-current="activeName === item.name ? 'page' : undefined"
          @click="drawerOpen = false"
        >
          <span aria-hidden="true">{{ item.index }}</span>{{ item.label }}
        </RouterLink>
        <p>质量与系统</p>
        <RouterLink
          v-for="item in navigationItems.slice(5)"
          :key="item.name"
          :to="navigationTarget(item.name)"
          :aria-current="activeName === item.name ? 'page' : undefined"
          @click="drawerOpen = false"
        >
          <span aria-hidden="true">{{ item.index }}</span>{{ item.label }}
        </RouterLink>
      </nav>

      <div
        v-if="session"
        class="sidebar-account"
      >
        <span
          class="sidebar-account__avatar"
          aria-hidden="true"
        >
          {{ session.username.slice(0, 2).toUpperCase() }}
        </span>
        <span><strong>{{ session.username }}</strong><small>本地工作区</small></span>
        <button
          type="button"
          data-test="account-menu"
          :aria-expanded="accountMenuOpen"
          aria-controls="account-popover"
          @click="accountMenuOpen = !accountMenuOpen"
        >
          账户
        </button>
        <div
          v-if="accountMenuOpen"
          id="account-popover"
          class="account-popover"
          role="menu"
        >
          <p><strong>{{ session.username }}</strong><span>已登录本地工作区</span></p>
          <button
            type="button"
            role="menuitem"
            data-test="logout"
            :disabled="busy"
            @click="$emit('logout')"
          >
            {{ busy ? '正在退出…' : '退出登录' }}
          </button>
        </div>
      </div>
      <div
        v-else
        class="sidebar-login"
      >
        <p><strong>未登录</strong><span>登录后访问本地视频资源</span></p>
        <button
          type="button"
          data-test="login-action"
          @click="$emit('login')"
        >
          登录
        </button>
      </div>
    </aside>

    <div class="app-main">
      <header class="app-toolbar">
        <button
          class="menu-toggle"
          data-test="menu-toggle"
          type="button"
          :aria-expanded="drawerOpen"
          aria-controls="app-sidebar"
          aria-label="打开主导航"
          @click="drawerOpen = !drawerOpen"
        >
          <span aria-hidden="true" />
          <span aria-hidden="true" />
        </button>
        <div>
          <p>VideoPilot / {{ pageTitle }}</p>
          <strong>{{ pageTitle }}</strong>
        </div>
        <div class="app-toolbar__actions">
          <WorkspaceSwitcher
            v-if="session"
            @unauthenticated="$emit('unauthenticated')"
          />
          <span class="system-status"><i aria-hidden="true" />本地工作区</span>
        </div>
      </header>

      <div
        id="main-content"
        class="app-content"
        data-test="shell-content"
        tabindex="-1"
        :inert="contentBlocked ? true : undefined"
        :aria-hidden="contentBlocked ? 'true' : undefined"
      >
        <slot />
      </div>

      <footer class="app-footer">
        <span>VideoPilot / Local Workspace</span>
        <span>Evidence-bound</span>
      </footer>
    </div>
  </div>
</template>
