type Brand<T, Name extends string> = T & { readonly __brand: Name }

export type UserId = Brand<string, 'UserId'>
export type MediaId = Brand<string, 'MediaId'>
export type UploadId = Brand<string, 'UploadId'>
export type JobId = Brand<string, 'JobId'>
export type ContextId = Brand<string, 'ContextId'>
export type SegmentId = Brand<string, 'SegmentId'>
export type KeyframeId = Brand<string, 'KeyframeId'>
export type ChunkId = Brand<string, 'ChunkId'>
export type AnswerId = Brand<string, 'AnswerId'>
export type ClaimId = Brand<string, 'ClaimId'>
export type CitationId = Brand<string, 'CitationId'>
export type EvaluationDatasetId = Brand<string, 'EvaluationDatasetId'>
export type EvaluationRunId = Brand<string, 'EvaluationRunId'>

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
  defaultSegmentPageSize: 100,
  maxSegmentPageSize: 500,
  defaultRetrievalTopK: 5,
  maxRetrievalTopK: 20,
  maxQueryLength: 1_000,
  defaultAnswerPageSize: 20,
  maxAnswerPageSize: 100,
  maxClaimsPerAnswer: 8,
  maxReplayReasonLength: 500,
} as const

export function contextPath(jobId: JobId): string {
  return `${API.analysisJobs}/${jobId}/context`
}

export function contextSegmentsPath(jobId: JobId): string {
  return `${contextPath(jobId)}/segments`
}

export function mediaPlaybackPath(mediaId: MediaId): string {
  return `${API.mediaAssets}/${mediaId}/playback`
}

export function contextKeyframeAccessPath(jobId: JobId, keyframeId: KeyframeId): string {
  return `${contextPath(jobId)}/keyframes/${keyframeId}/access`
}

export function jobRetrievalPath(jobId: JobId): string {
  return `${API.analysisJobs}/${jobId}/retrieval`
}

export function jobAnswersPath(jobId: JobId): string {
  return `${API.analysisJobs}/${jobId}/answers`
}

export function jobAnswerPath(jobId: JobId, answerId: AnswerId | string): string {
  return `${jobAnswersPath(jobId)}/${answerId}`
}

export function jobReplaysPath(jobId: JobId): string {
  return `${API.analysisJobs}/${jobId}/replays`
}

export function reliabilitySummaryPath(): string {
  return `${API.prefix}/operations/reliability`
}

export function evaluationDatasetPath(datasetId: EvaluationDatasetId | string): string {
  return `${API.prefix}/evaluation-datasets/${datasetId}`
}

export function evaluationDatasetRunsPath(datasetId: EvaluationDatasetId | string): string {
  return `${evaluationDatasetPath(datasetId)}/runs`
}

export function evaluationRunResultsPath(runId: EvaluationRunId | string): string {
  return `${API.prefix}/evaluation-runs/${runId}/results`
}

export function evaluationRunReportPath(runId: EvaluationRunId | string): string {
  return `${API.prefix}/evaluation-runs/${runId}/report`
}

export function evaluationRunsComparisonPath(): string {
  return `${API.prefix}/evaluation-runs/comparison`
}

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
  | 'RATE_LIMITED'
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
  failure: MediaFailure | null
  createdAt: string
  updatedAt: string
}

export interface MediaFailure {
  code: string
  retryable: boolean
  summary: string
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
  reliability: JobReliability
  createdAt: string
  updatedAt: string
  completedAt: string | null
  links: {
    self: string
    events: string
    cancellations: string
  }
}

export type RecoveryState =
  | 'NONE'
  | 'AUTO_RECOVERING'
  | 'RETRY_WAIT'
  | 'MANUAL_ACTION_REQUIRED'
  | 'NON_RECOVERABLE'
  | 'BUDGET_EXHAUSTED'

export type OperatorAction = 'NONE' | 'WAIT' | 'MANUAL_REPLAY'

export interface JobReliability {
  recoveryState: RecoveryState
  operatorAction: OperatorAction
  attempt: number
  nextRetryAt: string | null
  reasonCode: string | null
}

export interface ReplayRequest {
  expectedVersion: number
  confirmed: true
  reason: string
}

export interface ReplayResponse {
  jobId: JobId
  attempt: number
  status: 'ACCEPTED'
  acceptedAt: string
}

export interface CountByCode {
  code: string
  count: number
}

export interface StageDuration {
  stage: string
  p50Ms: number
  p95Ms: number
}

export interface ProviderCalls {
  provider: string
  totalCalls: number
  failedCalls: number
  inputUnits: number
  outputUnits: number
  estimatedCost: number | null
  currency: string | null
}

export type DegradedOperationsSource = 'MYSQL' | 'RABBITMQ'

export interface ReliabilitySummary {
  windowStart: string
  windowEnd: string
  pendingOutbox: number | null
  queueBacklog: number | null
  failureCounts: CountByCode[]
  stageDurations: StageDuration[]
  providerCalls: ProviderCalls[]
  degradedSources: DegradedOperationsSource[]
  generatedAt: string
}

export type EvaluationDatasetSplit = 'DEV' | 'TEST'
export type EvaluationDatasetStatus = 'DRAFT' | 'FROZEN'
export type EvaluationBaselineType = 'BASELINE_V1'
export type EvaluationRunStatus = 'REQUESTED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
export type EvaluationCaseType = 'RETRIEVAL' | 'ANSWER' | 'RELIABILITY'
export type EvaluationCaseOutcome = 'COMPLETED' | 'REFUSED' | 'ERROR' | 'UNKNOWN'

export interface EvaluationExecutionVersions {
  codeCommit: string
  pipelineVersion: string
  promptVersion: string
  modelVersion: string
  embeddingVersion: string
  chunkVersion: string
  retrievalFormulaVersion: string
  evidenceGateVersion: string
  limitsVersion: string
  configurationVersion: string
}

export interface EvaluationRunRequest {
  baselineType: EvaluationBaselineType
  repetitions: 1 | 2 | 3
  versions: EvaluationExecutionVersions
}

export interface EvaluationDataset {
  datasetId: EvaluationDatasetId
  dataVersion: string
  datasetFingerprint: string
  split: EvaluationDatasetSplit
  status: EvaluationDatasetStatus
  retrievalCaseCount: number
  answerCaseCount: number
  videoCount: number
  approvedCaseCount: number
  totalCaseCount: number
  frozenAt: string | null
}

export interface EvaluationRun {
  runId: EvaluationRunId
  datasetId: EvaluationDatasetId
  baselineType: EvaluationBaselineType
  status: EvaluationRunStatus
  executionFingerprint: string
  repetitionIndex: number
  repetitionCount: number
  completedCaseCount: number
  totalCaseCount: number
  createdAt: string
  completedAt: string | null
}

export interface EvaluationCaseResult {
  caseId: string
  caseType: EvaluationCaseType
  outcome: EvaluationCaseOutcome
  rank: number | null
  claimCount: number
  citationCount: number
  latencyMs: number
  tokenCount: number
  estimatedCost: number | null
  errorCode: string | null
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

export type ContextStatus = 'BUILDING' | 'READY' | 'FAILED'
export type ContextChannelState = 'PENDING' | 'AVAILABLE' | 'DEGRADED' | 'FAILED'

export interface VideoContext {
  contextId: ContextId
  jobId: JobId
  mediaId: MediaId
  status: ContextStatus
  extractionVersion: string
  alignmentVersion: string
  durationMs: number
  asrState: ContextChannelState
  ocrState: ContextChannelState
  segmentCount: number
  updatedAt: string
  links: {
    segments: string
    playback: string
  }
}

export interface OcrObservation {
  text: string
  confidence: number
}

export interface KeyframeReference {
  keyframeId: KeyframeId
  timestampMs: number
  access: string
}

export interface VideoSegment {
  segmentId: SegmentId
  ordinal: number
  startMs: number
  endMs: number
  asrText: string | null
  ocr: OcrObservation[]
  keyframes: KeyframeReference[]
}

export interface SegmentPageResponse {
  data: VideoSegment[]
  nextCursor: number | null
  hasMore: boolean
}

export interface TemporaryObjectAccess {
  url: string
  expiresAt: string
}

export type EvidenceChannel = 'ASR' | 'OCR' | 'KEYFRAME'
export type DegradedChannel = 'VECTOR_STORE' | 'SEMANTIC' | 'OCR'

export interface EvidenceScoreBreakdown {
  semanticRaw: number
  semanticNormalized: number
  keywordRaw: number
  keywordNormalized: number
  visualRaw: number
  visualNormalized: number
  total: number
}

export interface EvidenceHit {
  chunkId: ChunkId
  segmentIds: SegmentId[]
  startMs: number
  endMs: number
  excerpt: string
  channels: EvidenceChannel[]
  scores: EvidenceScoreBreakdown
}

export interface RetrievalResponse {
  query: string
  retrievalFormulaVersion: string
  embeddingVersion: string
  chunkVersion: string
  degradedChannels: DegradedChannel[]
  hits: EvidenceHit[]
}

export interface AnswerRequest {
  question: string
}

export interface CitedEvidence {
  citationId: CitationId
  segmentId: SegmentId
  startMs: number
  endMs: number
  channel: EvidenceChannel
  quote: string
}

export interface AnswerClaim {
  claimId: ClaimId
  text: string
  citations: CitedEvidence[]
}

export interface AnswerExecutionVersions {
  promptVersion: string
  modelVersion: string
  embeddingVersion: string
  retrievalFormulaVersion: string
  chunkVersion: string
}

interface AnswerResponseBase {
  answerId: AnswerId
  question: string
  degradedChannels: DegradedChannel[]
  versions: AnswerExecutionVersions
  createdAt: string
}

export interface AnsweredResponse extends AnswerResponseBase {
  status: 'ANSWERED'
  claims: AnswerClaim[]
  refusalReason: null
}

export type RefusalReason =
  | 'NO_SUFFICIENT_EVIDENCE'
  | 'EVIDENCE_BELOW_THRESHOLD'
  | 'CITATION_VALIDATION_FAILED'
  | 'QUESTION_OUT_OF_SCOPE'

export interface RefusedResponse extends AnswerResponseBase {
  status: 'REFUSED'
  claims: []
  refusalReason: RefusalReason
}

export type AnswerResponse = AnsweredResponse | RefusedResponse

export interface AnswerPageResponse {
  data: AnswerResponse[]
  nextCursor: number | null
  hasMore: boolean
}
