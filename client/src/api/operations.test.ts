import { describe, expect, it, vi } from 'vitest'

import type { ReliabilitySummary } from './contracts'
import { createOperationsApi } from './operations'

const summary: ReliabilitySummary = {
  windowStart: '2026-11-06T12:00:00Z',
  windowEnd: '2026-11-07T12:00:00Z',
  pendingOutbox: 2,
  queueBacklog: null,
  failureCounts: [{ code: 'RETRYABLE', count: 1 }],
  stageDurations: [{ stage: 'INDEXING', p50Ms: 100, p95Ms: 250 }],
  providerCalls: [{
    provider: 'SiliconFlow', totalCalls: 3, failedCalls: 1,
    inputUnits: 500, outputUnits: 20, estimatedCost: null, currency: null,
  }],
  degradedSources: ['RABBITMQ'],
  generatedAt: '2026-11-07T12:00:01Z',
}

describe('operations API', () => {
  it('reads the fixed reliability path without owner or mutation fields', async () => {
    const request = vi.fn().mockResolvedValue(new Response(JSON.stringify(summary), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))

    await expect(createOperationsApi(request).get()).resolves.toEqual(summary)
    expect(request).toHaveBeenCalledWith('/api/v1/operations/reliability', expect.objectContaining({
      method: 'GET',
      credentials: 'same-origin',
    }))
    expect(JSON.stringify(request.mock.calls)).not.toContain('ownerId')
  })
})
