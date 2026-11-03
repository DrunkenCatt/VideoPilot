import { describe, expect, it, vi } from 'vitest'

import type { MediaAsset, PageResponse, UploadSession } from './contracts'
import { createMediaApi } from './media'
import { ApiClientError } from './session'

const session: UploadSession = {
  uploadId: '10000000-0000-0000-0000-000000000009' as UploadSession['uploadId'],
  mediaId: '20000000-0000-0000-0000-000000000009' as UploadSession['mediaId'],
  partSizeBytes: 5,
  maxParts: 10_000,
  expiresAt: '2026-11-03T08:00:00Z',
  partsUrl: '/api/v1/media-uploads/10000000-0000-0000-0000-000000000009/parts',
  completionUrl: '/api/v1/media-uploads/10000000-0000-0000-0000-000000000009/completion',
}

const media: MediaAsset = {
  mediaId: session.mediaId,
  originalFilename: 'lesson.mp4',
  contentLength: 9,
  sha256: 'a'.repeat(64),
  status: 'UPLOADED',
  durationMs: null,
  failure: null,
  createdAt: '2026-11-02T08:00:00Z',
  updatedAt: '2026-11-02T08:01:00Z',
}

function jsonResponse(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('media API', () => {
  it('uses owner-scoped links and CSRF without sending an owner id', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce(jsonResponse(201, session))
      .mockResolvedValueOnce(jsonResponse(201, {
        partNumber: 1,
        uploadUrl: 'http://minio.invalid/signed-part',
        expiresAt: session.expiresAt,
      }))
      .mockResolvedValueOnce(jsonResponse(202, media))
      .mockResolvedValueOnce(jsonResponse(204))
    const api = createMediaApi(request)

    const created = await api.create({
      originalFilename: 'lesson.mp4',
      contentLength: 9,
      sha256: 'a'.repeat(64),
      contentType: 'video/mp4',
    }, 'csrf-media')
    const target = await api.createPart(created.partsUrl, 1, 'csrf-media')
    await api.complete(created.completionUrl, [{ partNumber: 1, etag: 'etag-1' }], 'csrf-media')
    await api.abort(created.uploadId, 'csrf-media')

    expect(target.uploadUrl).toBe('http://minio.invalid/signed-part')
    for (const call of request.mock.calls) {
      expect(JSON.stringify(call)).not.toContain('ownerId')
      if (call[1]?.method !== 'GET') expect(call[1]?.headers['X-CSRF-Token']).toBe('csrf-media')
    }
  })

  it('reads owner-scoped media pages and details without sending owner identity', async () => {
    const page: PageResponse<MediaAsset> = {
      data: [media],
      pagination: { page: 2, pageSize: 10, totalItems: 11, totalPages: 2 },
    }
    const request = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, page))
      .mockResolvedValueOnce(jsonResponse(200, media))
    const api = createMediaApi(request)

    await expect(api.list(2, 10)).resolves.toEqual(page)
    await expect(api.get(media.mediaId)).resolves.toEqual(media)

    expect(request.mock.calls[0]?.[0]).toBe('/api/v1/media-assets?page=2&pageSize=10')
    expect(request.mock.calls[1]?.[0]).toBe(`/api/v1/media-assets/${media.mediaId}`)
    expect(JSON.stringify(request.mock.calls)).not.toContain('ownerId')
  })

  it('uploads a part without credentials and reports real loaded bytes', async () => {
    const progress = vi.fn()
    const xhr = new FakeXhr()
    const api = createMediaApi(fetch, () => xhr as unknown as XMLHttpRequest)

    const promise = api.putPart('http://minio.invalid/signed-part', new Blob(['hello']), progress)
    xhr.upload.onprogress?.({ lengthComputable: true, loaded: 3, total: 5 } as ProgressEvent)
    xhr.status = 200
    xhr.responseHeaders.ETag = '"etag-1"'
    xhr.onload?.(new ProgressEvent('load'))

    await expect(promise).resolves.toBe('"etag-1"')
    expect(progress).toHaveBeenCalledWith(3, 5)
    expect(xhr.withCredentials).toBe(false)
    expect(xhr.openArgs).toEqual(['PUT', 'http://minio.invalid/signed-part', true])
  })

  it('aborts the active part request through AbortSignal', async () => {
    const xhr = new FakeXhr()
    const controller = new AbortController()
    const api = createMediaApi(fetch, () => xhr as unknown as XMLHttpRequest)

    const promise = api.putPart('http://minio.invalid/signed-part', new Blob(['hello']), vi.fn(), controller.signal)
    controller.abort()

    await expect(promise).rejects.toMatchObject({ name: 'AbortError' })
    expect(xhr.abort).toHaveBeenCalledOnce()
  })

  it('maps JSON and malformed API failures to safe client errors', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce(jsonResponse(410, {
        code: 'UPLOAD_EXPIRED', message: 'expired', retryable: false, requestId: 'request-1',
      }))
      .mockResolvedValueOnce(new Response('not-json', { status: 500 }))
    const api = createMediaApi(request)

    await expect(api.create({
      originalFilename: 'lesson.mp4', contentLength: 9, sha256: 'a'.repeat(64), contentType: 'video/mp4',
    }, 'csrf')).rejects.toMatchObject({ code: 'UPLOAD_EXPIRED', status: 410 })
    await expect(api.abort(session.uploadId, 'csrf')).rejects.toMatchObject({ code: 'UNKNOWN', status: 500 })
  })

  it('rejects unsuccessful storage responses and missing ETags', async () => {
    const failed = new FakeXhr()
    const missingEtag = new FakeXhr()
    const api = createMediaApi(fetch, vi.fn()
      .mockReturnValueOnce(failed as unknown as XMLHttpRequest)
      .mockReturnValueOnce(missingEtag as unknown as XMLHttpRequest))

    const failedPromise = api.putPart('signed-1', new Blob(['x']), vi.fn())
    failed.status = 503
    failed.onload?.(new ProgressEvent('load'))
    await expect(failedPromise).rejects.toMatchObject({ status: 503, retryable: true })

    const missingPromise = api.putPart('signed-2', new Blob(['x']), vi.fn())
    missingEtag.status = 200
    missingEtag.onload?.(new ProgressEvent('load'))
    await expect(missingPromise).rejects.toBeInstanceOf(ApiClientError)
  })

  it('maps a storage network error and ignores non-computable progress', async () => {
    const progress = vi.fn()
    const xhr = new FakeXhr()
    const api = createMediaApi(fetch, () => xhr as unknown as XMLHttpRequest)
    const promise = api.putPart('signed', new Blob(['x']), progress)
    xhr.upload.onprogress?.({ lengthComputable: false, loaded: 1, total: 0 } as ProgressEvent)
    xhr.onerror?.(new ProgressEvent('error'))

    await expect(promise).rejects.toMatchObject({ status: 0, retryable: true })
    expect(progress).not.toHaveBeenCalled()
  })
})

class FakeXhr {
  readonly upload: { onprogress: ((event: ProgressEvent) => void) | null } = { onprogress: null }
  readonly responseHeaders: Record<string, string> = {}
  readonly abort = vi.fn(() => this.onabort?.(new ProgressEvent('abort')))
  status = 0
  withCredentials = false
  onload: ((event: ProgressEvent) => void) | null = null
  onerror: ((event: ProgressEvent) => void) | null = null
  onabort: ((event: ProgressEvent) => void) | null = null
  openArgs: unknown[] = []

  open(...args: unknown[]) { this.openArgs = args }
  send(body: Blob) { void body }
  getResponseHeader(name: string) { return this.responseHeaders[name] ?? null }
}
