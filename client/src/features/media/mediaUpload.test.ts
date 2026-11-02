import { describe, expect, it, vi } from 'vitest'

import type { MediaAsset, UploadSession } from '../../api/contracts'
import { hashFile, uploadMediaFile, validateUploadFile } from './mediaUpload'

describe('media upload workflow', () => {
  it('hashes a file incrementally with the standard SHA-256 result', async () => {
    const file = new File(['abc'], 'lesson.mp4', { type: 'video/mp4' })
    await expect(hashFile(file, 2)).resolves.toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
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
})
