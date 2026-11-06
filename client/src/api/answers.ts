import {
  API,
  CONTRACT_LIMITS,
  jobAnswersPath,
  type AnswerPageResponse,
  type AnswerRequest,
  type AnswerResponse,
  type JobId,
} from './contracts'
import { ApiClientError } from './session'

type Request = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

async function errorFrom(response: Response): Promise<ApiClientError> {
  try {
    return new ApiClientError(response.status, await response.json())
  } catch {
    return new ApiClientError(response.status)
  }
}

async function responseJson<T>(response: Response): Promise<T> {
  if (!response.ok) throw await errorFrom(response)
  return (await response.json()) as T
}

export function createAnswersApi(request: Request = fetch) {
  return {
    async ask(
      jobId: JobId,
      input: AnswerRequest,
      idempotencyKey: string,
      csrfToken: string,
      signal?: AbortSignal,
    ): Promise<AnswerResponse> {
      const response = await request(jobAnswersPath(jobId), {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          [API.idempotencyKeyHeader]: idempotencyKey,
          'X-CSRF-Token': csrfToken,
        },
        body: JSON.stringify(input),
        signal,
      })
      return responseJson(response)
    },

    async list(
      jobId: JobId,
      cursor?: number,
      pageSize = CONTRACT_LIMITS.defaultAnswerPageSize,
    ): Promise<AnswerPageResponse> {
      const query = new URLSearchParams()
      if (cursor !== undefined) query.set('cursor', String(cursor))
      query.set('pageSize', String(pageSize))
      const response = await request(`${jobAnswersPath(jobId)}?${query}`, {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      })
      return responseJson(response)
    },
  }
}

export type AnswersApi = ReturnType<typeof createAnswersApi>
