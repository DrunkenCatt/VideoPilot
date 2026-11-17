import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import App from './App.vue'
import type { Session } from './api/contracts'
import { uiPreferencesKey } from './features/settings/uiPreferences'
import { appRoutes } from './router'

const activeSession: Session = {
  userId: 'f97a9f68-b14c-412c-a0f2-1ad20c62f769' as Session['userId'],
  username: 'teacher',
  csrfToken: 'csrf-for-test',
  expiresAt: '2026-10-30T08:00:00Z',
}

function jsonResponse(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

async function mountApp(path = '/workspace') {
  const router = createRouter({ history: createMemoryHistory(), routes: appRoutes })
  await router.push(path)
  await router.isReady()
  return mount(App, {
    global: {
      plugins: [router],
      stubs: {
        VideoWorkspacePage: {
          props: ['session'],
          emits: ['unauthenticated', 'select-job'],
          template: '<main><h1>假任务工作台</h1><button data-test="expire" @click="$emit(\'unauthenticated\')">模拟过期</button><button data-test="select-job" @click="$emit(\'select-job\', \'10000000-0000-0000-0000-000000000099\')">切换任务</button></main>',
        },
        EvaluationWorkbench: {
          props: ['session'],
          emits: ['unauthenticated'],
          template: '<main><h1>假评测工作台</h1></main>',
        },
        VideoContextPage: {
          props: ['jobId', 'seekTarget'],
          template: '<main><h1>假视频上下文</h1><p data-test="context-route-props">{{ jobId }} · {{ seekTarget?.segmentId }} · {{ seekTarget?.startMs }} · {{ seekTarget?.endMs }}</p></main>',
        },
        WorkspaceSwitcher: { template: '<button data-test="workspace-switcher-stub">切换视频</button>' },
        StorageSummaryCard: { template: '<section data-test="storage-summary-stub">本地存储</section>' },
      },
    },
  })
}

describe('App session flow', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('shows the login surface when there is no active session', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(401)))

    const wrapper = await mountApp()
    await flushPromises()

    expect(wrapper.get('#login-dialog-title').text()).toBe('登录工作区')
    expect(wrapper.get('label[for="username"]').text()).toContain('账号')
    expect(wrapper.get('label[for="password"]').text()).toContain('密码')
    expect(wrapper.get('button[type="submit"]').text()).toContain('登录')
    expect(wrapper.get('[data-test="shell-content"]').attributes('inert')).toBeDefined()
    expect(wrapper.text()).not.toContain('假任务工作台')
  })

  it('logs in against the session API and opens the authenticated workspace', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401))
      .mockResolvedValueOnce(jsonResponse(201, activeSession))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountApp()
    await flushPromises()
    await wrapper.get('#username').setValue('teacher')
    await wrapper.get('#password').setValue('a local password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/sessions',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ username: 'teacher', password: 'a local password' }),
        credentials: 'same-origin',
      }),
    )
    expect(wrapper.get('.sidebar-account').text()).toContain('teacher')
    expect(wrapper.get('h1').text()).toContain('假任务工作台')
    expect(wrapper.text()).not.toContain('a local password')
  })

  it('switches between video and evaluation without creating another session boundary', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, activeSession)))
    const wrapper = await mountApp()
    await flushPromises()
    await wrapper.get('a[href="/evaluation"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('h1').text()).toBe('假评测工作台')
    expect(wrapper.text()).toContain('teacher')
  })

  it('keeps the original target route after login', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401))
      .mockResolvedValueOnce(jsonResponse(201, activeSession))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountApp('/evaluation')
    await flushPromises()
    await wrapper.get('#username').setValue('teacher')
    await wrapper.get('#password').setValue('password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('假评测工作台')
    expect(wrapper.get('[aria-current="page"]').text()).toContain('评测中心')
  })

  it('keeps the workspace page when the workbench selects another job', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, activeSession)))
    const wrapper = await mountApp('/workspace/10000000-0000-0000-0000-000000000008')
    await flushPromises()

    await wrapper.get('[data-test="select-job"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('a[href="/context/10000000-0000-0000-0000-000000000099"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/answers/10000000-0000-0000-0000-000000000099"]').exists()).toBe(true)
  })

  it('remembers the selected workspace job after visiting a non-job page', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, activeSession)))
    const wrapper = await mountApp('/workspace/10000000-0000-0000-0000-000000000008')
    await flushPromises()

    await wrapper.get('[data-test="select-job"]').trigger('click')
    await flushPromises()
    await wrapper.get('a[href="/library"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('a[href="/context/10000000-0000-0000-0000-000000000099"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/answers/10000000-0000-0000-0000-000000000099"]').exists()).toBe(true)
  })

  it('restores a valid citation seek target from the context URL', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, activeSession)))
    const wrapper = await mountApp('/context/10000000-0000-0000-0000-000000000008?segmentId=40000000-0000-0000-0000-000000000008&fromMs=72000&toMs=89000')
    await flushPromises()

    expect(wrapper.get('[data-test="context-route-props"]').text()).toContain('40000000-0000-0000-0000-000000000008 · 72000 · 89000')
  })

  it('uses a generic message and clears the password after invalid credentials', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401))
      .mockResolvedValueOnce(
        jsonResponse(401, {
          code: 'INVALID_CREDENTIALS',
          message: 'The credentials are invalid.',
          retryable: false,
          requestId: 'request-1',
        }),
      )
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountApp()
    await flushPromises()
    await wrapper.get('#username').setValue('teacher')
    await wrapper.get('#password').setValue('wrong password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('账号或密码不正确')
    expect((wrapper.get('#username').element as HTMLInputElement).value).toBe('teacher')
    expect((wrapper.get('#password').element as HTMLInputElement).value).toBe('')
  })

  it.each([
    ['RATE_LIMITED', '尝试次数过多'],
    ['DEPENDENCY_UNAVAILABLE', '登录服务暂时不可用'],
    ['INTERNAL_ERROR', '请求未完成'],
  ])('maps the %s login error to a safe message', async (code, expectedMessage) => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401))
      .mockResolvedValueOnce(
        jsonResponse(code === 'RATE_LIMITED' ? 429 : 503, {
          code,
          message: 'Internal public message.',
          retryable: code !== 'INTERNAL_ERROR',
          requestId: 'request-2',
        }),
      )
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountApp()
    await flushPromises()
    await wrapper.get('#username').setValue('teacher')
    await wrapper.get('#password').setValue('password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain(expectedMessage)
  })

  it('shows a connection message when session restoration cannot reach the server', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('connection failed')))

    const wrapper = await mountApp()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('无法连接本地服务')
  })

  it('restores and closes a session without exposing the cookie', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, activeSession))
      .mockResolvedValueOnce(jsonResponse(204))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountApp()
    await flushPromises()
    localStorage.setItem(uiPreferencesKey(activeSession.userId), '{"density":"compact"}')
    expect(wrapper.get('.sidebar-account').text()).toContain('teacher')

    await wrapper.get('[data-test="account-menu"]').trigger('click')
    await wrapper.get('[data-test="logout"]').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenLastCalledWith(
      '/api/v1/sessions/current',
      expect.objectContaining({
        method: 'DELETE',
        credentials: 'same-origin',
        headers: { 'X-CSRF-Token': 'csrf-for-test' },
      }),
    )
    expect(wrapper.get('button[type="submit"]').text()).toContain('登录')
    expect(localStorage.getItem(uiPreferencesKey(activeSession.userId))).toBeNull()
    expect(wrapper.text()).not.toContain('假任务工作台')
  })

  it('blocks the old page state and explains an expired session', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, activeSession)))
    const wrapper = await mountApp('/workspace/example-job')
    await flushPromises()

    await wrapper.get('[data-test="expire"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(wrapper.get('[role="alert"]').text()).toContain('会话已过期')
    expect(wrapper.text()).not.toContain('假任务工作台')
    expect(wrapper.get('[data-test="shell-content"]').attributes('inert')).toBeDefined()
  })

  it('keeps the authenticated workspace when logout fails', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, activeSession))
      .mockResolvedValueOnce(
        jsonResponse(503, {
          code: 'DEPENDENCY_UNAVAILABLE',
          message: 'A required dependency is unavailable.',
          retryable: true,
          requestId: 'request-3',
        }),
      )
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountApp()
    await flushPromises()
    await wrapper.get('[data-test="account-menu"]').trigger('click')
    await wrapper.get('[data-test="logout"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('.sidebar-account').text()).toContain('teacher')
    expect(wrapper.get('[role="alert"]').text()).toContain('登录服务暂时不可用')
  })
})

describe('application bootstrap', () => {
  beforeEach(() => {
    document.body.innerHTML = '<div id="app"></div>'
    vi.resetModules()
    vi.stubGlobal('scrollTo', vi.fn())
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(401)))
  })

  it('mounts the application into the root element', async () => {
    await import('./main')
    await flushPromises()

    expect(document.querySelector('#app')?.textContent).toContain('VIDEOPILOT')
  })
})
