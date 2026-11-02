import { CONTRACT_LIMITS, type CompletedPart, type MediaAsset } from '../../api/contracts'
import { ApiClientError } from '../../api/session'
import type { MediaApi } from '../../api/media'
import { Sha256 } from './sha256'

const HASH_CHUNK_BYTES = 4 * 1024 ** 2
const MAX_PART_ATTEMPTS = 3

export function validateUploadFile(file: Pick<File, 'size' | 'name'>): string | null {
  if (file.size <= 0) return '视频文件不能为空。'
  if (file.size > CONTRACT_LIMITS.maxUploadBytes) return '单个视频不能超过 5 GiB。'
  if (file.name.length > 512) return '文件名过长，请缩短后重试。'
  return null
}

export async function hashFile(file: Blob, chunkBytes = HASH_CHUNK_BYTES): Promise<string> {
  const hash = new Sha256()
  for (let offset = 0; offset < file.size; offset += chunkBytes) {
    const bytes = new Uint8Array(await readBlob(file.slice(offset, offset + chunkBytes)))
    hash.update(bytes)
  }
  return hash.digestHex()
}

function readBlob(blob: Blob): Promise<ArrayBuffer> {
  if (typeof blob.arrayBuffer === 'function') return blob.arrayBuffer()
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as ArrayBuffer)
    reader.onerror = () => reject(reader.error ?? new Error('无法读取本地文件。'))
    reader.readAsArrayBuffer(blob)
  })
}

interface UploadOptions {
  signal?: AbortSignal
  hash?: (file: File) => Promise<string>
  onHashing?: () => void
  onProgress?: (loaded: number, total: number) => void
  onRetry?: (partNumber: number, nextAttempt: number) => void
  onSession?: (uploadId: string) => void
}

function shouldRetry(error: unknown, signal?: AbortSignal) {
  if (signal?.aborted || (error instanceof DOMException && error.name === 'AbortError')) return false
  return !(error instanceof ApiClientError) || error.retryable
}

export async function uploadMediaFile(
  file: File,
  csrfToken: string,
  api: MediaApi,
  options: UploadOptions = {},
): Promise<MediaAsset> {
  const validation = validateUploadFile(file)
  if (validation) throw new Error(validation)
  options.onHashing?.()
  const sha256 = await (options.hash ?? hashFile)(file)
  const session = await api.create({
    originalFilename: file.name,
    contentLength: file.size,
    sha256,
    contentType: file.type.startsWith('video/') ? file.type : 'application/octet-stream',
  }, csrfToken)
  options.onSession?.(session.uploadId)

  try {
    const partCount = Math.ceil(file.size / session.partSizeBytes)
    if (partCount > session.maxParts) throw new Error('文件需要的分片数量超过服务端限制。')
    const completed: CompletedPart[] = []
    let committedBytes = 0
    for (let partNumber = 1; partNumber <= partCount; partNumber += 1) {
      const start = (partNumber - 1) * session.partSizeBytes
      const part = file.slice(start, Math.min(start + session.partSizeBytes, file.size))
      let etag: string | null = null
      for (let attempt = 1; attempt <= MAX_PART_ATTEMPTS; attempt += 1) {
        try {
          const target = await api.createPart(session.partsUrl, partNumber, csrfToken)
          etag = await api.putPart(
            target.uploadUrl,
            part,
            (loaded) => options.onProgress?.(committedBytes + loaded, file.size),
            options.signal,
          )
          break
        } catch (error) {
          options.onProgress?.(committedBytes, file.size)
          if (attempt === MAX_PART_ATTEMPTS || !shouldRetry(error, options.signal)) throw error
          options.onRetry?.(partNumber, attempt + 1)
        }
      }
      if (!etag) throw new Error('分片上传未返回校验标识。')
      completed.push({ partNumber, etag })
      committedBytes += part.size
      options.onProgress?.(committedBytes, file.size)
    }
    return await api.complete(session.completionUrl, completed, csrfToken)
  } catch (error) {
    try {
      await api.abort(session.uploadId, csrfToken)
    } catch {
      // The original upload error is more useful; server expiry cleanup remains authoritative.
    }
    throw error
  }
}
