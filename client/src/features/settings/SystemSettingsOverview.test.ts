import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { Session } from '../../api/contracts'
import SystemSettingsOverview from './SystemSettingsOverview.vue'

const session: Session = {
  userId: 'f97a9f68-b14c-412c-a0f2-1ad20c62f769' as Session['userId'],
  username: 'teacher',
  csrfToken: 'csrf-for-settings-test',
  expiresAt: '2026-10-30T08:00:00Z',
}

describe('SystemSettingsOverview', () => {
  beforeEach(() => localStorage.clear())

  it('shows safe session boundaries without exposing credentials', () => {
    const wrapper = mount(SystemSettingsOverview, { props: { session } })

    expect(wrapper.get('h1').text()).toBe('系统设置')
    expect(wrapper.text()).toContain('teacher')
    expect(wrapper.text()).toContain('HttpOnly Cookie')
    expect(wrapper.get('[data-test="settings-product-grid"]')).toBeTruthy()
    expect(wrapper.findAll('[data-test="settings-section"]')).toHaveLength(4)
    expect(wrapper.get('[data-test="infrastructure-settings"]').text()).toContain('未提供浏览器健康合同')
    expect(wrapper.get('[data-test="model-settings"]').text()).toContain('SiliconFlow')
    expect(wrapper.text()).not.toContain(session.csrfToken)
    expect(wrapper.text()).not.toContain('API Key')
  })

  it('exposes account logout as an explicit parent-owned action', async () => {
    const wrapper = mount(SystemSettingsOverview, { props: { session } })

    await wrapper.get('[data-test="settings-logout"]').trigger('click')

    expect(wrapper.emitted('logout')).toHaveLength(1)
  })

  it('restores and saves the owner-scoped density preference', async () => {
    localStorage.setItem(`videopilot:ui-preferences:${session.userId}`, '{"density":"compact"}')
    const wrapper = mount(SystemSettingsOverview, { props: { session } })

    expect(wrapper.get('main').attributes('data-density')).toBe('compact')
    await wrapper.get('input[value="comfortable"]').setValue()

    expect(localStorage.getItem(`videopilot:ui-preferences:${session.userId}`))
      .toBe('{"density":"comfortable"}')
    expect(wrapper.get('[data-test="preference-status"]').text()).toContain('已保存')
  })

  it('keeps the preview usable and offers retry when local saving fails', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    const wrapper = mount(SystemSettingsOverview, { props: { session } })

    await wrapper.get('input[value="compact"]').setValue()

    expect(wrapper.get('main').attributes('data-density')).toBe('compact')
    expect(wrapper.get('[role="alert"]').text()).toContain('无法保存')
    expect(wrapper.get('[data-test="retry-preference"]').text()).toContain('重试')
    setItem.mockRestore()
  })
})
