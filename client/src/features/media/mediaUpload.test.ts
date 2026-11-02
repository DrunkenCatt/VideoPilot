import { describe, expect, it, vi } from 'vitest'

import type { MediaAsset, UploadSession } from '../../api/contracts'
import { hashFile, uploadMediaFile, validateUploadFile } from './mediaUpload'

describe('media upload workflow', () => {
  it('hashes a file incrementally with the standard SHA-256 result', async () => {
    const file = new File(['abc'], 'lesson.mp4', { type: 'video/mp4' })
    await expect(hashFile(file, 2)).resolves.toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    )
    await expect(hashFile(new Blob(['a'.repeat(60)]), 60)).resolves.toBe(
      '11ee391211c6256460b6ed375957fadd8061cafbb31daf967db875aebd5aaad4',
    )
    await expect(hashFile(new Blob([]))).resolves.toBe(
      'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    )
  })

  it('rejects an empty or oversized file before creating a session', () => {
    expect(validateUploadFile({ size: 0, name: 'empty.mp4' })).toContain('不能为空')
    expect(validateUploadFile({ size: 5 * 1024 ** 3 + 1, name: 'large.mp4' })).toContain('5 GiB')
    expect(validateUploadFile({ size: 10, name: 'lesson.mp4' })).toBeNull()
  })

  it('uploads sequential parts, retries with a fresh target, and completes with ETags', async () => {
    const file = new File(['123456789'], 'lesson.mp4', { type: 'video/mp4' })
    const session: UploadSession = {
      uploadId: '10000000-0000-0000-0000-000000000009' as UploadSession['uploadId'],
      mediaId: '20000000-0000-0000-0000-000000000009' as UploadSession['mediaId'],
      partSizeBytes: 5,
      maxParts: 10_000,
      expiresAt: '2026-11-03T08:00:00Z',
      partsUrl: '/parts',
      completionUrl: '/completion',
    }
    const media = { mediaId: session.mediaId, status: 'UPLOADED' } as MediaAsset
    const api = {
      create: vi.fn().mockResolvedValue(session),
      createPart: vi.fn()
        .mockResolvedValueOnce({ partNumber: 1, uploadUrl: 'signed-1a', expiresAt: session.expiresAt })
        .mockResolvedValueOnce({ partNumber: 1, uploadUrl: 'signed-1b', expiresAt: session.expiresAt })
        .mockResolvedValueOnce({ partNumber: 2, uploadUrl: 'signed-2', expiresAt: session.expiresAt }),
      putPart: vi.fn()
        .mockRejectedValueOnce(new Error('network'))
        .mockResolvedValueOnce('etag-1')
        .mockResolvedValueOnce('etag-2'),
      complete: vi.fn().mockResolvedValue(media),
      abort: vi.fn(),
    }
    const progress = vi.fn()

    await expect(uploadMediaFile(file, 'csrf', api, { hash: vi.fn().mockResolvedValue('a'.repeat(64)), onProgress: progress }))
      .resolves.toBe(media)

    expect(api.createPart).toHaveBeenCalledTimes(3)
    expect(api.putPart.mock.calls.map((call) => call[0])).toEqual(['signed-1a', 'signed-1b', 'signed-2'])
    expect(api.complete).toHaveBeenCalledWith('/completion', [
      { partNumber: 1, etag: 'etag-1' },
      { partNumber: 2, etag: 'etag-2' },
    ], 'csrf')
    expect(progress).toHaveBeenLastCalledWith(9, 9)
  })

  it('uses octet-stream for unknown MIME and aborts a session after a terminal part failure', async () => {
    const file = new File(['123'], 'lesson.mkv')
    const session: UploadSession = {
      uploadId: '10000000-0000-0000-0000-000000000009' as UploadSession['uploadId'],
      mediaId: '20000000-0000-0000-0000-000000000009' as UploadSession['mediaId'],
      partSizeBytes: 5,
      maxParts: 1,
      expiresAt: '2026-11-03T08:00:00Z',
      partsUrl: '/parts',
      completionUrl: '/completion',
    }
    const expired = new (await import('../../api/session')).ApiClientError(410, {
      code: 'UPLOAD_EXPIRED', retryable: false,
    })
    const api = {
      create: vi.fn().mockResolvedValue(session),
      createPart: vi.fn().mockRejectedValue(expired),
      putPart: vi.fn(),
      complete: vi.fn(),
      abort: vi.fn().mockRejectedValue(new Error('already expired')),
    }

    await expect(uploadMediaFile(file, 'csrf', api, { hash: vi.fn().mockResolvedValue('b'.repeat(64)) }))
      .rejects.toBe(expired)
    expect(api.create.mock.calls[0]?.[0].contentType).toBe('application/octet-stream')
    expect(api.createPart).toHaveBeenCalledOnce()
    expect(api.abort).toHaveBeenCalledWith(session.uploadId, 'csrf')
  })

  it('rejects a server part limit before requesting targets', async () => {
    const session = {
      uploadId: '10000000-0000-0000-0000-000000000009',
      mediaId: '20000000-0000-0000-0000-000000000009',
      partSizeBytes: 2,
      maxParts: 1,
      expiresAt: '2026-11-03T08:00:00Z',
      partsUrl: '/parts',
      completionUrl: '/completion',
    } as UploadSession
    const api = {
      create: vi.fn().mockResolvedValue(session),
      createPart: vi.fn(), putPart: vi.fn(), complete: vi.fn(), abort: vi.fn(),
    }

    await expect(uploadMediaFile(
      new File(['123'], 'lesson.mp4', { type: 'video/mp4' }),
      'csrf', api, { hash: vi.fn().mockResolvedValue('a'.repeat(64)) },
    )).rejects.toThrow('分片数量')
    expect(api.createPart).not.toHaveBeenCalled()
    expect(api.abort).toHaveBeenCalledOnce()
  })

  it('stops hashing when the user aborts before a session exists', async () => {
    const controller = new AbortController()
    controller.abort()
    const api = {
      create: vi.fn(), createPart: vi.fn(), putPart: vi.fn(), complete: vi.fn(), abort: vi.fn(),
    }

    await expect(uploadMediaFile(
      new File(['123'], 'lesson.mp4', { type: 'video/mp4' }),
      'csrf', api, { signal: controller.signal },
    )).rejects.toMatchObject({ name: 'AbortError' })
    expect(api.create).not.toHaveBeenCalled()
  })
})
