import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'

import type { Session } from '../api/contracts'
import { appRoutes } from '../router'
import AppShell from './AppShell.vue'

const session: Session = {
  userId: 'f97a9f68-b14c-412c-a0f2-1ad20c62f769' as Session['userId'],
  username: 'teacher',
  csrfToken: 'csrf-for-shell',
  expiresAt: '2026-10-30T08:00:00Z',
}

async function mountShell(path = '/workspace') {
  const router = createRouter({ history: createMemoryHistory(), routes: appRoutes })
  await router.push(path)
  await router.isReady()
  return mount(AppShell, {
    props: { session, pageTitle: '视频工作台' },
    slots: { default: '<main><h1>真实内容</h1></main>' },
    global: { plugins: [router] },
  })
}

describe('AppShell', () => {
  it('renders the approved navigation and marks the current page', async () => {
    const wrapper = await mountShell('/workspace')

    expect(wrapper.get('[aria-label="主导航"]').text()).toContain('证据问答')
    expect(wrapper.get('[aria-current="page"]').text()).toContain('视频工作台')
    expect(wrapper.get('a[href="#main-content"]').text()).toBe('跳到主要内容')
    expect(wrapper.text()).toContain('teacher')
    expect(wrapper.get('.system-status').text()).toBe('本地工作区')
    expect(wrapper.get('h1').text()).toBe('真实内容')
  })

  it('opens and closes the navigation drawer for narrow screens', async () => {
    const wrapper = await mountShell()

    expect(wrapper.get('[data-test="app-sidebar"]').attributes('data-open')).toBe('false')
    await wrapper.get('[data-test="menu-toggle"]').trigger('click')
    expect(wrapper.get('[data-test="app-sidebar"]').attributes('data-open')).toBe('true')
    await wrapper.get('[data-test="sidebar-backdrop"]').trigger('click')
    expect(wrapper.get('[data-test="app-sidebar"]').attributes('data-open')).toBe('false')
  })

  it('closes the navigation drawer after a route change', async () => {
    const wrapper = await mountShell()

    await wrapper.get('[data-test="menu-toggle"]').trigger('click')
    await wrapper.get('a[href="/evaluation"]').trigger('click')

    expect(wrapper.get('[data-test="app-sidebar"]').attributes('data-open')).toBe('false')
  })

  it('shows a login action without exposing account data for an empty session', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: appRoutes })
    await router.push('/workspace')
    await router.isReady()
    const wrapper = mount(AppShell, {
      props: { session: null, pageTitle: '视频工作台', contentBlocked: true },
      slots: { default: '<p data-test="public-shell-copy">请登录后继续</p>' },
      global: { plugins: [router] },
    })

    expect(wrapper.get('[data-test="login-action"]').text()).toBe('登录')
    expect(wrapper.find('.sidebar-account__avatar').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('teacher')
    expect(wrapper.get('[data-test="shell-content"]').attributes('inert')).toBeDefined()
  })
})
