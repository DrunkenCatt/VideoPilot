package com.videopilot.analysis.application;

import com.videopilot.analysis.domain.AnalysisDispatchMessage;
import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.JobEventType;
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

    private static final int MAX_CAS_ATTEMPTS = 3;
    private static final String FINGERPRINT_VERSION = "m1-consumption-v1";

    private final JobTransitionStore store;
    private final JobTransitionService transitions;
    private final JobExecutionLock lock;
    private final Supplier<Instant> now;

    public AnalysisJobExecutionHandler(
            JobTransitionStore store,
            JobTransitionService transitions,
            JobExecutionLock lock,
            Supplier<Instant> now) {
        this.store = store;
        this.transitions = transitions;
        this.lock = lock;
        this.now = now;
    }

    public Result handle(AnalysisDispatchMessage message) {
        if (store.findById(message.jobId()).isEmpty()) {
            return Result.DEAD_LETTER;
        }
        try (JobExecutionLock.LockAttempt lockAttempt = lock.tryAcquire(message.jobId())) {
            if (lockAttempt.result() == JobExecutionLock.AcquireResult.BUSY) {
                return Result.RETRY;
            }
            return claimExecution(message);
        }
    }

    private Result claimExecution(AnalysisDispatchMessage message) {
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            AnalysisJob job = store.findById(message.jobId()).orElse(null);
            if (job == null) {
                return Result.DEAD_LETTER;
            }
            if (job.status() == JobStatus.CREATED) {
                if (!transitionCreatedToQueued(job, message.requestId())) {
                    continue;
                }
                continue;
            }
            if (job.status() == JobStatus.QUEUED) {
                if (!transitionQueuedToConsuming(job, message.requestId())) {
                    continue;
                }
            }
            return Result.ACK;
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

    private boolean transitionQueuedToConsuming(AnalysisJob job, String requestId) {
        JobStageAttempt stage = JobStageAttempt.started(
                job.jobId(),
                JobStage.CONSUMPTION,
                consumptionFingerprint(job),
                1,
                now.get());
        return transition(new JobTransitionCommand(
                job.jobId(),
                JobStatus.QUEUED,
                job.version(),
                JobStatus.CONSUMING,
                Optional.of(stage),
                Optional.empty(),
                JobEventType.STAGE_STARTED,
                requestId,
                "{\"stage\":\"CONSUMPTION\",\"status\":\"STARTED\"}"));
    }

    private boolean transition(JobTransitionCommand command) {
        try {
            transitions.transition(command);
            return true;
        } catch (JobTransitionConflictException exception) {
            return false;
        }
    }

    private String consumptionFingerprint(AnalysisJob job) {
        String input = FINGERPRINT_VERSION + '|' + job.jobId() + '|' + job.mediaId();
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
