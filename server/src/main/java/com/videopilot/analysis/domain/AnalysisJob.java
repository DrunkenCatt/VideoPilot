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
        long version,
        long eventSequence,
        Optional<JobFailure> failure,
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
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (attempt < 0 || version < 0 || eventSequence < 0) {
            throw new IllegalArgumentException("attempt, version and eventSequence must not be negative");
        }
    }

    public AnalysisJob advance(
            JobStatus targetStatus,
            Optional<JobStage> stage,
            Optional<JobFailure> nextFailure,
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
                attempt,
                version + 1,
                eventSequence + 1,
                nextFailure,
                createdAt,
                now,
                completion);
    }
}
