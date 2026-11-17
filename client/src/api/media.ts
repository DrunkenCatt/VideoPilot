import {
  API,
  type CompletedPart,
  type MediaAsset,
  type MediaId,
  type MediaStorageSummary,
  type PageResponse,
  type UploadId,
  type UploadPartTarget,
  type UploadSession,
  type UploadSessionRequest,
} from './contracts'
import { ApiClientError } from './session'

type Request = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>
type XhrFactory = () => XMLHttpRequest

async function errorFrom(response: Response): Promise<ApiClientError> {
  try {
    return new ApiClientError(response.status, await response.json())
  } catch {
    return new ApiClientError(response.status)
  }
}

function jsonHeaders(csrfToken: string) {
  return {
    Accept: 'application/json',
    'Content-Type': 'application/json',
    'X-CSRF-Token': csrfToken,
  }
}

export function createMediaApi(
  request: Request = fetch,
  xhrFactory: XhrFactory = () => new XMLHttpRequest(),
) {
  return {
    async list(page = 1, pageSize = 20): Promise<PageResponse<MediaAsset>> {
      const query = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
      const response = await request(`${API.mediaAssets}?${query}`, {
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      })
      if (!response.ok) throw await errorFrom(response)
      return (await response.json()) as PageResponse<MediaAsset>
    },

    async get(mediaId: MediaId): Promise<MediaAsset> {
      const response = await request(`${API.mediaAssets}/${encodeURIComponent(mediaId)}`, {
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      })
      if (!response.ok) throw await errorFrom(response)
      return (await response.json()) as MediaAsset
    },

    async storageSummary(): Promise<MediaStorageSummary> {
      const response = await request(`${API.mediaAssets}/storage-summary`, {
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      })
      if (!response.ok) throw await errorFrom(response)
      return (await response.json()) as MediaStorageSummary
    },

    async create(input: UploadSessionRequest, csrfToken: string): Promise<UploadSession> {
      const response = await request(API.mediaUploads, {
        method: 'POST',
        credentials: 'same-origin',
        headers: jsonHeaders(csrfToken),
        body: JSON.stringify(input),
      })
      if (!response.ok) throw await errorFrom(response)
      return (await response.json()) as UploadSession
    },

    async createPart(partsUrl: string, partNumber: number, csrfToken: string): Promise<UploadPartTarget> {
      const response = await request(partsUrl, {
        method: 'POST',
        credentials: 'same-origin',
        headers: jsonHeaders(csrfToken),
        body: JSON.stringify({ partNumber }),
      })
      if (!response.ok) throw await errorFrom(response)
      return (await response.json()) as UploadPartTarget
    },

    putPart(
      uploadUrl: string,
      body: Blob,
      onProgress: (loaded: number, total: number) => void,
      signal?: AbortSignal,
    ): Promise<string> {
      return new Promise((resolve, reject) => {
        const xhr = xhrFactory()
        const abortError = () => new DOMException('Upload aborted.', 'AbortError')
        const onSignalAbort = () => xhr.abort()
        const finish = (action: () => void) => {
          signal?.removeEventListener('abort', onSignalAbort)
          action()
        }

        xhr.open('PUT', uploadUrl, true)
        xhr.withCredentials = false
        xhr.upload.onprogress = (event) => {
          if (event.lengthComputable) onProgress(event.loaded, event.total)
        }
        xhr.onload = () => finish(() => {
          if (xhr.status < 200 || xhr.status >= 300) {
            reject(new ApiClientError(xhr.status, { message: '分片上传失败。', retryable: xhr.status >= 500 }))
            return
          }
          const etag = xhr.getResponseHeader('ETag')
          if (!etag) {
            reject(new ApiClientError(xhr.status, { message: '对象存储未返回可读的 ETag。' }))
            return
          }
          resolve(etag)
        })
        xhr.onerror = () => finish(() => reject(new ApiClientError(0, {
          message: '无法连接对象存储。',
          retryable: true,
        })))
        xhr.onabort = () => finish(() => reject(abortError()))
        signal?.addEventListener('abort', onSignalAbort, { once: true })
        if (signal?.aborted) {
          xhr.abort()
          return
        }
        xhr.send(body)
      })
    },

    async complete(completionUrl: string, parts: CompletedPart[], csrfToken: string): Promise<MediaAsset> {
      const response = await request(completionUrl, {
        method: 'POST',
        credentials: 'same-origin',
        headers: jsonHeaders(csrfToken),
        body: JSON.stringify({ parts }),
      })
      if (!response.ok) throw await errorFrom(response)
      return (await response.json()) as MediaAsset
    },

    async abort(uploadId: UploadId, csrfToken: string): Promise<void> {
      const response = await request(`${API.mediaUploads}/${uploadId}`, {
        method: 'DELETE',
        credentials: 'same-origin',
        headers: { 'X-CSRF-Token': csrfToken },
      })
      if (!response.ok) throw await errorFrom(response)
    },
  }
}

export type MediaApi = ReturnType<typeof createMediaApi>
