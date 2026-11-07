package com.videopilot.analysis.application;

import com.videopilot.analysis.domain.JobEventType;
import com.videopilot.analysis.domain.JobFailure;
import com.videopilot.analysis.domain.JobStageAttempt;
import com.videopilot.analysis.domain.JobStatus;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import com.videopilot.analysis.domain.JobEvent.OperationMetadata;

public record JobTransitionCommand(
        UUID jobId,
        JobStatus expectedStatus,
        long expectedVersion,
        JobStatus targetStatus,
        Optional<JobStageAttempt> stageAttempt,
        Optional<JobFailure> failure,
        JobEventType eventType,
        String requestId,
        String eventPayload,
        Optional<Instant> nextRetryAt,
        Optional<OperationMetadata> operation) {

    public JobTransitionCommand {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(expectedStatus, "expectedStatus");
        Objects.requireNonNull(targetStatus, "targetStatus");
        stageAttempt = Objects.requireNonNull(stageAttempt, "stageAttempt");
        failure = Objects.requireNonNull(failure, "failure");
        nextRetryAt = Objects.requireNonNull(nextRetryAt, "nextRetryAt");
        operation = Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(eventType, "eventType");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (stageAttempt.isPresent() && !stageAttempt.orElseThrow().jobId().equals(jobId)) {
            throw new IllegalArgumentException("stageAttempt must belong to the transitioned job");
        }
        if ((targetStatus == JobStatus.FAILED || targetStatus == JobStatus.RETRY_WAIT)
                && failure.isEmpty()) {
            throw new IllegalArgumentException(targetStatus + " transition requires a stable failure");
        }
    }

    public JobTransitionCommand(
            UUID jobId,
            JobStatus expectedStatus,
            long expectedVersion,
            JobStatus targetStatus,
            Optional<JobStageAttempt> stageAttempt,
            Optional<JobFailure> failure,
            JobEventType eventType,
            String requestId,
            String eventPayload) {
        this(jobId, expectedStatus, expectedVersion, targetStatus, stageAttempt, failure,
                eventType, requestId, eventPayload, Optional.empty(), Optional.empty());
    }

    public JobTransitionCommand(
            UUID jobId, JobStatus expectedStatus, long expectedVersion, JobStatus targetStatus,
            Optional<JobStageAttempt> stageAttempt, Optional<JobFailure> failure,
            JobEventType eventType, String requestId, String eventPayload,
            Optional<Instant> nextRetryAt) {
        this(jobId, expectedStatus, expectedVersion, targetStatus, stageAttempt, failure,
                eventType, requestId, eventPayload, nextRetryAt, Optional.empty());
    }
}
