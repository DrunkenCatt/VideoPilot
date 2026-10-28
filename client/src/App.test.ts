import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import App from './App.vue'

describe('App', () => {
  it('shows the current project milestone', () => {
    const wrapper = mount(App)

    expect(wrapper.text()).toContain('M0 · 工程骨架与契约冻结')
  })
})

describe('application bootstrap', () => {
  beforeEach(() => {
    document.body.innerHTML = '<div id="app"></div>'
    vi.resetModules()
  })

  it('mounts the application into the root element', async () => {
    await import('./main')

    expect(document.querySelector('#app')?.textContent).toContain('VIDEOPILOT')
  })
})
