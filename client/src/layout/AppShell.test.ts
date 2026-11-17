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

async function mountShell(path = '/workspace', currentWorkspaceJobId?: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: appRoutes })
  await router.push(path)
  await router.isReady()
  return mount(AppShell, {
    props: { session, pageTitle: '视频工作台', currentWorkspaceJobId },
    slots: { default: '<main><h1>真实内容</h1></main>' },
    global: {
      plugins: [router],
      stubs: {
        WorkspaceSwitcher: { template: '<button data-test="workspace-switcher-stub">切换视频</button>' },
        StorageSummaryCard: { template: '<section data-test="storage-summary-stub">本地存储</section>' },
      },
    },
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
    expect(wrapper.find('[data-test="storage-summary-stub"]').exists()).toBe(true)
  })

  it('opens and closes the navigation drawer for narrow screens', async () => {
    const wrapper = await mountShell()
    document.body.appendChild(wrapper.element)
    const toggle = wrapper.get<HTMLButtonElement>('[data-test="menu-toggle"]')
    toggle.element.focus()

    expect(wrapper.get('[data-test="app-sidebar"]').attributes('data-open')).toBe('false')
    await toggle.trigger('click')
    expect(wrapper.get('[data-test="app-sidebar"]').attributes('data-open')).toBe('true')
    expect(document.activeElement).toBe(wrapper.get('.app-brand').element)
    await wrapper.get('[data-test="app-sidebar"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.get('[data-test="app-sidebar"]').attributes('data-open')).toBe('false')
    expect(document.activeElement).toBe(toggle.element)
    wrapper.unmount()
  })

  it('closes the navigation drawer after a route change', async () => {
    const wrapper = await mountShell()

    await wrapper.get('[data-test="menu-toggle"]').trigger('click')
    await wrapper.get('a[href="/evaluation"]').trigger('click')

    expect(wrapper.get('[data-test="app-sidebar"]').attributes('data-open')).toBe('false')
  })

  it('preserves the current job across workspace, context, and answer navigation', async () => {
    const jobId = '10000000-0000-0000-0000-000000000008'
    const wrapper = await mountShell(`/workspace/${jobId}`)

    expect(wrapper.find(`a[href="/context/${jobId}"]`).exists()).toBe(true)
    expect(wrapper.find(`a[href="/answers/${jobId}"]`).exists()).toBe(true)
  })

  it('keeps the last workspace job when navigating through a collection page', async () => {
    const jobId = '10000000-0000-0000-0000-000000000008'
    const wrapper = await mountShell('/library', jobId)

    expect(wrapper.find(`a[href="/workspace/${jobId}"]`).exists()).toBe(true)
    expect(wrapper.find(`a[href="/context/${jobId}"]`).exists()).toBe(true)
    expect(wrapper.find(`a[href="/answers/${jobId}"]`).exists()).toBe(true)
  })

  it.each([
    ['/workspace/10000000-0000-0000-0000-000000000008', true],
    ['/context/10000000-0000-0000-0000-000000000008', true],
    ['/answers/10000000-0000-0000-0000-000000000008', true],
    ['/library', false],
    ['/analysis', false],
    ['/evaluation', false],
    ['/settings', false],
  ])('only shows the video switcher on job-scoped routes: %s', async (path, expected) => {
    const wrapper = await mountShell(path)

    expect(wrapper.find('[data-test="workspace-switcher-stub"]').exists()).toBe(expected)
  })

  it.each([
    ['/library', '上传视频', '/library?action=upload'],
    ['/analysis', '新建分析', '/library'],
    ['/evaluation', '打开最近视频', '/workspace/10000000-0000-0000-0000-000000000008'],
    ['/settings', '打开最近视频', '/workspace/10000000-0000-0000-0000-000000000008'],
  ])('renders the route-specific toolbar action for %s', async (path, label, href) => {
    const wrapper = await mountShell(path, '10000000-0000-0000-0000-000000000008')
    const action = wrapper.get('[data-test="toolbar-page-action"]')

    expect(action.text()).toContain(label)
    expect(action.attributes('href')).toBe(href)
  })

  it('keeps the toolbar breadcrumb without repeating the page title', async () => {
    const wrapper = await mountShell('/library')

    expect(wrapper.get('.app-toolbar p').text()).toBe('VideoPilot / 视频工作台')
    expect(wrapper.find('.app-toolbar strong').exists()).toBe(false)
  })

  it('shows a login action without exposing account data for an empty session', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: appRoutes })
    await router.push('/workspace')
    await router.isReady()
    const wrapper = mount(AppShell, {
      props: { session: null, pageTitle: '视频工作台', contentBlocked: true },
      slots: { default: '<p data-test="public-shell-copy">请登录后继续</p>' },
      global: {
        plugins: [router],
        stubs: {
          WorkspaceSwitcher: { template: '<button>切换视频</button>' },
          StorageSummaryCard: { template: '<section data-test="storage-summary-stub">本地存储</section>' },
        },
      },
    })

    expect(wrapper.get('[data-test="login-action"]').text()).toBe('登录')
    expect(wrapper.find('.sidebar-account__avatar').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('teacher')
    expect(wrapper.get('[data-test="shell-content"]').attributes('inert')).toBeDefined()
    expect(wrapper.find('[data-test="storage-summary-stub"]').exists()).toBe(false)
  })
})
