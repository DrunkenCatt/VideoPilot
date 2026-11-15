import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { Session } from '../../api/contracts'
import SystemSettingsOverview from './SystemSettingsOverview.vue'

const session: Session = {
  userId: 'f97a9f68-b14c-412c-a0f2-1ad20c62f769' as Session['userId'],
  username: 'teacher',
  csrfToken: 'csrf-for-settings-test',
  expiresAt: '2026-10-30T08:00:00Z',
}

describe('SystemSettingsOverview', () => {
  it('shows safe session boundaries without exposing credentials', () => {
    const wrapper = mount(SystemSettingsOverview, { props: { session } })

    expect(wrapper.get('h1').text()).toBe('系统设置')
    expect(wrapper.text()).toContain('teacher')
    expect(wrapper.text()).toContain('HttpOnly Cookie')
    expect(wrapper.text()).not.toContain(session.csrfToken)
  })
})
