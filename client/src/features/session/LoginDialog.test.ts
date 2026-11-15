import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import LoginDialog from './LoginDialog.vue'

describe('LoginDialog', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('opens as a required modal and focuses the username field', async () => {
    const wrapper = mount(LoginDialog, {
      props: { busy: false, errorMessage: '' },
      attachTo: document.body,
    })

    await vi.waitFor(() => expect(document.activeElement).toBe(wrapper.get('#username').element))
    expect(wrapper.get('[role="dialog"]').attributes('aria-modal')).toBe('true')
    expect(wrapper.get('[role="dialog"]').attributes('aria-labelledby')).toBe('login-dialog-title')
  })

  it('uses native form submission for keyboard entry and never renders the password as text', async () => {
    const wrapper = mount(LoginDialog, {
      props: { busy: false, errorMessage: '' },
    })

    await wrapper.get('#username').setValue('teacher')
    await wrapper.get('#password').setValue('local password')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('submit')?.[0]).toEqual([{ username: 'teacher', password: 'local password' }])
    expect(wrapper.text()).not.toContain('local password')
  })

  it('traps focus in both directions and cannot be dismissed with Escape', async () => {
    const wrapper = mount(LoginDialog, {
      props: { busy: false, errorMessage: '' },
      attachTo: document.body,
    })
    const username = wrapper.get('#username').element as HTMLInputElement
    const submit = wrapper.get('button[type="submit"]').element as HTMLButtonElement

    submit.focus()
    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(username)

    username.focus()
    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(submit)

    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toBeUndefined()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
  })

  it('keeps focus inside the dialog while the submit button is disabled', async () => {
    const wrapper = mount(LoginDialog, {
      props: { busy: true, errorMessage: '' },
      attachTo: document.body,
    })
    const submit = wrapper.get('button[type="submit"]').element as HTMLButtonElement
    submit.focus()

    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Tab' })

    expect(document.activeElement).toBe(wrapper.get('#username').element)
  })

  it('clears the password and keeps recovery available after an error', async () => {
    const wrapper = mount(LoginDialog, {
      props: { busy: true, errorMessage: '' },
    })
    await wrapper.get('#username').setValue('teacher')
    await wrapper.get('#password').setValue('wrong password')

    await wrapper.setProps({ busy: false, errorMessage: '账号或密码不正确，请重新输入。' })

    expect((wrapper.get('#username').element as HTMLInputElement).value).toBe('teacher')
    expect((wrapper.get('#password').element as HTMLInputElement).value).toBe('')
    expect(wrapper.get('[role="alert"]').text()).toContain('账号或密码不正确')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
  })
})
