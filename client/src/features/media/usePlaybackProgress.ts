import { computed, ref } from 'vue'

import type { MediaId } from '../../api/contracts'
import type { MediaApi } from '../../api/media'
import { ApiClientError } from '../../api/session'
import { createPlaybackProgressWriter } from './playbackProgress'

type ProgressApi = Pick<MediaApi, 'playbackProgress' | 'savePlaybackProgress'>

function formatTime(milliseconds: number): string {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1_000))
  const hours = Math.floor(totalSeconds / 3_600)
  const minutes = Math.floor((totalSeconds % 3_600) / 60)
  const seconds = totalSeconds % 60
  const clock = `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
  return hours > 0 ? `${String(hours).padStart(2, '0')}:${clock}` : clock
}

export function usePlaybackProgress(
  api: ProgressApi,
  csrfToken: () => string,
  unauthenticated: () => void,
) {
  const restoredPositionMs = ref(0)
  const message = ref('')
  const label = computed(() => restoredPositionMs.value > 0
    ? `上次播放位置 ${formatTime(restoredPositionMs.value)}`
    : '')
  let revision = 0
  let applied = false
  let player: HTMLVideoElement | null = null

  function handleError(error: unknown) {
    if (error instanceof ApiClientError && error.code === 'AUTHENTICATION_REQUIRED') {
      message.value = '会话已过期，无法同步观看进度，请重新登录。'
      unauthenticated()
      return
    }
    message.value = '观看进度暂时无法同步，不影响当前视频播放。'
  }

  const writer = createPlaybackProgressWriter(async ({ mediaId, positionMs }) => {
    await api.savePlaybackProgress(mediaId as MediaId, positionMs, csrfToken())
  }, 5_000, handleError)

  function applyRestoredPosition() {
    if (applied || !player || restoredPositionMs.value <= 0) return
    player.currentTime = restoredPositionMs.value / 1_000
    applied = true
  }

  async function restore(mediaId: string, durationMs: number) {
    const currentRevision = ++revision
    restoredPositionMs.value = 0
    message.value = ''
    applied = false
    try {
      const restored = await api.playbackProgress(mediaId as MediaId)
      if (currentRevision !== revision) return
      const positionMs = Math.min(Math.max(0, restored.positionMs), Math.max(0, durationMs))
      restoredPositionMs.value = positionMs
      writer.markSaved(mediaId, positionMs)
      applyRestoredPosition()
    } catch (error) {
      if (currentRevision === revision) handleError(error)
    }
  }

  function apply(target: HTMLVideoElement) {
    player = target
    applyRestoredPosition()
  }

  function track(mediaId: string, currentTimeSeconds: number, durationMs: number) {
    const positionMs = Math.min(
      Math.max(0, Math.round(currentTimeSeconds * 1_000)),
      Math.max(0, durationMs),
    )
    writer.schedule(mediaId, positionMs)
  }

  function reset() {
    revision++
    restoredPositionMs.value = 0
    message.value = ''
    applied = false
    player = null
  }

  async function flush() {
    try {
      await writer.flush()
    } catch (error) {
      handleError(error)
    }
  }

  return {
    restoredPositionMs,
    message,
    label,
    restore,
    apply,
    track,
    flush,
    reset,
    dispose: writer.dispose,
  }
}
