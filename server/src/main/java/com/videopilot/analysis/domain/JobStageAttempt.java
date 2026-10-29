package com.videopilot.analysis.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record JobStageAttempt(
        UUID jobId,
        JobStage stage,
        String inputFingerprint,
        int checkpointSchemaVersion,
        JobStageStatus status,
        int attempt,
        Optional<String> checkpointPayload,
        Optional<String> artifactRef,
        Optional<String> artifactDigest,
        Optional<JobFailure> failure,
        Instant startedAt,
        Optional<Instant> completedAt) {

    public JobStageAttempt {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(stage, "stage");
        if (inputFingerprint == null || inputFingerprint.isBlank() || inputFingerprint.length() > 128) {
            throw new IllegalArgumentException("inputFingerprint must contain 1 to 128 characters");
        }
        Objects.requireNonNull(status, "status");
        checkpointPayload = Objects.requireNonNull(checkpointPayload, "checkpointPayload");
        artifactRef = Objects.requireNonNull(artifactRef, "artifactRef");
        artifactDigest = Objects.requireNonNull(artifactDigest, "artifactDigest");
        failure = Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(startedAt, "startedAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (checkpointSchemaVersion < 1 || attempt < 1) {
            throw new IllegalArgumentException("schema version and attempt must be positive");
        }
    }

    public static JobStageAttempt started(
            UUID jobId,
            JobStage stage,
            String inputFingerprint,
            int checkpointSchemaVersion,
            Instant startedAt) {
        return new JobStageAttempt(
                jobId,
                stage,
                inputFingerprint,
                checkpointSchemaVersion,
                JobStageStatus.STARTED,
                1,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                startedAt,
                Optional.empty());
    }
}
