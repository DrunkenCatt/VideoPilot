import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import App from './App.vue'

describe('App', () => {
  it('shows the current project milestone', () => {
    const wrapper = mount(App)

    expect(wrapper.text()).toContain('M0 · 工程骨架与契约冻结')
  })
})
