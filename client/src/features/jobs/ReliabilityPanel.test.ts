import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { ReliabilitySummary } from '../../api/contracts'
import ReliabilityPanel from './ReliabilityPanel.vue'

const summary: ReliabilitySummary = {
  windowStart: '2026-11-06T12:00:00Z', windowEnd: '2026-11-07T12:00:00Z',
  pendingOutbox: 2, queueBacklog: null,
  failureCounts: [{ code: 'RETRYABLE', count: 3 }],
  stageDurations: [{ stage: 'INDEXING', p50Ms: 800, p95Ms: 2_500 }],
  providerCalls: [{ provider: 'SiliconFlow', totalCalls: 5, failedCalls: 1,
    inputUnits: 900, outputUnits: 30, estimatedCost: null, currency: null }],
  degradedSources: ['RABBITMQ'], generatedAt: '2026-11-07T12:00:01Z',
}

describe('ReliabilityPanel', () => {
  it('renders backlog degradation latency and unknown price without inventing zero', () => {
    const wrapper = mount(ReliabilityPanel, { props: { summary, loading: false, error: '' } })

    expect(wrapper.text()).toContain('QUEUE 积压')
    expect(wrapper.text()).toContain('不可用')
    expect(wrapper.text()).toContain('RABBITMQ')
    expect(wrapper.text()).toContain('P95 2.5 s')
    expect(wrapper.text()).toContain('价格未知')
    expect(wrapper.text()).not.toContain('价格 0')
  })

  it('exposes a keyboard-operable read-only refresh action', async () => {
    const wrapper = mount(ReliabilityPanel, { props: { summary, loading: false, error: '' } })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })
})
