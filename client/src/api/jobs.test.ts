import { describe, expect, it, vi } from 'vitest'

import type { AnalysisJob, JobEvent, MediaId } from './contracts'
import { createJobsApi } from './jobs'

const job: AnalysisJob = {
  jobId: '10000000-0000-0000-0000-000000000008' as AnalysisJob['jobId'],
  mediaId: '20000000-0000-0000-0000-000000000008' as MediaId,
  goal: '提取课程结构',
  mode: 'STANDARD',
  status: 'CREATED',
  currentStage: null,
  attempt: 0,
  version: 0,
  failure: null,
  createdAt: '2026-11-02T08:00:00Z',
  updatedAt: '2026-11-02T08:00:00Z',
  completedAt: null,
  links: {
    self: '/api/v1/analysis-jobs/10000000-0000-0000-0000-000000000008',
    events: '/api/v1/analysis-jobs/10000000-0000-0000-0000-000000000008/events',
    cancellations: '/api/v1/analysis-jobs/10000000-0000-0000-0000-000000000008/cancellations',
  },
}

function jsonResponse(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function eventStreamResponse(events: Array<{ id?: number; event: string; data: unknown }>): Response {
  const body = events
    .map(({ id, event, data }) => `${id === undefined ? '' : `id: ${id}\n`}event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
    .join('')
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

describe('jobs API', () => {
  it('submits one intent with its stable idempotency key and CSRF token', async () => {
    const request = vi.fn().mockResolvedValue(jsonResponse(202, job))

    await createJobsApi(request).submit(
      { mediaId: job.mediaId, goal: job.goal, mode: 'STANDARD' },
      'intent-8',
      'csrf-8',
    )

    expect(request).toHaveBeenCalledWith(
      '/api/v1/analysis-jobs',
      expect.objectContaining({
        method: 'POST',
        credentials: 'same-origin',
        headers: expect.objectContaining({
          'Idempotency-Key': 'intent-8',
          'X-CSRF-Token': 'csrf-8',
        }),
      }),
    )
  })

  it('uses server links for detail and cancellation without sending an owner id', async () => {
    const request = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, job))
      .mockResolvedValueOnce(jsonResponse(202, { ...job, status: 'CANCELED' }))
    const api = createJobsApi(request)

    await api.get(job.links.self)
    await api.cancel(job.links.cancellations, 'csrf-8')

    expect(request.mock.calls[0]?.[0]).toBe(job.links.self)
    expect(request.mock.calls[1]?.[0]).toBe(job.links.cancellations)
    expect(JSON.stringify(request.mock.calls)).not.toContain('ownerId')
  })

  it('reconnects a non-terminal stream with the last persisted event sequence', async () => {
    const queued: JobEvent = {
      jobId: job.jobId,
      sequence: 2,
      eventType: 'JOB_QUEUED',
      status: 'QUEUED',
      stage: null,
      message: 'Analysis job queued.',
      occurredAt: '2026-11-02T08:00:01Z',
      terminal: false,
    }
    const ready: JobEvent = {
      ...queued,
      sequence: 6,
      eventType: 'JOB_READY',
      status: 'READY',
      message: 'Analysis job ready.',
      terminal: true,
    }
    const request = vi
      .fn()
      .mockResolvedValueOnce(eventStreamResponse([{ id: 2, event: 'job-event', data: queued }]))
      .mockResolvedValueOnce(eventStreamResponse([{ id: 6, event: 'job-event', data: ready }]))
    const received: JobEvent[] = []

    await createJobsApi(request, async () => undefined).followEvents(job.links.events, {
      onEvent: (event) => received.push(event),
    })

    expect(received.map((event) => event.sequence)).toEqual([2, 6])
    expect(request).toHaveBeenNthCalledWith(
      2,
      job.links.events,
      expect.objectContaining({
        headers: expect.objectContaining({ 'Last-Event-ID': '2' }),
      }),
    )
  })

  it('stops reconnecting on a stable SSE authorization error', async () => {
    const request = vi.fn().mockResolvedValue(
      new Response(
        'event: error\ndata: {"code":"RESOURCE_NOT_FOUND","message":"Resource not found.","retryable":false,"requestId":"request-8"}\n\n',
        { status: 404, headers: { 'Content-Type': 'text/event-stream' } },
      ),
    )

    const result = createJobsApi(request, async () => undefined).followEvents(job.links.events, {
      onEvent: vi.fn(),
    })

    await expect(result).rejects.toMatchObject({ status: 404, code: 'RESOURCE_NOT_FOUND' })
    expect(request).toHaveBeenCalledTimes(1)
  })

  it('retries a transient connection failure without losing the initial cursor', async () => {
    const stale: JobEvent = {
      jobId: job.jobId,
      sequence: 3,
      eventType: 'JOB_QUEUED',
      status: 'QUEUED',
      stage: null,
      message: 'Analysis job queued.',
      occurredAt: '2026-11-02T08:00:03Z',
      terminal: false,
    }
    const ready: JobEvent = {
      jobId: job.jobId,
      sequence: 9,
      eventType: 'JOB_READY',
      status: 'READY',
      stage: 'INDEXING',
      message: 'Analysis job ready.',
      occurredAt: '2026-11-02T08:00:09Z',
      terminal: true,
    }
    const request = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('connection reset'))
      .mockResolvedValueOnce(eventStreamResponse([
        { id: 3, event: 'job-event', data: stale },
        { id: 9, event: 'job-event', data: ready },
      ]))
    const received = vi.fn()

    const cursor = await createJobsApi(request, async () => undefined).followEvents(job.links.events, {
      initialLastEventId: 4,
      onEvent: received,
    })

    expect(cursor).toBe(9)
    expect(received).toHaveBeenCalledOnce()
    expect(received).toHaveBeenCalledWith(ready)
    expect(request).toHaveBeenNthCalledWith(
      2,
      job.links.events,
      expect.objectContaining({ headers: expect.objectContaining({ 'Last-Event-ID': '4' }) }),
    )
  })

  it('deduplicates repeated persisted event ids within one stream', async () => {
    const queued: JobEvent = {
      jobId: job.jobId,
      sequence: 2,
      eventType: 'JOB_QUEUED',
      status: 'QUEUED',
      stage: null,
      message: 'Analysis job queued.',
      occurredAt: '2026-11-02T08:00:02Z',
      terminal: false,
    }
    const ready: JobEvent = { ...queued, sequence: 3, eventType: 'JOB_READY', status: 'READY', terminal: true }
    const request = vi.fn().mockResolvedValue(eventStreamResponse([
      { id: 2, event: 'job-event', data: queued },
      { id: 2, event: 'job-event', data: queued },
      { id: 3, event: 'job-event', data: ready },
    ]))
    const received: number[] = []

    await createJobsApi(request).followEvents(job.links.events, {
      onEvent: (event) => received.push(event.sequence),
    })

    expect(received).toEqual([2, 3])
  })

  it('uses a generic typed error when a list failure is not JSON', async () => {
    const request = vi.fn().mockResolvedValue(new Response('unavailable', { status: 503 }))

    await expect(createJobsApi(request).list()).rejects.toMatchObject({
      status: 503,
      code: 'UNKNOWN',
    })
  })

  it('preserves a JSON authentication error returned before an SSE stream starts', async () => {
    const request = vi.fn().mockResolvedValue(jsonResponse(401, {
      code: 'AUTHENTICATION_REQUIRED',
      message: 'Authentication required.',
      retryable: false,
      requestId: 'request-8',
    }))

    await expect(createJobsApi(request).followEvents(job.links.events, { onEvent: vi.fn() }))
      .rejects.toMatchObject({ status: 401, code: 'AUTHENTICATION_REQUIRED' })
    expect(request).toHaveBeenCalledOnce()
  })
})
