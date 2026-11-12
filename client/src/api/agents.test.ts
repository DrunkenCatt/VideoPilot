import { describe, expect, it, vi } from 'vitest'

import type { AgentRun, GoalAnalysisResult, JobId } from './contracts'
import { createAgentsApi } from './agents'

const jobId = '10000000-0000-0000-0000-000000000008' as JobId
const run = {
  runId: '80000000-0000-0000-0000-000000000008' as AgentRun['runId'],
  jobId,
  goal: '解释课程',
  status: 'REQUESTED',
  versions: {
    policyVersion: 'policy-v1', promptVersion: 'prompt-v1', modelVersion: 'model-v1',
    embeddingVersion: 'embedding-v1', retrievalFormulaVersion: 'formula-v1',
    evidenceGateVersion: 'gate-v1', limitsVersion: 'limits-v1',
  },
  budget: {
    maxSteps: 6, maxAnswerAttempts: 2, maxInputTokens: 12_000, maxOutputTokens: 6_000,
    maxKnownCost: 1, deadlineAt: '2026-11-11T09:05:00Z',
  },
  usage: { steps: 0, answerAttempts: 0, inputTokens: 0, outputTokens: 0, knownCost: 0, unknownCostCalls: 0 },
  terminationReason: null,
  createdAt: '2026-11-11T09:00:00Z',
  completedAt: null,
  steps: [],
  toolCalls: [],
} satisfies AgentRun

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('agents API', () => {
  it('creates an owner-scoped run and polls its redacted trace', async () => {
    const request = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(202, run)))
    const api = createAgentsApi(request)

    await expect(api.create(jobId, '解释课程', 'agent-intent-1', 'csrf-agent')).resolves.toEqual(run)
    expect(request).toHaveBeenCalledWith(`/api/v1/analysis-jobs/${jobId}/agent-runs`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'Idempotency-Key': 'agent-intent-1',
        'X-CSRF-Token': 'csrf-agent',
      },
      body: JSON.stringify({ goal: '解释课程' }),
    })

    await api.get(run.runId)
    expect(request).toHaveBeenLastCalledWith(`/api/v1/agent-runs/${run.runId}`, {
      method: 'GET',
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    })

    const goalResult = { state: 'RUNNING', run, answer: null } satisfies GoalAnalysisResult
    request.mockResolvedValueOnce(jsonResponse(200, goalResult))
    await expect(api.getGoalAnalysisResult(jobId)).resolves.toEqual(goalResult)
    expect(request).toHaveBeenLastCalledWith(`/api/v1/analysis-jobs/${jobId}/goal-analysis-result`, {
      method: 'GET', credentials: 'same-origin', headers: { Accept: 'application/json' },
    })
  })

  it('preserves owner and malformed server failures for safe presentation', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce(jsonResponse(404, {
        code: 'RESOURCE_NOT_FOUND', message: 'not found', retryable: false, requestId: 'request-1',
      }))
      .mockResolvedValueOnce(new Response('not-json', { status: 500 }))
    const api = createAgentsApi(request)

    await expect(api.get(run.runId)).rejects.toMatchObject({ status: 404, code: 'RESOURCE_NOT_FOUND' })
    await expect(api.get(run.runId)).rejects.toMatchObject({ status: 500 })
  })
})
