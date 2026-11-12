import {
  API,
  agentRunPath,
  jobGoalAnalysisResultPath,
  jobAgentRunsPath,
  type AgentRun,
  type AgentRunId,
  type GoalAnalysisResult,
  type JobId,
} from './contracts'
import { ApiClientError } from './session'

type Request = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

async function responseJson<T>(response: Response): Promise<T> {
  if (response.ok) return (await response.json()) as T
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
      return responseJson<AgentRun>(response)
    },

    async get(runId: AgentRunId): Promise<AgentRun> {
      const response = await request(agentRunPath(runId), {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      })
      return responseJson<AgentRun>(response)
    },

    async getGoalAnalysisResult(jobId: JobId): Promise<GoalAnalysisResult> {
      const response = await request(jobGoalAnalysisResultPath(jobId), {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      })
      return responseJson<GoalAnalysisResult>(response)
    },
  }
}

export type AgentsApi = ReturnType<typeof createAgentsApi>
