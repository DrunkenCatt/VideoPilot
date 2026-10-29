type Brand<T, Name extends string> = T & { readonly __brand: Name }

export type UserId = Brand<string, 'UserId'>
export type MediaId = Brand<string, 'MediaId'>
export type UploadId = Brand<string, 'UploadId'>
export type JobId = Brand<string, 'JobId'>

export const API = {
  prefix: '/api/v1',
  sessions: '/api/v1/sessions',
  mediaUploads: '/api/v1/media-uploads',
  mediaAssets: '/api/v1/media-assets',
  analysisJobs: '/api/v1/analysis-jobs',
  idempotencyKeyHeader: 'Idempotency-Key',
  lastEventIdHeader: 'Last-Event-ID',
} as const

export const CONTRACT_LIMITS = {
  maxUploadBytes: 5 * 1024 ** 3,
  defaultPageSize: 20,
  maxPageSize: 100,
} as const

export type AnalysisMode = 'STANDARD'
export type MediaStatus = 'UPLOAD_PENDING' | 'UPLOADED' | 'PROBING' | 'READY' | 'FAILED'
export type JobStatus =
  | 'CREATED'
  | 'QUEUED'
  | 'CONSUMING'
  | 'PROBING'
  | 'CONTEXT_BUILDING'
  | 'INDEXING'
  | 'READY'
  | 'ANSWERING'
  | 'VERIFYING'
  | 'COMPLETED'
  | 'COMPLETED_WITH_WARNINGS'
  | 'RETRY_WAIT'
  | 'CANCEL_REQUESTED'
  | 'CANCELED'
  | 'FAILED'
  | 'DEAD_LETTERED'
  | 'MANUAL_REPLAY'
export type JobStage = 'PROBING' | 'CONTEXT_BUILDING' | 'INDEXING' | 'ANSWERING' | 'VERIFYING'
export type JobEventType =
  | 'JOB_CREATED'
  | 'JOB_QUEUED'
  | 'STAGE_STARTED'
  | 'STAGE_COMPLETED'
  | 'JOB_CANCEL_REQUESTED'
  | 'JOB_CANCELED'
  | 'JOB_FAILED'
  | 'JOB_READY'

export type ApiErrorCode =
  | 'INVALID_REQUEST'
  | 'AUTHENTICATION_REQUIRED'
  | 'INVALID_CREDENTIALS'
  | 'CSRF_REJECTED'
  | 'RESOURCE_NOT_FOUND'
  | 'IDEMPOTENCY_KEY_REUSED'
  | 'UPLOAD_EXPIRED'
  | 'MEDIA_NOT_READY'
  | 'JOB_NOT_CANCELABLE'
  | 'CONFLICT'
  | 'DEPENDENCY_UNAVAILABLE'
  | 'INTERNAL_ERROR'

export interface ApiError {
  code: ApiErrorCode
  message: string
  retryable: boolean
  requestId: string
}

export interface LoginRequest {
  username: string
  password: string
}

export interface Session {
  userId: UserId
  username: string
  csrfToken: string
  expiresAt: string
}

export interface UploadSessionRequest {
  originalFilename: string
  contentLength: number
  sha256: string
  contentType: string
}

export interface UploadSession {
  uploadId: UploadId
  mediaId: MediaId
  partSizeBytes: number
  maxParts: number
  expiresAt: string
  partsUrl: string
  completionUrl: string
}

export interface UploadPartTarget {
  partNumber: number
  uploadUrl: string
  expiresAt: string
}

export interface CompletedPart {
  partNumber: number
  etag: string
}

export interface CompleteUploadRequest {
  parts: CompletedPart[]
}

export interface MediaAsset {
  mediaId: MediaId
  originalFilename: string
  contentLength: number
  sha256: string
  status: MediaStatus
  durationMs: number | null
  createdAt: string
  updatedAt: string
}

export interface CreateAnalysisJobRequest {
  mediaId: MediaId
  goal: string
  mode: AnalysisMode
}

export interface JobFailure {
  code: string
  retryable: boolean
  summary: string
}

export interface AnalysisJob {
  jobId: JobId
  mediaId: MediaId
  goal: string
  mode: AnalysisMode
  status: JobStatus
  currentStage: JobStage | null
  attempt: number
  version: number
  failure: JobFailure | null
  createdAt: string
  updatedAt: string
  completedAt: string | null
  links: {
    self: string
    events: string
    cancellations: string
  }
}

export interface JobEvent {
  jobId: JobId
  sequence: number
  eventType: JobEventType
  status: JobStatus
  stage: JobStage | null
  message: string
  occurredAt: string
  terminal: boolean
}

export interface PageMetadata {
  page: number
  pageSize: number
  totalItems: number
  totalPages: number
}

export interface PageResponse<T> {
  data: T[]
  pagination: PageMetadata
}
