package com.videopilot.analysis.application;

import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.JobErrorCategory;
import com.videopilot.analysis.domain.JobEventType;
import com.videopilot.analysis.domain.JobFailure;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.analysis.port.AnalysisJobReliabilityStore;
import com.videopilot.analysis.port.AnalysisJobReliabilityStore.RecoveryCandidate;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class AnalysisJobReliabilityService implements AutoCloseable {

    private final AnalysisJobReliabilityStore store;
    private final JobTransitionService transitions;
    private final Supplier<Instant> now;
    private final Supplier<UUID> identifiers;
    private final int maxRetries;
    private final Duration retryDelay;
    private final Duration leaseDuration;
    private final ScheduledExecutorService heartbeatExecutor;

    public AnalysisJobReliabilityService(
            AnalysisJobReliabilityStore store,
            JobTransitionService transitions,
            Supplier<Instant> now,
            Supplier<UUID> identifiers,
            int maxRetries,
            Duration retryDelay,
            Duration leaseDuration) {
        if (maxRetries < 1 || retryDelay.isNegative() || retryDelay.isZero()
                || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("Invalid analysis recovery configuration");
        }
        this.store = store;
        this.transitions = transitions;
        this.now = now;
        this.identifiers = identifiers;
        this.maxRetries = maxRetries;
        this.retryDelay = retryDelay;
        this.leaseDuration = leaseDuration;
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("analysis-lease-heartbeat-", 0).factory());
    }

    public AnalysisJob retryOrDeadLetter(AnalysisJob job, JobFailure failure, String requestId) {
        if (job.retryCount() < maxRetries) {
            return transitions.transition(new JobTransitionCommand(
                    job.jobId(), job.status(), job.version(), JobStatus.RETRY_WAIT,
                    Optional.empty(), Optional.of(failure), JobEventType.JOB_RETRY_SCHEDULED,
                    requestId, "{}", Optional.of(now.get().plus(retryDelay))));
        }

        JobFailure exhausted = new JobFailure(
                JobErrorCategory.RETRYABLE,
                "RETRY_EXHAUSTED",
                "Automatic retry budget exhausted after " + maxRetries + " attempts",
                true);
        AnalysisJob failed = transitions.transition(new JobTransitionCommand(
                job.jobId(), job.status(), job.version(), JobStatus.FAILED,
                Optional.empty(), Optional.of(exhausted), JobEventType.JOB_FAILED,
                requestId, "{}"));
        return transitions.transition(new JobTransitionCommand(
                failed.jobId(), failed.status(), failed.version(), JobStatus.DEAD_LETTERED,
                Optional.empty(), Optional.of(exhausted), JobEventType.JOB_DEAD_LETTERED,
                requestId, "{}"));
    }

    public int recoverDue(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Recovery batch size must be positive");
        }
        Instant currentTime = now.get();
        int recovered = 0;
        for (RecoveryCandidate candidate : store.findRecoveryCandidates(
                currentTime, currentTime.minus(leaseDuration), limit)) {
            if (candidate.status() == JobStatus.RETRY_WAIT) {
                try {
                    transitions.transition(new JobTransitionCommand(
                            candidate.jobId(), candidate.status(), candidate.version(), JobStatus.QUEUED,
                            Optional.empty(), Optional.empty(), JobEventType.JOB_QUEUED,
                            "recovery:" + identifiers.get(), "{}"));
                    recovered++;
                } catch (JobTransitionConflictException ignored) {
                    // Another scanner or consumer already won the version CAS.
                }
            } else if (store.enqueueRecovery(
                    candidate, identifiers.get(), "recovery:" + identifiers.get(), currentTime)) {
                recovered++;
            }
        }
        return recovered;
    }

    public <T> Optional<T> executeWithLease(UUID jobId, Supplier<T> action) {
        Instant acquiredAt = now.get();
        UUID leaseId = identifiers.get();
        if (!store.tryAcquireExecutionLease(jobId, leaseId, acquiredAt, acquiredAt.plus(leaseDuration))) {
            return Optional.empty();
        }
        long heartbeatMillis = Math.max(1_000, leaseDuration.toMillis() / 3);
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                () -> store.renewExecutionLease(jobId, leaseId, now.get().plus(leaseDuration)),
                heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
        try {
            return Optional.ofNullable(action.get());
        } finally {
            heartbeat.cancel(false);
            store.releaseExecutionLease(jobId, leaseId);
        }
    }

    @Override
    public void close() {
        heartbeatExecutor.shutdownNow();
    }
}
