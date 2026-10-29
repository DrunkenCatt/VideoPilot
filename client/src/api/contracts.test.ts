import { describe, expect, it } from 'vitest'

import { API, CONTRACT_LIMITS, type AnalysisJob, type JobEvent } from './contracts'

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
