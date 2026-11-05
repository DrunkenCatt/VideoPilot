import { describe, expect, it, vi } from 'vitest'

import type { JobId, SegmentPageResponse, TemporaryObjectAccess, VideoContext } from './contracts'
import { createContextApi } from './context'

const jobId = '10000000-0000-0000-0000-000000000008' as JobId

const context: VideoContext = {
  contextId: '30000000-0000-0000-0000-000000000008' as VideoContext['contextId'],
  jobId,
  mediaId: '20000000-0000-0000-0000-000000000008' as VideoContext['mediaId'],
  status: 'READY',
  extractionVersion: 'm2-extraction-v1',
  alignmentVersion: 'm2-align-60s-v1',
  durationMs: 125_000,
  asrState: 'AVAILABLE',
  ocrState: 'DEGRADED',
  segmentCount: 2,
  updatedAt: '2026-11-05T08:00:00Z',
  links: {
    segments: `/api/v1/analysis-jobs/${jobId}/context/segments`,
    playback: '/api/v1/media-assets/20000000-0000-0000-0000-000000000008/playback',
  },
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('context API', () => {
  it('reads the owner-scoped context and a bounded segment window', async () => {
    const page: SegmentPageResponse = { data: [], nextCursor: 17, hasMore: true }
    const request = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, context))
      .mockResolvedValueOnce(jsonResponse(200, page))
    const api = createContextApi(request)

    await expect(api.get(jobId)).resolves.toEqual(context)
    await expect(api.segments(context.links.segments, {
      cursor: 12,
      pageSize: 100,
      fromMs: 60_000,
      toMs: 120_000,
    })).resolves.toEqual(page)

    expect(request.mock.calls[0]?.[0]).toBe(`/api/v1/analysis-jobs/${jobId}/context`)
    expect(request.mock.calls[1]?.[0]).toBe(`${context.links.segments}?cursor=12&pageSize=100&fromMs=60000&toMs=120000`)
    expect(JSON.stringify(request.mock.calls)).not.toContain('ownerId')
  })

  it('renews playback and keyframe access through server-provided links', async () => {
    const access: TemporaryObjectAccess = {
      url: 'http://storage.invalid/temporary-object',
      expiresAt: '2026-11-05T08:10:00Z',
    }
    const request = vi.fn().mockResolvedValue(jsonResponse(200, access))
    const api = createContextApi(request)

    await expect(api.access(context.links.playback)).resolves.toEqual(access)

    expect(request).toHaveBeenCalledWith(context.links.playback, expect.objectContaining({
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    }))
  })

  it('preserves stable authentication and not-found failures', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce(jsonResponse(401, {
        code: 'AUTHENTICATION_REQUIRED', message: 'expired', retryable: false, requestId: 'request-1',
      }))
      .mockResolvedValueOnce(jsonResponse(404, {
        code: 'RESOURCE_NOT_FOUND', message: 'hidden', retryable: false, requestId: 'request-2',
      }))
    const api = createContextApi(request)

    await expect(api.get(jobId)).rejects.toMatchObject({ status: 401, code: 'AUTHENTICATION_REQUIRED' })
    await expect(api.get(jobId)).rejects.toMatchObject({ status: 404, code: 'RESOURCE_NOT_FOUND' })
  })
})
