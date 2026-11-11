import {
  API,
  agentRunPath,
  jobAgentRunsPath,
  type AgentRun,
  type AgentRunId,
  type JobId,
} from './contracts'
import { ApiClientError } from './session'

type Request = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

async function responseJson(response: Response): Promise<AgentRun> {
  if (response.ok) return (await response.json()) as AgentRun
  try {
    throw new ApiClientError(response.status, await response.json())
  } catch (error) {
    if (error instanceof ApiClientError) throw error
    throw new ApiClientError(response.status)
  }
}

export function createAgentsApi(request: Request = fetch) {
  return {
    async create(
      jobId: JobId,
      goal: string,
      idempotencyKey: string,
      csrfToken: string,
    ): Promise<AgentRun> {
      const response = await request(jobAgentRunsPath(jobId), {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          [API.idempotencyKeyHeader]: idempotencyKey,
          'X-CSRF-Token': csrfToken,
        },
        body: JSON.stringify({ goal }),
      })
      return responseJson(response)
    },

    async get(runId: AgentRunId): Promise<AgentRun> {
      const response = await request(agentRunPath(runId), {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      })
      return responseJson(response)
    },
  }
}

export type AgentsApi = ReturnType<typeof createAgentsApi>
