package com.videopilot.analysis.application;

import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.JobErrorCategory;
import com.videopilot.analysis.domain.JobEventType;
import com.videopilot.analysis.domain.JobFailure;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.analysis.domain.JobEvent.OperationMetadata;
import com.videopilot.analysis.port.AnalysisJobReliabilityStore;
import com.videopilot.analysis.port.AnalysisJobReliabilityStore.RecoveryCandidate;
import com.videopilot.analysis.port.AnalysisJobReliabilityStore.ReplayAudit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    public ReplayAnalysisJobResult replay(ReplayAnalysisJobCommand command) {
        validateReplay(command);
        String reason = command.reason().trim();
        String fingerprint = replayFingerprint(command.expectedVersion(), reason);
        Optional<ReplayAudit> existing = store.findReplay(command.jobId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return resumeOrReturn(command, fingerprint, existing.orElseThrow());
        }

        AnalysisJob current = requireOwnedJob(command);
        if (current.status() != JobStatus.DEAD_LETTERED || current.version() != command.expectedVersion()) {
            throw new AnalysisJobException(AnalysisJobException.Reason.CONFLICT);
        }
        int replayAttempt = current.attempt() + 1;
        AnalysisJob replaying;
        try {
            replaying = transitions.transition(new JobTransitionCommand(
                    current.jobId(), current.status(), current.version(), JobStatus.MANUAL_REPLAY,
                    Optional.empty(), Optional.empty(), JobEventType.JOB_MANUAL_REPLAY_STARTED,
                    command.requestId(), replayPayload(reason), Optional.empty(),
                    Optional.of(new OperationMetadata(command.idempotencyKey(), fingerprint, replayAttempt))));
        } catch (JobTransitionConflictException conflict) {
            return store.findReplay(command.jobId(), command.idempotencyKey())
                    .map(audit -> resumeOrReturn(command, fingerprint, audit))
                    .orElseThrow(() -> new AnalysisJobException(AnalysisJobException.Reason.CONFLICT));
        }
        queueReplay(replaying, command.requestId());
        ReplayAudit committedAudit = store.findReplay(command.jobId(), command.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Committed replay audit is unavailable"));
        return new ReplayAnalysisJobResult(current.jobId(), replayAttempt, committedAudit.acceptedAt());
    }

    private ReplayAnalysisJobResult resumeOrReturn(
            ReplayAnalysisJobCommand command, String fingerprint, ReplayAudit audit) {
        if (!audit.fingerprint().equals(fingerprint)) {
            throw new AnalysisJobException(AnalysisJobException.Reason.IDEMPOTENCY_KEY_REUSED);
        }
        AnalysisJob job = requireOwnedJob(command);
        if (job.status() == JobStatus.MANUAL_REPLAY && job.attempt() == audit.attempt()) {
            queueReplay(job, command.requestId());
        }
        return new ReplayAnalysisJobResult(command.jobId(), audit.attempt(), audit.acceptedAt());
    }

    private void queueReplay(AnalysisJob replaying, String requestId) {
        try {
            transitions.transition(new JobTransitionCommand(
                    replaying.jobId(), JobStatus.MANUAL_REPLAY, replaying.version(), JobStatus.QUEUED,
                    Optional.empty(), Optional.empty(), JobEventType.JOB_QUEUED,
                    requestId, "{}"));
        } catch (JobTransitionConflictException conflict) {
            AnalysisJob winner = store.findById(replaying.jobId()).orElseThrow(() -> conflict);
            if (winner.attempt() != replaying.attempt() || winner.status() == JobStatus.MANUAL_REPLAY
                    || winner.status() == JobStatus.DEAD_LETTERED) {
                throw conflict;
            }
        }
    }

    private AnalysisJob requireOwnedJob(ReplayAnalysisJobCommand command) {
        return store.findById(command.jobId())
                .filter(job -> job.ownerId().equals(command.ownerId()))
                .orElseThrow(() -> new AnalysisJobException(AnalysisJobException.Reason.RESOURCE_NOT_FOUND));
    }

    private void validateReplay(ReplayAnalysisJobCommand command) {
        if (command == null || command.jobId() == null || command.ownerId() == null
                || command.ownerId().isBlank() || command.expectedVersion() < 0
                || command.idempotencyKey() == null
                || !command.idempotencyKey().matches("[A-Za-z0-9._:-]{1,128}")
                || !command.confirmed() || command.reason() == null || command.reason().isBlank()
                || command.reason().length() > 500 || command.requestId() == null
                || command.requestId().isBlank()) {
            throw new AnalysisJobException(AnalysisJobException.Reason.INVALID_REQUEST);
        }
    }

    private String replayFingerprint(long expectedVersion, String reason) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((expectedVersion + "\n" + reason).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String replayPayload(String reason) {
        StringBuilder escaped = new StringBuilder(reason.length());
        for (int index = 0; index < reason.length(); index++) {
            char character = reason.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return "{\"reason\":\"" + escaped + "\"}";
    }

    public int recoverDue(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Recovery batch size must be positive");
        }
        Instant currentTime = now.get();
        int recovered = 0;
        for (RecoveryCandidate candidate : store.findRecoveryCandidates(
                currentTime, currentTime.minus(leaseDuration), limit)) {
            if (candidate.status() == JobStatus.RETRY_WAIT || candidate.status() == JobStatus.MANUAL_REPLAY) {
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
