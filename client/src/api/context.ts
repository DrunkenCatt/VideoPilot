import {
  CONTRACT_LIMITS,
  contextPath,
  type JobId,
  type SegmentPageResponse,
  type TemporaryObjectAccess,
  type VideoContext,
} from './contracts'
import { ApiClientError } from './session'

type Request = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

export interface SegmentQuery {
  cursor?: number
  pageSize?: number
  fromMs?: number
  toMs?: number
}

async function errorFrom(response: Response): Promise<ApiClientError> {
  try {
    return new ApiClientError(response.status, await response.json())
  } catch {
    return new ApiClientError(response.status)
  }
}

async function getJson<T>(request: Request, path: string): Promise<T> {
  const response = await request(path, {
    method: 'GET',
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  })
  if (!response.ok) throw await errorFrom(response)
  return (await response.json()) as T
}

export function createContextApi(request: Request = fetch) {
  return {
    get(jobId: JobId): Promise<VideoContext> {
      return getJson(request, contextPath(jobId))
    },

    segments(link: string, query: SegmentQuery = {}): Promise<SegmentPageResponse> {
      const parameters = new URLSearchParams()
      if (query.cursor !== undefined) parameters.set('cursor', String(query.cursor))
      parameters.set('pageSize', String(query.pageSize ?? CONTRACT_LIMITS.defaultSegmentPageSize))
      if (query.fromMs !== undefined) parameters.set('fromMs', String(query.fromMs))
      if (query.toMs !== undefined) parameters.set('toMs', String(query.toMs))
      return getJson(request, `${link}?${parameters}`)
    },

    access(link: string): Promise<TemporaryObjectAccess> {
      return getJson(request, link)
    },
  }
}

export type ContextApi = ReturnType<typeof createContextApi>
