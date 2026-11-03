package com.videopilot.infrastructure.web.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class M1ApiContracts {

    public static final String API_PREFIX = "/api/v1";
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";
    public static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024 * 1024;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private M1ApiContracts() {
    }

    public static final class Paths {

        public static final String SESSIONS = API_PREFIX + "/sessions";
        public static final String MEDIA_UPLOADS = API_PREFIX + "/media-uploads";
        public static final String MEDIA_ASSETS = API_PREFIX + "/media-assets";
        public static final String ANALYSIS_JOBS = API_PREFIX + "/analysis-jobs";

        private Paths() {
        }
    }

    public enum AnalysisMode {
        STANDARD
    }

    public enum MediaStatus {
        UPLOAD_PENDING,
        UPLOADED,
        PROBING,
        READY,
        FAILED
    }

    public enum JobStatus {
        CREATED,
        QUEUED,
        CONSUMING,
        PROBING,
        CONTEXT_BUILDING,
        INDEXING,
        READY,
        ANSWERING,
        VERIFYING,
        COMPLETED,
        COMPLETED_WITH_WARNINGS,
        RETRY_WAIT,
        CANCEL_REQUESTED,
        CANCELED,
        FAILED,
        DEAD_LETTERED,
        MANUAL_REPLAY
    }

    public enum JobStage {
        PROBING,
        CONTEXT_BUILDING,
        INDEXING,
        ANSWERING,
        VERIFYING
    }

    public enum JobEventType {
        JOB_CREATED,
        JOB_QUEUED,
        STAGE_STARTED,
        STAGE_COMPLETED,
        JOB_CANCEL_REQUESTED,
        JOB_CANCELED,
        JOB_FAILED,
        JOB_READY
    }

    public record LoginRequest(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(max = 256) String password) {
    }

    public record SessionResponse(
            UUID userId,
            String username,
            String csrfToken,
            Instant expiresAt) {
    }

    public record UploadSessionRequest(
            @NotBlank @Size(max = 512) String originalFilename,
            @Positive @Max(MAX_UPLOAD_BYTES) long contentLength,
            @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{64}$") String sha256,
            @NotBlank @Pattern(regexp = "^(video/[A-Za-z0-9.+-]+|application/octet-stream)$") String contentType) {
    }

    public record UploadSessionResponse(
            UUID uploadId,
            UUID mediaId,
            long partSizeBytes,
            int maxParts,
            Instant expiresAt,
            String partsUrl,
            String completionUrl) {
    }

    public record UploadPartRequest(@Min(1) @Max(10_000) int partNumber) {
    }

    public record UploadPartResponse(
            int partNumber,
            String uploadUrl,
            Instant expiresAt) {
    }

    public record CompletedPart(
            @Min(1) @Max(10_000) int partNumber,
            @NotBlank @Size(max = 512) String etag) {
    }

    public record CompleteUploadRequest(
            @NotEmpty @Size(max = 10_000) List<@Valid CompletedPart> parts) {
    }

    public record MediaAssetResponse(
            UUID mediaId,
            String originalFilename,
            long contentLength,
            String sha256,
            MediaStatus status,
            Long durationMs,
            MediaFailure failure,
            Instant createdAt,
            Instant updatedAt) {

        public MediaAssetResponse(
                UUID mediaId,
                String originalFilename,
                long contentLength,
                String sha256,
                MediaStatus status,
                Long durationMs,
                Instant createdAt,
                Instant updatedAt) {
            this(mediaId, originalFilename, contentLength, sha256, status,
                    durationMs, null, createdAt, updatedAt);
        }
    }

    public record MediaFailure(
            String code,
            boolean retryable,
            String summary) {
    }

    public record CreateAnalysisJobRequest(
            @NotNull UUID mediaId,
            @NotBlank @Size(max = 4_000) String goal,
            @NotNull AnalysisMode mode) {
    }

    public record JobFailure(
            String code,
            boolean retryable,
            String summary) {
    }

    public record JobLinks(
            String self,
            String events,
            String cancellations) {
    }

    public record AnalysisJobResponse(
            UUID jobId,
            UUID mediaId,
            String goal,
            AnalysisMode mode,
            JobStatus status,
            JobStage currentStage,
            int attempt,
            long version,
            JobFailure failure,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            JobLinks links) {
    }

    public record JobEventResponse(
            UUID jobId,
            long sequence,
            JobEventType eventType,
            JobStatus status,
            JobStage stage,
            String message,
            Instant occurredAt,
            boolean terminal) {
    }

    public record PageMetadata(
            int page,
            int pageSize,
            long totalItems,
            int totalPages) {
    }

    public record PageResponse<T>(
            List<T> data,
            PageMetadata pagination) {
    }
}
