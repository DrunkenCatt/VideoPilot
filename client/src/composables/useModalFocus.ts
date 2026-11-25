import { nextTick, onBeforeUnmount, watch, type Ref } from 'vue'

const focusableSelector = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

export function useModalFocus(
  open: Readonly<Ref<boolean>>,
  container: Readonly<Ref<HTMLElement | null>>,
  close: () => void | Promise<void>,
) {
  let returnTarget: HTMLElement | null = null

  function focusableElements(): HTMLElement[] {
    return container.value
      ? [...container.value.querySelectorAll<HTMLElement>(focusableSelector)].filter((element) => !element.hidden)
      : []
  }

  function restoreFocus() {
    if (returnTarget?.isConnected) returnTarget.focus()
    returnTarget = null
  }

  watch(open, async (isOpen) => {
    if (isOpen) {
      returnTarget = document.activeElement instanceof HTMLElement ? document.activeElement : null
      await nextTick()
      focusableElements()[0]?.focus()
      return
    }
    await nextTick()
    restoreFocus()
  }, { immediate: true })

  function handleKeydown(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      event.preventDefault()
      void close()
      return
    }
    if (event.key !== 'Tab') return

    const elements = focusableElements()
    if (elements.length === 0) {
      event.preventDefault()
      container.value?.focus()
      return
    }
    const first = elements[0]
    const last = elements.at(-1) ?? first
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  onBeforeUnmount(restoreFocus)

  return { handleKeydown }
}
