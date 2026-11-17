export interface PlaybackProgressUpdate {
  mediaId: string
  positionMs: number
}

type SaveProgress = (update: PlaybackProgressUpdate) => Promise<unknown>

export interface PlaybackProgressWriter {
  schedule(mediaId: string, positionMs: number): void
  markSaved(mediaId: string, positionMs: number): void
  flush(): Promise<void>
  dispose(): void
}

export function createPlaybackProgressWriter(
  save: SaveProgress,
  intervalMs = 5_000,
  onError: (error: unknown) => void = () => undefined,
): PlaybackProgressWriter {
  const pending = new Map<string, number>()
  const saved = new Map<string, number>()
  let timer: ReturnType<typeof setTimeout> | null = null
  let flushing: Promise<void> | null = null
  let disposed = false

  function arm() {
    if (timer !== null || disposed || pending.size === 0) return
    timer = setTimeout(() => {
      timer = null
      void flush().catch(onError)
    }, intervalMs)
  }

  async function drain() {
    while (pending.size > 0) {
      const entry = pending.entries().next().value as [string, number] | undefined
      if (!entry) return
      const [mediaId, positionMs] = entry
      pending.delete(mediaId)
      if (saved.get(mediaId) === positionMs) continue
      await save({ mediaId, positionMs })
      saved.set(mediaId, positionMs)
    }
  }

  async function flush(): Promise<void> {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
    if (!flushing) {
      flushing = drain().finally(() => {
        flushing = null
        arm()
      })
    }
    await flushing
  }

  return {
    schedule(mediaId, positionMs) {
      if (disposed || !mediaId || !Number.isSafeInteger(positionMs) || positionMs < 0) return
      pending.set(mediaId, positionMs)
      arm()
    },
    markSaved(mediaId, positionMs) {
      if (mediaId && Number.isSafeInteger(positionMs) && positionMs >= 0) saved.set(mediaId, positionMs)
    },
    flush,
    dispose() {
      disposed = true
      if (timer !== null) clearTimeout(timer)
      timer = null
      pending.clear()
    },
  }
}
