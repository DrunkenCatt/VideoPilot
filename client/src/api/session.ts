import { API, type ApiError, type ApiErrorCode, type LoginRequest, type Session } from './contracts'

type Request = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

export class ApiClientError extends Error {
  readonly status: number
  readonly code: ApiErrorCode | 'UNKNOWN'
  readonly retryable: boolean
  readonly requestId?: string

  constructor(status: number, detail?: Partial<ApiError>) {
    super(detail?.message ?? 'The request could not be completed.')
    this.name = 'ApiClientError'
    this.status = status
    this.code = detail?.code ?? 'UNKNOWN'
    this.retryable = detail?.retryable ?? false
    this.requestId = detail?.requestId
  }
}

async function errorFrom(response: Response): Promise<ApiClientError> {
  try {
    return new ApiClientError(response.status, (await response.json()) as ApiError)
  } catch {
    return new ApiClientError(response.status)
  }
}

export function createSessionApi(request: Request = fetch) {
  const currentUrl = `${API.sessions}/current`

  return {
    async current(): Promise<Session | null> {
      const response = await request(currentUrl, {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      })
      if (response.status === 401) return null
      if (!response.ok) throw await errorFrom(response)
      return (await response.json()) as Session
    },

    async login(credentials: LoginRequest): Promise<Session> {
      const response = await request(API.sessions, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
        body: JSON.stringify(credentials),
      })
      if (!response.ok) throw await errorFrom(response)
      return (await response.json()) as Session
    },

    async logout(csrfToken: string): Promise<void> {
      const response = await request(currentUrl, {
        method: 'DELETE',
        credentials: 'same-origin',
        headers: { 'X-CSRF-Token': csrfToken },
      })
      if (!response.ok) throw await errorFrom(response)
    },
  }
}
