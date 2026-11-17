import { describe, expect, it, vi } from 'vitest'

import type { MediaApi } from '../../api/media'
import { ApiClientError } from '../../api/session'
import { usePlaybackProgress } from './usePlaybackProgress'

const mediaId = '20000000-0000-0000-0000-000000000140'

function api(overrides: Partial<MediaApi> = {}) {
  return {
    playbackProgress: vi.fn().mockResolvedValue({ positionMs: 12_000, updatedAt: '2026-11-17T08:00:00Z' }),
    savePlaybackProgress: vi.fn().mockResolvedValue({ positionMs: 18_000, updatedAt: '2026-11-17T08:01:00Z' }),
    ...overrides,
  } as unknown as MediaApi
}

describe('usePlaybackProgress', () => {
  it('restores a bounded last position and applies it to a player once metadata is ready', async () => {
    const mediaApi = api()
    const progress = usePlaybackProgress(mediaApi, () => 'csrf', vi.fn())
    const player = document.createElement('video')

    await progress.restore(mediaId, 60_000)
    progress.apply(player)

    expect(progress.restoredPositionMs.value).toBe(12_000)
    expect(progress.label.value).toBe('上次播放位置 00:12')
    expect(player.currentTime).toBe(12)
  })

  it('applies a late progress response after player metadata is already ready', async () => {
    let resolveProgress!: (value: { positionMs: number, updatedAt: string }) => void
    const response = new Promise<{ positionMs: number, updatedAt: string }>((resolve) => {
      resolveProgress = resolve
    })
    const progress = usePlaybackProgress(api({ playbackProgress: vi.fn().mockReturnValue(response) }), () => 'csrf', vi.fn())
    const player = document.createElement('video')

    const restoring = progress.restore(mediaId, 60_000)
    progress.apply(player)
    resolveProgress({ positionMs: 9_000, updatedAt: '2026-11-17T08:00:00Z' })
    await restoring

    expect(player.currentTime).toBe(9)
  })

  it('throttles playback writes and flushes the latest bounded position', async () => {
    vi.useFakeTimers()
    const mediaApi = api()
    const progress = usePlaybackProgress(mediaApi, () => 'csrf-token', vi.fn())

    progress.track(mediaId, 3.2, 60_000)
    progress.track(mediaId, 4.8, 60_000)
    await vi.advanceTimersByTimeAsync(5_000)

    expect(mediaApi.savePlaybackProgress).toHaveBeenCalledOnce()
    expect(mediaApi.savePlaybackProgress).toHaveBeenCalledWith(mediaId, 4_800, 'csrf-token')
    progress.dispose()
    vi.useRealTimers()
  })

  it('reports session expiry while keeping playback progress non-blocking', async () => {
    const unauthenticated = vi.fn()
    const progress = usePlaybackProgress(api({
      playbackProgress: vi.fn().mockRejectedValue(new ApiClientError(401, {
        code: 'AUTHENTICATION_REQUIRED', message: 'expired', retryable: false, requestId: 'r1',
      })),
    }), () => 'csrf', unauthenticated)

    await progress.restore(mediaId, 60_000)

    expect(unauthenticated).toHaveBeenCalledOnce()
    expect(progress.message.value).toContain('重新登录')
  })

  it('handles a failed explicit flush without leaking a rejected promise', async () => {
    const progress = usePlaybackProgress(api({
      savePlaybackProgress: vi.fn().mockRejectedValue(new Error('offline')),
    }), () => 'csrf', vi.fn())
    progress.track(mediaId, 3, 60_000)

    await expect(progress.flush()).resolves.toBeUndefined()
    expect(progress.message.value).toContain('不影响当前视频播放')
  })
})
