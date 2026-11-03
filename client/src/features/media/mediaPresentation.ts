import type { MediaStatus } from '../../api/contracts'

const mediaStatusLabels: Record<MediaStatus, string> = {
  UPLOAD_PENDING: '上传中',
  UPLOADED: '等待校验',
  PROBING: '正在校验',
  READY: '可分析',
  FAILED: '校验失败',
}

export function mediaStatusLabel(status: MediaStatus) {
  return mediaStatusLabels[status]
}

export function mediaDurationLabel(value: number | null) {
  if (value === null) return '—'
  const totalSeconds = Math.floor(value / 1_000)
  const hours = Math.floor(totalSeconds / 3_600)
  const minutes = Math.floor((totalSeconds % 3_600) / 60)
  const seconds = totalSeconds % 60
  return hours > 0
    ? `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
    : `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}
