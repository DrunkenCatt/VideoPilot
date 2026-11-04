import { describe, expect, it } from 'vitest'

import {
  API,
  CONTRACT_LIMITS,
  contextKeyframeAccessPath,
  contextPath,
  contextSegmentsPath,
  mediaPlaybackPath,
  type AnalysisJob,
  type JobEvent,
  type KeyframeId,
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
