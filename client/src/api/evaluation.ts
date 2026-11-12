import {
  API,
  type EvaluationCaseResult,
  type EvaluationDataset,
  type EvaluationDatasetId,
  type EvaluationReport,
  type EvaluationPromotionDecision,
  type EvaluationRun,
  type EvaluationRunId,
  type EvaluationRunRequest,
  evaluationDatasetRunsPath,
  evaluationRunReportPath,
  evaluationRunResultsPath,
  evaluationRunsComparisonPath,
} from './contracts'
import { ApiClientError } from './session'

type Request = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

async function json<T>(request: Request, path: string, init: RequestInit): Promise<T> {
  const response = await request(path, init)
  if (!response.ok) {
    try {
      throw new ApiClientError(response.status, await response.json())
    } catch (error) {
      if (error instanceof ApiClientError) throw error
      throw new ApiClientError(response.status)
    }
  }
  return (await response.json()) as T
}

const read: RequestInit = { method: 'GET', credentials: 'same-origin', headers: { Accept: 'application/json' } }

export function createEvaluationApi(request: Request = fetch) {
  return {
    listDatasets: () => json<EvaluationDataset[]>(request, `${API.prefix}/evaluation-datasets`, read),
    listRuns: (datasetId: EvaluationDatasetId | string) =>
      json<EvaluationRun[]>(request, evaluationDatasetRunsPath(datasetId), read),
    getRun: (runId: EvaluationRunId | string) =>
      json<EvaluationRun>(request, `${API.prefix}/evaluation-runs/${runId}`, read),
    results: (runId: EvaluationRunId | string) =>
      json<EvaluationCaseResult[]>(request, evaluationRunResultsPath(runId), read),
    report: (runId: EvaluationRunId | string) =>
      json<EvaluationReport>(request, evaluationRunReportPath(runId), read),
    comparison: (baseRunId: EvaluationRunId | string, candidateRunId: EvaluationRunId | string) =>
      json<EvaluationPromotionDecision>(request,
        `${evaluationRunsComparisonPath()}?baseRunId=${encodeURIComponent(baseRunId)}&candidateRunId=${encodeURIComponent(candidateRunId)}`, read),
    createRuns: (
      datasetId: EvaluationDatasetId | string,
      input: EvaluationRunRequest,
      idempotencyKey: string,
      csrfToken: string,
    ) => json<EvaluationRun[]>(request, evaluationDatasetRunsPath(datasetId), {
      method: 'POST', credentials: 'same-origin',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey, 'X-CSRF-Token': csrfToken },
      body: JSON.stringify(input),
    }),
  }
}

export type EvaluationApi = ReturnType<typeof createEvaluationApi>

export function compareEvaluationReports(base: EvaluationReport, candidate: EvaluationReport) {
  const baseMetrics = new Map(base.metrics.map((metric) => [metric.name, metric.value]))
  return {
    metricDeltas: candidate.metrics.map((metric) => {
      const baseValue = baseMetrics.get(metric.name) ?? null
      return {
        name: metric.name,
        base: baseValue,
        candidate: metric.value,
        delta: baseValue === null || metric.value === null ? null : metric.value - baseValue,
      }
    }),
    usageDeltas: {
      latencyMs: candidate.usage.latencyMs - base.usage.latencyMs,
      inputUnits: candidate.usage.inputUnits - base.usage.inputUnits,
      outputUnits: candidate.usage.outputUnits - base.usage.outputUnits,
      totalCalls: candidate.usage.totalCalls - base.usage.totalCalls,
      estimatedCost: base.usage.estimatedCost === null || candidate.usage.estimatedCost === null
        ? null : candidate.usage.estimatedCost - base.usage.estimatedCost,
    },
  }
}

export function evaluationExport(report: EvaluationReport, results: EvaluationCaseResult[]) {
  return { report, results }
}
