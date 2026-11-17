import { mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { describe, expect, it } from 'vitest'

import { useModalFocus } from './useModalFocus'

const Harness = defineComponent({
  props: {
    initialOpen: { type: Boolean, default: false },
  },
  setup(props) {
    const open = ref(props.initialOpen)
    const dialog = ref<HTMLElement | null>(null)
    const close = () => { open.value = false }
    const { handleKeydown } = useModalFocus(open, dialog, close)
    return { open, dialog, handleKeydown }
  },
  template: `
    <button data-test="trigger" @click="open = true">打开</button>
    <section v-if="open" ref="dialog" role="dialog" @keydown="handleKeydown">
      <button data-test="first">第一个</button>
      <button data-test="last">最后一个</button>
    </section>
  `,
})

describe('useModalFocus', () => {
  it('moves focus inside, traps Tab at the edges and restores the trigger on Escape', async () => {
    const wrapper = mount(Harness, { attachTo: document.body })
    const trigger = wrapper.get<HTMLButtonElement>('[data-test="trigger"]')
    trigger.element.focus()

    await trigger.trigger('click')
    expect(document.activeElement).toBe(wrapper.get('[data-test="first"]').element)

    await wrapper.get('[data-test="first"]').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(wrapper.get('[data-test="last"]').element)

    await wrapper.get('[data-test="last"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(document.activeElement).toBe(trigger.element)
    wrapper.unmount()
  })

  it('focuses an initially open dialog after mount', async () => {
    const wrapper = mount(Harness, { attachTo: document.body, props: { initialOpen: true } })

    await wrapper.vm.$nextTick()
    expect(document.activeElement).toBe(wrapper.get('[data-test="first"]').element)
    wrapper.unmount()
  })
})
