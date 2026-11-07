package com.videopilot.analysis.application;

import com.videopilot.analysis.domain.AnalysisDispatchMessage;
import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.JobEventType;
import com.videopilot.analysis.domain.JobErrorCategory;
import com.videopilot.analysis.domain.JobFailure;
import com.videopilot.analysis.domain.JobStage;
import com.videopilot.analysis.domain.JobStageAttempt;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.analysis.port.JobExecutionLock;
import com.videopilot.analysis.port.JobTransitionStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class AnalysisJobExecutionHandler {

    private static final int MAX_TRANSITION_ATTEMPTS = 12;
    private static final String FINGERPRINT_VERSION = "m2-context-pipeline-v1";

    private final JobTransitionStore store;
    private final JobTransitionService transitions;
    private final AnalysisJobReliabilityService reliability;
    private final JobExecutionLock lock;
    private final AnalysisJobPipeline pipeline;
    private final Supplier<Instant> now;

    public AnalysisJobExecutionHandler(
            JobTransitionStore store,
            JobTransitionService transitions,
            AnalysisJobReliabilityService reliability,
            JobExecutionLock lock,
            AnalysisJobPipeline pipeline,
            Supplier<Instant> now) {
        this.store = store;
        this.transitions = transitions;
        this.reliability = reliability;
        this.lock = lock;
        this.pipeline = pipeline;
        this.now = now;
    }

    public AnalysisJobExecutionHandler(
            JobTransitionStore store,
            JobTransitionService transitions,
            JobExecutionLock lock,
            AnalysisJobPipeline pipeline,
            Supplier<Instant> now) {
        this(store, transitions, null, lock, pipeline, now);
    }

    public Result handle(AnalysisDispatchMessage message) {
        if (store.findById(message.jobId()).isEmpty()) {
            return Result.DEAD_LETTER;
        }
        try (JobExecutionLock.LockAttempt lockAttempt = lock.tryAcquire(message.jobId())) {
            if (lockAttempt.result() == JobExecutionLock.AcquireResult.BUSY) {
                return Result.RETRY;
            }
            if (reliability == null) {
                return claimExecution(message);
            }
            return reliability.executeWithLease(message.jobId(), () -> claimExecution(message))
                    .orElse(Result.ACK);
        }
    }

    private Result claimExecution(AnalysisDispatchMessage message) {
        for (int attempt = 0; attempt < MAX_TRANSITION_ATTEMPTS; attempt++) {
            AnalysisJob job = store.findById(message.jobId()).orElse(null);
            if (job == null) {
                return Result.DEAD_LETTER;
            }
            if (job.status() == JobStatus.CONTEXT_BUILDING || job.status() == JobStatus.INDEXING) {
                return executePipeline(job, message.requestId());
            }
            boolean advanced = switch (job.status()) {
                case CREATED -> transitionCreatedToQueued(job, message.requestId());
                case QUEUED -> transitionToStage(
                        job, JobStatus.CONSUMING, JobStage.CONSUMPTION, message.requestId());
                case CONSUMING -> transitionToStage(
                        job, JobStatus.PROBING, JobStage.PROBING, message.requestId());
                case PROBING -> transitionToStage(
                        job, JobStatus.CONTEXT_BUILDING, JobStage.CONTEXT_BUILDING, message.requestId());
                case CONTEXT_BUILDING, INDEXING -> false;
                case CANCEL_REQUESTED -> transitionToCanceled(job, message.requestId());
                default -> false;
            };
            if (!advanced && isTransitioningStatus(job.status())) {
                continue;
            }
            if (!advanced) {
                return Result.ACK;
            }
        }
        return Result.RETRY;
    }

    private boolean transitionCreatedToQueued(AnalysisJob job, String requestId) {
        return transition(new JobTransitionCommand(
                job.jobId(),
                JobStatus.CREATED,
                job.version(),
                JobStatus.QUEUED,
                Optional.empty(),
                Optional.empty(),
                JobEventType.JOB_QUEUED,
                requestId,
                "{\"status\":\"QUEUED\"}"));
    }

    private boolean transitionToStage(
            AnalysisJob job,
            JobStatus targetStatus,
            JobStage stageName,
            String requestId) {
        Optional<JobStageAttempt> stage = job.retryCount() > 0 ? Optional.empty() : Optional.of(JobStageAttempt.started(
                job.jobId(),
                stageName,
                stageFingerprint(job, stageName),
                1,
                now.get()));
        return transition(new JobTransitionCommand(
                job.jobId(),
                job.status(),
                job.version(),
                targetStatus,
                stage,
                Optional.empty(),
                JobEventType.STAGE_STARTED,
                requestId,
                "{\"stage\":\"" + stageName.name() + "\",\"status\":\"STARTED\"}"));
    }

    private Result executePipeline(AnalysisJob job, String requestId) {
        AnalysisJobPipeline.Outcome outcome = pipeline.execute(job, requestId);
        return switch (outcome.state()) {
            case COMPLETED -> {
                AnalysisJob latest = store.findById(job.jobId()).orElse(job);
                if (job.status() == JobStatus.CONTEXT_BUILDING && latest.status() == JobStatus.INDEXING) {
                    yield executePipeline(latest, requestId);
                }
                yield Result.ACK;
            }
            case RETRY -> scheduleRetry(job, new JobFailure(
                    JobErrorCategory.RETRYABLE, "WORK_PENDING", "Analysis work is still pending", true), requestId);
            case FAILED -> handleFailure(job, outcome.failure().orElseThrow(), requestId);
        };
    }

    private Result handleFailure(AnalysisJob job, JobFailure failure, String requestId) {
        if (failure.category() == JobErrorCategory.RETRYABLE && reliability != null) {
            reliability.retryOrDeadLetter(job, failure, requestId);
            return Result.ACK;
        }
        return transition(new JobTransitionCommand(
                job.jobId(), job.status(), job.version(), JobStatus.FAILED,
                Optional.empty(), Optional.of(failure), JobEventType.JOB_FAILED, requestId,
                "{\"status\":\"FAILED\"}")) ? Result.ACK : Result.RETRY;
    }

    private Result scheduleRetry(AnalysisJob job, JobFailure failure, String requestId) {
        if (reliability == null) {
            return Result.RETRY;
        }
        reliability.retryOrDeadLetter(job, failure, requestId);
        return Result.ACK;
    }

    private boolean transitionToCanceled(AnalysisJob job, String requestId) {
        return transition(new JobTransitionCommand(
                job.jobId(),
                JobStatus.CANCEL_REQUESTED,
                job.version(),
                JobStatus.CANCELED,
                Optional.empty(),
                Optional.empty(),
                JobEventType.JOB_CANCELED,
                requestId,
                "{\"status\":\"CANCELED\"}"));
    }

    private boolean transition(JobTransitionCommand command) {
        try {
            transitions.transition(command);
            return true;
        } catch (JobTransitionConflictException exception) {
            return false;
        }
    }

    private boolean isTransitioningStatus(JobStatus status) {
        return switch (status) {
            case CREATED, QUEUED, CONSUMING, PROBING, CANCEL_REQUESTED -> true;
            default -> false;
        };
    }

    private String stageFingerprint(AnalysisJob job, JobStage stage) {
        String input = FINGERPRINT_VERSION + '|' + job.jobId() + '|' + job.mediaId() + '|' + stage;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    public enum Result {
        ACK,
        RETRY,
        DEAD_LETTER
    }
}
