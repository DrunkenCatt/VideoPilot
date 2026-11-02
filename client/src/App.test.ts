import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import App from './App.vue'
import type { Session } from './api/contracts'

const activeSession: Session = {
  userId: 'f97a9f68-b14c-412c-a0f2-1ad20c62f769' as Session['userId'],
  username: 'teacher',
  csrfToken: 'csrf-for-test',
  expiresAt: '2026-10-30T08:00:00Z',
}

const emptyJobPage = {
  data: [],
  pagination: { page: 1, pageSize: 20, totalItems: 0, totalPages: 0 },
}

function jsonResponse(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('App session flow', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the login surface when there is no active session', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(401)))

    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('回到视频，也回到证据。')
    expect(wrapper.get('label[for="username"]').text()).toContain('账号')
    expect(wrapper.get('label[for="password"]').text()).toContain('密码')
    expect(wrapper.get('button[type="submit"]').text()).toContain('进入工作台')
  })

  it('logs in against the session API and opens the authenticated workspace', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401))
      .mockResolvedValueOnce(jsonResponse(201, activeSession))
      .mockResolvedValueOnce(jsonResponse(200, emptyJobPage))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(App)
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
    expect(wrapper.get('.session-actions').text()).toContain('teacher')
    expect(wrapper.get('h1').text()).toContain('假任务工作台')
    expect(wrapper.text()).not.toContain('a local password')
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

    const wrapper = mount(App)
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

    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('#username').setValue('teacher')
    await wrapper.get('#password').setValue('password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain(expectedMessage)
  })

  it('shows a connection message when session restoration cannot reach the server', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('connection failed')))

    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('无法连接本地服务')
  })

  it('restores and closes a session without exposing the cookie', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, activeSession))
      .mockResolvedValueOnce(jsonResponse(200, emptyJobPage))
      .mockResolvedValueOnce(jsonResponse(204))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.get('.session-actions').text()).toContain('teacher')

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
    expect(wrapper.get('button[type="submit"]').text()).toContain('进入工作台')
  })

  it('keeps the authenticated workspace when logout fails', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, activeSession))
      .mockResolvedValueOnce(jsonResponse(200, emptyJobPage))
      .mockResolvedValueOnce(
        jsonResponse(503, {
          code: 'DEPENDENCY_UNAVAILABLE',
          message: 'A required dependency is unavailable.',
          retryable: true,
          requestId: 'request-3',
        }),
      )
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('[data-test="logout"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('.session-actions').text()).toContain('teacher')
    expect(wrapper.get('[role="alert"]').text()).toContain('登录服务暂时不可用')
  })
})

describe('application bootstrap', () => {
  beforeEach(() => {
    document.body.innerHTML = '<div id="app"></div>'
    vi.resetModules()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(401)))
  })

  it('mounts the application into the root element', async () => {
    await import('./main')
    await flushPromises()

    expect(document.querySelector('#app')?.textContent).toContain('VIDEOPILOT')
  })
})
