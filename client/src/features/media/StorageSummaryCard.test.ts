import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import { ApiClientError } from '../../api/session'
import StorageSummaryCard from './StorageSummaryCard.vue'

describe('StorageSummaryCard', () => {
  it('renders owner usage against a configured capacity with an accessible meter', async () => {
    const storageSummary = vi.fn().mockResolvedValue({
      usedBytes: 3_221_225_472,
      capacityBytes: 32_212_254_720,
      mediaCount: 3,
    })
    const wrapper = mount(StorageSummaryCard, { props: { api: { storageSummary } } })
    await flushPromises()

    expect(wrapper.get('[data-test="storage-usage"]').text()).toContain('3 GiB / 30 GiB')
    expect(wrapper.get('[role="meter"]').attributes()).toMatchObject({
      'aria-valuenow': '10',
      'aria-valuemin': '0',
      'aria-valuemax': '100',
    })
    expect(wrapper.text()).toContain('3 个视频')
  })

  it('shows an explicit unconfigured state without inventing a percentage', async () => {
    const storageSummary = vi.fn().mockResolvedValue({
      usedBytes: 0,
      capacityBytes: null,
      mediaCount: 0,
    })
    const wrapper = mount(StorageSummaryCard, { props: { api: { storageSummary } } })
    await flushPromises()

    expect(wrapper.get('[data-test="storage-usage"]').text()).toContain('0 B')
    expect(wrapper.text()).toContain('容量未配置')
    expect(wrapper.find('[role="meter"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('%')
  })

  it('offers retry after a transient failure and recovers with the real summary', async () => {
    const storageSummary = vi.fn()
      .mockRejectedValueOnce(new ApiClientError(503, { retryable: true }))
      .mockResolvedValueOnce({ usedBytes: 1024, capacityBytes: null, mediaCount: 1 })
    const wrapper = mount(StorageSummaryCard, { props: { api: { storageSummary } } })
    await flushPromises()

    expect(wrapper.text()).toContain('存储信息暂不可用')
    await wrapper.get('[data-test="storage-retry"]').trigger('click')
    await flushPromises()

    expect(storageSummary).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-test="storage-usage"]').text()).toContain('1 KiB')
  })

  it('notifies the shell when the session has expired', async () => {
    const storageSummary = vi.fn().mockRejectedValue(new ApiClientError(401, {
      code: 'AUTHENTICATION_REQUIRED',
    }))
    const wrapper = mount(StorageSummaryCard, { props: { api: { storageSummary } } })
    await flushPromises()

    expect(wrapper.emitted('unauthenticated')).toHaveLength(1)
  })
})
