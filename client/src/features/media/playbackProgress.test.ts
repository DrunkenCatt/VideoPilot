import { describe, expect, it, vi } from 'vitest'

import { createPlaybackProgressWriter } from './playbackProgress'

describe('playback progress writer', () => {
  it('coalesces frequent time updates and writes only the latest position', async () => {
    vi.useFakeTimers()
    const save = vi.fn().mockResolvedValue(undefined)
    const writer = createPlaybackProgressWriter(save, 5_000)

    writer.schedule('media-1', 1_000)
    writer.schedule('media-1', 2_000)
    writer.schedule('media-1', 3_000)
    await vi.advanceTimersByTimeAsync(4_999)
    expect(save).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1)
    expect(save).toHaveBeenCalledOnce()
    expect(save).toHaveBeenCalledWith({ mediaId: 'media-1', positionMs: 3_000 })
    vi.useRealTimers()
  })

  it('flushes immediately, skips an unchanged restored value and isolates media switches', async () => {
    vi.useFakeTimers()
    const save = vi.fn().mockResolvedValue(undefined)
    const writer = createPlaybackProgressWriter(save, 5_000)

    writer.markSaved('media-1', 2_000)
    writer.schedule('media-1', 2_000)
    await writer.flush()
    expect(save).not.toHaveBeenCalled()

    writer.schedule('media-1', 4_000)
    writer.schedule('media-2', 1_000)
    await writer.flush()
    expect(save.mock.calls).toEqual([
      [{ mediaId: 'media-1', positionMs: 4_000 }],
      [{ mediaId: 'media-2', positionMs: 1_000 }],
    ])
    vi.useRealTimers()
  })

  it('does not overlap writes and preserves a newer position queued during a request', async () => {
    vi.useFakeTimers()
    let resolveFirst!: () => void
    const save = vi.fn()
      .mockImplementationOnce(() => new Promise<void>((resolve) => { resolveFirst = resolve }))
      .mockResolvedValueOnce(undefined)
    const writer = createPlaybackProgressWriter(save, 5_000)

    writer.schedule('media-1', 1_000)
    const flushing = writer.flush()
    await Promise.resolve()
    writer.schedule('media-1', 2_000)
    expect(save).toHaveBeenCalledOnce()

    resolveFirst()
    await flushing
    expect(save).toHaveBeenCalledTimes(2)
    expect(save).toHaveBeenLastCalledWith({ mediaId: 'media-1', positionMs: 2_000 })
    vi.useRealTimers()
  })
})
