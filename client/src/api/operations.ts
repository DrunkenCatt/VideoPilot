import { reliabilitySummaryPath, type ReliabilitySummary } from './contracts'
import { ApiClientError } from './session'

type Request = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

async function failure(response: Response): Promise<ApiClientError> {
  try {
    return new ApiClientError(response.status, await response.json())
  } catch {
    return new ApiClientError(response.status)
  }
}

export interface OperationsApi {
  get(): Promise<ReliabilitySummary>
}

export function createOperationsApi(request: Request = fetch): OperationsApi {
  return {
    async get(): Promise<ReliabilitySummary> {
      const response = await request(reliabilitySummaryPath(), {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      })
      if (!response.ok) throw await failure(response)
      return (await response.json()) as ReliabilitySummary
    },
  }
}
