package com.videopilot.analysis.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record AnalysisJob(
        UUID jobId,
        String ownerId,
        UUID mediaId,
        JobStatus status,
        Optional<JobStage> currentStage,
        int attempt,
        int retryCount,
        long version,
        long eventSequence,
        Optional<JobFailure> failure,
        Optional<Instant> nextRetryAt,
        Optional<Instant> executionLeaseUntil,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> completedAt) {

    public AnalysisJob {
        Objects.requireNonNull(jobId, "jobId");
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId must not be blank");
        }
        Objects.requireNonNull(mediaId, "mediaId");
        Objects.requireNonNull(status, "status");
        currentStage = Objects.requireNonNull(currentStage, "currentStage");
        failure = Objects.requireNonNull(failure, "failure");
        nextRetryAt = Objects.requireNonNull(nextRetryAt, "nextRetryAt");
        executionLeaseUntil = Objects.requireNonNull(executionLeaseUntil, "executionLeaseUntil");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (attempt < 0 || retryCount < 0 || version < 0 || eventSequence < 0) {
            throw new IllegalArgumentException("attempt, retryCount, version and eventSequence must not be negative");
        }
    }

    public AnalysisJob(
            UUID jobId,
            String ownerId,
            UUID mediaId,
            JobStatus status,
            Optional<JobStage> currentStage,
            int attempt,
            long version,
            long eventSequence,
            Optional<JobFailure> failure,
            Instant createdAt,
            Instant updatedAt,
            Optional<Instant> completedAt) {
        this(jobId, ownerId, mediaId, status, currentStage, attempt, 0, version, eventSequence,
                failure, Optional.empty(), Optional.empty(), createdAt, updatedAt, completedAt);
    }

    public AnalysisJob advance(
            JobStatus targetStatus,
            Optional<JobStage> stage,
            Optional<JobFailure> nextFailure,
            Instant now) {
        return advance(targetStatus, stage, nextFailure, Optional.empty(), now);
    }

    public AnalysisJob advance(
            JobStatus targetStatus,
            Optional<JobStage> stage,
            Optional<JobFailure> nextFailure,
            Optional<Instant> retryAt,
            Instant now) {
        Optional<Instant> completion = switch (targetStatus) {
            case COMPLETED, COMPLETED_WITH_WARNINGS, CANCELED -> Optional.of(now);
            default -> Optional.empty();
        };
        return new AnalysisJob(
                jobId,
                ownerId,
                mediaId,
                targetStatus,
                stage.isPresent() ? stage : currentStage,
                targetStatus == JobStatus.MANUAL_REPLAY ? attempt + 1 : attempt,
                targetStatus == JobStatus.RETRY_WAIT ? retryCount + 1 : retryCount,
                version + 1,
                eventSequence + 1,
                nextFailure,
                targetStatus == JobStatus.RETRY_WAIT ? retryAt : Optional.empty(),
                executionLeaseUntil,
                createdAt,
                now,
                completion);
    }

    public AnalysisJob withNextRetryAt(Instant retryAt) {
        return new AnalysisJob(jobId, ownerId, mediaId, status, currentStage, attempt, retryCount,
                version, eventSequence, failure, Optional.of(retryAt), executionLeaseUntil,
                createdAt, updatedAt, completedAt);
    }
}
