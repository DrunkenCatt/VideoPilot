import { describe, expect, it } from 'vitest'

import {
  API,
  CONTRACT_LIMITS,
  contextKeyframeAccessPath,
  contextPath,
  contextSegmentsPath,
  jobAnswerPath,
  jobAnswersPath,
  jobRetrievalPath,
  jobReplaysPath,
  mediaPlaybackPath,
  evaluationDatasetPath,
  evaluationDatasetRunsPath,
  evaluationRunReportPath,
  evaluationRunResultsPath,
  evaluationRunsComparisonPath,
  reliabilitySummaryPath,
  type AnswerResponse,
  type AnalysisJob,
  type EvidenceHit,
  type JobEvent,
  type JobReliability,
  type KeyframeId,
  type ReliabilitySummary,
  type ReplayRequest,
  type EvaluationDataset,
  type EvaluationRunRequest,
  type EvaluationCaseResult,
  type VideoContext,
  type VideoSegment,
} from './contracts'

describe('M1 API contract', () => {
  it('uses versioned resource paths and explicit retry headers', () => {
    expect(API.prefix).toBe('/api/v1')
    expect(API.sessions).toBe('/api/v1/sessions')
    expect(API.mediaUploads).toBe('/api/v1/media-uploads')
    expect(API.mediaAssets).toBe('/api/v1/media-assets')
    expect(API.analysisJobs).toBe('/api/v1/analysis-jobs')
    expect(API.idempotencyKeyHeader).toBe('Idempotency-Key')
    expect(API.lastEventIdHeader).toBe('Last-Event-ID')
  })

  it('freezes the upload and pagination limits', () => {
    expect(CONTRACT_LIMITS.maxUploadBytes).toBe(5 * 1024 ** 3)
    expect(CONTRACT_LIMITS.defaultPageSize).toBe(20)
    expect(CONTRACT_LIMITS.maxPageSize).toBe(100)
  })

  it('represents durable event cursors separately from job versions', () => {
    const job = { jobId: 'job-1', version: 3 } as AnalysisJob
    const event = { jobId: job.jobId, sequence: 7 } as JobEvent

    expect(event.sequence).toBe(7)
    expect(job.version).toBe(3)
  })
})

describe('M3 API contract', () => {
  it('uses job-scoped retrieval and answer resource paths', () => {
    const jobId = 'job-1' as AnalysisJob['jobId']

    expect(jobRetrievalPath(jobId)).toBe('/api/v1/analysis-jobs/job-1/retrieval')
    expect(jobAnswersPath(jobId)).toBe('/api/v1/analysis-jobs/job-1/answers')
    expect(jobAnswerPath(jobId, 'answer-1')).toBe('/api/v1/analysis-jobs/job-1/answers/answer-1')
  })

  it('keeps score provenance and degradation explicit on evidence hits', () => {
    const hit = {
      channels: ['ASR'],
      scores: { semanticRaw: 0.9, semanticNormalized: 1, keywordRaw: 0.6, keywordNormalized: 0.15, visualRaw: 0, visualNormalized: 0, total: 0.75 },
    } as EvidenceHit

    expect(hit.channels).toEqual(['ASR'])
    expect(hit.scores.total).toBe(0.75)
  })

  it('models full, bounded partial and refused answers as a discriminated union', () => {
    const answered = {
      status: 'ANSWERED',
      claims: [{ claimId: 'claim-1', text: 'A supported claim', citations: [{ citationId: 'citation-1' }] }],
    } as AnswerResponse
    const refused = {
      status: 'REFUSED',
      claims: [],
      refusalReason: 'NO_SUFFICIENT_EVIDENCE',
    } as AnswerResponse
    const partial = {
      status: 'PARTIALLY_ANSWERED',
      claims: answered.claims,
      refusalReason: null,
      missingRequirements: ['Unconfirmed use case'],
    } as AnswerResponse

    expect(answered.status === 'ANSWERED' && answered.claims[0]?.citations).toHaveLength(1)
    expect(partial.status === 'PARTIALLY_ANSWERED' && partial.missingRequirements)
      .toEqual(['Unconfirmed use case'])
    expect(refused.status === 'REFUSED' && refused.refusalReason).toBe('NO_SUFFICIENT_EVIDENCE')
  })
})

describe('M2 API contract', () => {
  it('uses job-scoped context and owner-checked object access paths', () => {
    expect(contextPath('job-1' as AnalysisJob['jobId'])).toBe('/api/v1/analysis-jobs/job-1/context')
    expect(contextSegmentsPath('job-1' as AnalysisJob['jobId'])).toBe('/api/v1/analysis-jobs/job-1/context/segments')
    expect(mediaPlaybackPath('media-1' as AnalysisJob['mediaId'])).toBe('/api/v1/media-assets/media-1/playback')
    expect(contextKeyframeAccessPath('job-1' as AnalysisJob['jobId'], 'frame-1' as KeyframeId)).toBe(
      '/api/v1/analysis-jobs/job-1/context/keyframes/frame-1/access',
    )
  })

  it('represents persisted channel degradation separately from context readiness', () => {
    const context = {
      status: 'READY',
      asrState: 'AVAILABLE',
      ocrState: 'DEGRADED',
    } as VideoContext
    const segment = { ordinal: 0, startMs: 0, endMs: 60_000 } as VideoSegment

    expect(context.status).toBe('READY')
    expect(context.ocrState).toBe('DEGRADED')
    expect(segment.endMs).toBe(60_000)
  })
})

describe('M4 API contract', () => {
  it('uses owner-scoped replay and read-only reliability paths', () => {
    const jobId = 'job-1' as AnalysisJob['jobId']

    expect(jobReplaysPath(jobId)).toBe('/api/v1/analysis-jobs/job-1/replays')
    expect(reliabilitySummaryPath()).toBe('/api/v1/operations/reliability')
  })

  it('keeps recovery state separate from the allowed operator action', () => {
    const reliability: JobReliability = {
      recoveryState: 'MANUAL_ACTION_REQUIRED',
      operatorAction: 'MANUAL_REPLAY',
      attempt: 2,
      nextRetryAt: null,
      reasonCode: 'RETRY_EXHAUSTED',
    }

    expect(reliability.recoveryState).toBe('MANUAL_ACTION_REQUIRED')
    expect(reliability.operatorAction).toBe('MANUAL_REPLAY')
  })

  it('requires an explicit replay confirmation and exposes unknown cost as null', () => {
    const replay: ReplayRequest = { expectedVersion: 7, confirmed: true, reason: 'Provider recovered.' }
    const summary = {
      providerCalls: [{ provider: 'SILICONFLOW', estimatedCost: null, currency: 'CNY' }],
      degradedSources: ['RABBITMQ'],
    } as ReliabilitySummary

    expect(replay.confirmed).toBe(true)
    expect(summary.providerCalls[0]?.estimatedCost).toBeNull()
    expect(summary.degradedSources).toEqual(['RABBITMQ'])
  })
})

describe('M5 API contract', () => {
  it('uses dataset-scoped run creation and run-scoped trace/report paths', () => {
    expect(evaluationDatasetPath('dataset-1')).toBe('/api/v1/evaluation-datasets/dataset-1')
    expect(evaluationDatasetRunsPath('dataset-1')).toBe('/api/v1/evaluation-datasets/dataset-1/runs')
    expect(evaluationRunResultsPath('run-1')).toBe('/api/v1/evaluation-runs/run-1/results')
    expect(evaluationRunReportPath('run-1')).toBe('/api/v1/evaluation-runs/run-1/report')
    expect(evaluationRunsComparisonPath()).toBe('/api/v1/evaluation-runs/comparison')
  })

  it('keeps test freezing, repetitions and all execution versions explicit', () => {
    const dataset = {
      split: 'TEST',
      status: 'FROZEN',
      approvedCaseCount: 150,
      totalCaseCount: 150,
    } as EvaluationDataset
    const request: EvaluationRunRequest = {
      baselineType: 'BASELINE_V1',
      repetitions: 3,
      versions: {
        codeCommit: 'c9b907e',
        pipelineVersion: 'm3-evidence-qa-v1',
        promptVersion: 'answer-evidence-v1',
        modelVersion: 'deepseek-ai/DeepSeek-V4-Flash',
        embeddingVersion: 'siliconflow-bge-m3-1024-v1',
        chunkVersion: 'chunk-5m-v1',
        retrievalFormulaVersion: 'hybrid-0.60-0.25-0.15-v1',
        evidenceGateVersion: 'citation-gate-v1',
        limitsVersion: 'm4-limits-v1',
        configurationVersion: 'm5-baseline-v1',
      },
    }

    expect(dataset.status).toBe('FROZEN')
    expect(dataset.approvedCaseCount).toBe(dataset.totalCaseCount)
    expect(request.repetitions).toBe(3)
    expect(Object.values(request.versions)).not.toContain('')
  })

  it('keeps case traces free of question, answer and prompt fields', () => {
    const result: EvaluationCaseResult = {
      caseId: 'release-r-001',
      caseType: 'RETRIEVAL',
      outcome: 'COMPLETED',
      rank: 1,
      claimCount: 2,
      citationCount: 2,
      supportedClaimCount: 2,
      localizedCitationCount: 2,
      latencyMs: 350,
      tokenCount: 120,
      estimatedCost: null,
      errorCode: null,
    }

    expect(Object.keys(result)).not.toEqual(expect.arrayContaining(['question', 'answer', 'prompt', 'response']))
  })
})
