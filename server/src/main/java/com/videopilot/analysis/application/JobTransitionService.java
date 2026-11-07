package com.videopilot.analysis.application;

import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.JobEvent;
import com.videopilot.analysis.domain.JobStage;
import com.videopilot.analysis.domain.JobStateMachine;
import com.videopilot.analysis.port.JobTransitionStore;
import com.videopilot.analysis.port.ActiveAnalysisJobCache;
import com.videopilot.analysis.port.ActiveAnalysisJobCacheUnavailableException;
import com.videopilot.analysis.port.JobTransitionStore.TransitionCommit;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JobTransitionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobTransitionService.class);
    private final JobTransitionStore store;
    private final ActiveAnalysisJobCache activeJobs;
    private final JobStateMachine stateMachine;
    private final Supplier<Instant> now;

    public JobTransitionService(
            JobTransitionStore store,
            ActiveAnalysisJobCache activeJobs,
            JobStateMachine stateMachine,
            Supplier<Instant> now) {
        this.store = store;
        this.activeJobs = activeJobs;
        this.stateMachine = stateMachine;
        this.now = now;
    }

    public AnalysisJob transition(JobTransitionCommand command) {
        AnalysisJob current = store.findById(command.jobId())
                .orElseThrow(() -> new JobNotFoundException(command.jobId()));
        requireExpectedSnapshot(command, current);
        stateMachine.requireTransition(current.status(), command.targetStatus());

        Instant occurredAt = now.get();
        Optional<JobStage> stage = command.stageAttempt().map(attempt -> attempt.stage());
        AnalysisJob next = current.advance(
                command.targetStatus(), stage, command.failure(), command.nextRetryAt(), occurredAt);
        JobEvent event = new JobEvent(
                UUID.randomUUID(),
                current.jobId(),
                next.eventSequence(),
                command.eventType(),
                1,
                command.requestId(),
                command.eventPayload(),
                command.operation(),
                occurredAt);
        TransitionCommit commit = new TransitionCommit(
                command.expectedStatus(),
                command.expectedVersion(),
                next,
                command.stageAttempt(),
                event);
        if (!store.apply(commit)) {
            throw JobTransitionConflictException.concurrentTransition();
        }
        updateActiveCache(next);
        return next;
    }

    private void updateActiveCache(AnalysisJob job) {
        try {
            if (!ActiveAnalysisJobCache.isActive(job.status())) {
                activeJobs.evict(job.jobId());
            } else {
                activeJobs.refresh(job.jobId(), job.status(), job.version());
            }
        } catch (ActiveAnalysisJobCacheUnavailableException exception) {
            LOGGER.warn("Active job cache update unavailable; committed MySQL transition remains authoritative");
        }
    }

    private void requireExpectedSnapshot(JobTransitionCommand command, AnalysisJob current) {
        if (current.status() != command.expectedStatus()) {
            throw JobTransitionConflictException.staleStatus(command.expectedStatus(), current.status());
        }
        if (current.version() != command.expectedVersion()) {
            throw JobTransitionConflictException.staleVersion(command.expectedVersion(), current.version());
        }
    }
}
