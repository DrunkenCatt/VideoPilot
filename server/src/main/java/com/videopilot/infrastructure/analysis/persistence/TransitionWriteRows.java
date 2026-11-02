package com.videopilot.infrastructure.analysis.persistence;

import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.JobEvent;
import com.videopilot.analysis.domain.JobFailure;
import com.videopilot.analysis.domain.JobStageAttempt;
import com.videopilot.analysis.port.JobTransitionStore.TransitionCommit;
import java.time.Instant;

final class TransitionWriteRows {

    private TransitionWriteRows() {
    }

    static JobWrite job(TransitionCommit commit) {
        JobFailure failure = commit.nextJob().failure().orElse(null);
        return new JobWrite(
                commit.nextJob().jobId().toString(),
                commit.expectedStatus().name(),
                commit.expectedVersion(),
                commit.nextJob().status().name(),
                commit.nextJob().currentStage().map(Enum::name).orElse(null),
                failure == null ? null : failure.category().name(),
                failure == null ? null : failure.code(),
                failure == null ? null : failure.summary(),
                failure == null ? null : failure.retryable(),
                commit.nextJob().completedAt().orElse(null),
                commit.nextJob().updatedAt());
    }

    static StageWrite stage(JobStageAttempt stage) {
        JobFailure failure = stage.failure().orElse(null);
        return new StageWrite(
                stage.jobId().toString(),
                stage.stage().name(),
                stage.inputFingerprint(),
                stage.checkpointSchemaVersion(),
                stage.checkpointPayload().orElse(null),
                stage.artifactRef().orElse(null),
                stage.artifactDigest().orElse(null),
                stage.status().name(),
                stage.attempt(),
                failure == null ? null : failure.category().name(),
                failure == null ? null : failure.code(),
                failure == null ? null : failure.summary(),
                stage.startedAt(),
                stage.completedAt().orElse(null));
    }

    static EventWrite event(JobEvent event, AnalysisJob job) {
        return new EventWrite(
                event.messageId().toString(),
                event.jobId().toString(),
                event.eventType().name(),
                event.schemaVersion(),
                event.sequence(),
                event.requestId(),
                event.payload(),
                event.occurredAt(),
                job.status().name(),
                job.currentStage().map(Enum::name).orElse(null),
                publicMessage(event, job),
                isTerminal(job));
    }

    private static String publicMessage(JobEvent event, AnalysisJob job) {
        return switch (event.eventType()) {
            case JOB_CREATED -> "Analysis job created.";
            case JOB_QUEUED -> "Analysis job queued.";
            case STAGE_STARTED -> "Analysis stage started.";
            case STAGE_COMPLETED -> "Analysis stage completed.";
            case JOB_CANCEL_REQUESTED -> "Analysis job cancellation requested.";
            case JOB_CANCELED -> "Analysis job canceled.";
            case JOB_FAILED -> job.failure().map(JobFailure::summary).orElse("Analysis job failed.");
            case JOB_READY -> "Analysis job ready.";
        };
    }

    private static boolean isTerminal(AnalysisJob job) {
        return switch (job.status()) {
            case READY, COMPLETED, COMPLETED_WITH_WARNINGS, CANCELED, FAILED, DEAD_LETTERED -> true;
            default -> false;
        };
    }

    record JobWrite(
            String jobId,
            String expectedStatus,
            long expectedVersion,
            String status,
            String currentStage,
            String errorCategory,
            String errorCode,
            String errorSummary,
            Boolean retryable,
            Instant completedAt,
            Instant updatedAt) {
    }

    record StageWrite(
            String jobId,
            String stage,
            String inputFingerprint,
            int checkpointSchemaVersion,
            String checkpointPayload,
            String artifactRef,
            String artifactDigest,
            String status,
            int attempt,
            String errorCategory,
            String errorCode,
            String errorSummary,
            Instant startedAt,
            Instant completedAt) {
    }

    record EventWrite(
            String messageId,
            String jobId,
            String eventType,
            int schemaVersion,
            long sequence,
            String requestId,
            String payload,
            Instant occurredAt,
            String jobStatus,
            String jobStage,
            String publicMessage,
            boolean terminal) {
    }
}
