import { describe, expect, it, vi } from 'vitest'

import type { EvaluationReport } from './contracts'
import { compareEvaluationReports, createEvaluationApi, evaluationExport } from './evaluation'

const report = {
  runId: '00000000-0000-0000-0000-000000000101',
  datasetId: '00000000-0000-0000-0000-000000000102',
  datasetFingerprint: 'a'.repeat(64),
  executionFingerprint: 'b'.repeat(64),
  versions: { codeCommit: 'commit', pipelineVersion: 'pipeline', promptVersion: 'prompt', modelVersion: 'model', embeddingVersion: 'embedding', chunkVersion: 'chunk', retrievalFormulaVersion: 'formula', evidenceGateVersion: 'gate', limitsVersion: 'limits', configurationVersion: 'config' },
  metrics: [{ name: 'recallAt3', numerator: 1, denominator: 2, value: 0.5, exclusions: {} }],
  usage: { totalCalls: 2, inputUnits: 10, outputUnits: 4, latencyMs: 100, estimatedCost: null, currency: null, unavailablePriceCalls: 2, callOutcomes: { SUCCEEDED: 2 } },
  series: {},
  generatedAt: '2026-11-08T00:00:00Z',
} as unknown as EvaluationReport

describe('evaluation API', () => {
  it('uses owner-scoped dataset, run, trace and report endpoints', async () => {
    const request = vi.fn().mockImplementation(() =>
      Promise.resolve(new Response(JSON.stringify([]), { status: 200 })))
    const api = createEvaluationApi(request)
    await api.listDatasets()
    await api.listRuns(report.datasetId)
    await api.results(report.runId)
    request.mockResolvedValueOnce(new Response(JSON.stringify(report), { status: 200 }))
    await expect(api.report(report.runId)).resolves.toEqual(report)
    expect(request.mock.calls.map((call) => call[0])).toEqual([
      '/api/v1/evaluation-datasets',
      `/api/v1/evaluation-datasets/${report.datasetId}/runs`,
      `/api/v1/evaluation-runs/${report.runId}/results`,
      `/api/v1/evaluation-runs/${report.runId}/report`,
    ])
  })

  it('creates runs with CSRF and idempotency without leaking either into the body', async () => {
    const request = vi.fn().mockResolvedValue(new Response(JSON.stringify([]), { status: 201 }))
    const input = { baselineType: 'BASELINE_V1' as const, repetitions: 2 as const, versions: report.versions }
    await createEvaluationApi(request).createRuns(report.datasetId, input, 'intent-1', 'csrf-1')
    expect(request).toHaveBeenCalledWith(
      `/api/v1/evaluation-datasets/${report.datasetId}/runs`,
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'Idempotency-Key': 'intent-1', 'X-CSRF-Token': 'csrf-1' }),
        body: JSON.stringify(input),
      }),
    )
  })

  it('maps structured and malformed failures to the shared client error', async () => {
    const structured = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'RESOURCE_NOT_FOUND', message: 'missing', retryable: false, requestId: 'request-1',
    }), { status: 404, headers: { 'Content-Type': 'application/json' } }))
    await expect(createEvaluationApi(structured).getRun(report.runId)).rejects.toMatchObject({
      status: 404, code: 'RESOURCE_NOT_FOUND',
    })
    const malformed = vi.fn().mockResolvedValue(new Response('not-json', { status: 503 }))
    await expect(createEvaluationApi(malformed).getRun(report.runId)).rejects.toMatchObject({ status: 503 })
  })

  it('computes neutral deltas and exports only redacted report and trace fields', () => {
    const candidate = structuredClone(report)
    candidate.metrics[0]!.value = 0.75
    candidate.usage.latencyMs = 80
    const comparison = compareEvaluationReports(report, candidate)
    expect(comparison.metricDeltas).toEqual([{ name: 'recallAt3', base: 0.5, candidate: 0.75, delta: 0.25 }])
    expect(comparison.usageDeltas.latencyMs).toBe(-20)
    const exported = evaluationExport(report, [{
      caseId: 'case-1', caseType: 'RETRIEVAL', outcome: 'COMPLETED', rank: 1,
      claimCount: 0, citationCount: 0, supportedClaimCount: 0, localizedCitationCount: 0,
      latencyMs: 12, tokenCount: 0, estimatedCost: null, errorCode: null,
    }])
    expect(exported).toEqual({ report, results: expect.any(Array) })
    expect(JSON.stringify(exported)).not.toMatch(/question|promptText|transcript|rawResponse/)
  })
})
