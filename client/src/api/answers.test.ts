import { describe, expect, it, vi } from 'vitest'

import type { AnswerPageResponse, AnswerResponse, JobId } from './contracts'
import { createAnswersApi } from './answers'

const jobId = '10000000-0000-0000-0000-000000000008' as JobId

const answer: AnswerResponse = {
  answerId: '60000000-0000-0000-0000-000000000008' as AnswerResponse['answerId'],
  status: 'REFUSED',
  question: 'Redis 有哪些数据结构？',
  claims: [],
  refusalReason: 'NO_SUFFICIENT_EVIDENCE',
  degradedChannels: [],
  versions: {
    promptVersion: 'answer-evidence-v1',
    modelVersion: 'deepseek-ai/DeepSeek-V4-Flash',
    embeddingVersion: 'BAAI/bge-m3',
    retrievalFormulaVersion: 'hybrid-v1',
    chunkVersion: 'chunk-v1',
  },
  createdAt: '2026-11-06T08:00:00Z',
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('answers API', () => {
  it('submits one owner-scoped question with stable idempotency and CSRF headers', async () => {
    const request = vi.fn().mockResolvedValue(jsonResponse(200, answer))
    const api = createAnswersApi(request)
    const signal = new AbortController().signal

    await expect(api.ask(jobId, { question: answer.question }, 'answer-intent-1', 'csrf-answer', signal))
      .resolves.toEqual(answer)

    expect(request).toHaveBeenCalledWith(`/api/v1/analysis-jobs/${jobId}/answers`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'Idempotency-Key': 'answer-intent-1',
        'X-CSRF-Token': 'csrf-answer',
      },
      body: JSON.stringify({ question: answer.question }),
      signal,
    })
  })

  it('restores a bounded answer page with an optional cursor', async () => {
    const page: AnswerPageResponse = { data: [answer], nextCursor: 9, hasMore: true }
    const request = vi.fn().mockResolvedValue(jsonResponse(200, page))
    const api = createAnswersApi(request)

    await expect(api.list(jobId, 11, 20)).resolves.toEqual(page)

    expect(request).toHaveBeenCalledWith(
      `/api/v1/analysis-jobs/${jobId}/answers?cursor=11&pageSize=20`,
      expect.objectContaining({ credentials: 'same-origin', headers: { Accept: 'application/json' } }),
    )
  })

  it('preserves authentication and dependency failures for distinct presentation', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce(jsonResponse(401, {
        code: 'AUTHENTICATION_REQUIRED', message: 'expired', retryable: false, requestId: 'request-1',
      }))
      .mockResolvedValueOnce(jsonResponse(503, {
        code: 'DEPENDENCY_UNAVAILABLE', message: 'model unavailable', retryable: true, requestId: 'request-2',
      }))
    const api = createAnswersApi(request)

    await expect(api.list(jobId)).rejects.toMatchObject({ status: 401, code: 'AUTHENTICATION_REQUIRED' })
    await expect(api.ask(jobId, { question: answer.question }, 'answer-intent-2', 'csrf-answer'))
      .rejects.toMatchObject({ status: 503, code: 'DEPENDENCY_UNAVAILABLE', retryable: true })
  })
})
