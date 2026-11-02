package com.videopilot.analysis.application;

import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.JobEventType;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.analysis.domain.StoredAnalysisJob;
import com.videopilot.analysis.port.AnalysisJobEventStore;
import com.videopilot.analysis.port.AnalysisJobStore;
import com.videopilot.identity.application.OwnerContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class AnalysisJobEventApplicationService {

    private static final int MAX_CAS_ATTEMPTS = 3;
    private static final int MAX_EVENT_BATCH_SIZE = 500;

    private final AnalysisJobStore jobs;
    private final AnalysisJobEventStore events;
    private final JobTransitionService transitions;

    public AnalysisJobEventApplicationService(
            AnalysisJobStore jobs,
            AnalysisJobEventStore events,
            JobTransitionService transitions) {
        this.jobs = jobs;
        this.events = events;
        this.transitions = transitions;
    }

    public AnalysisJobEventBatch readAfter(
            OwnerContext owner,
            UUID jobId,
            long afterSequence,
            int limit) {
        StoredAnalysisJob stored = requireOwned(owner, jobId);
        if (afterSequence < 0 || afterSequence > stored.job().eventSequence()
                || limit < 1 || limit > MAX_EVENT_BATCH_SIZE) {
            throw new AnalysisJobException(AnalysisJobException.Reason.INVALID_REQUEST);
        }
        List<AnalysisJobEventView> batch = events.findAfter(owner.ownerId(), jobId, afterSequence, limit);
        return new AnalysisJobEventBatch(
                stored.job().eventSequence(), isTerminal(stored.job().status()), batch);
    }

    public AnalysisJob cancel(OwnerContext owner, UUID jobId, String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 100) {
            throw new AnalysisJobException(AnalysisJobException.Reason.INVALID_REQUEST);
        }
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            AnalysisJob current = requireOwned(owner, jobId).job();
            if (current.status() == JobStatus.CANCELED || current.status() == JobStatus.CANCEL_REQUESTED) {
                return current;
            }
            Cancellation cancellation = cancellationFor(current.status());
            JobTransitionCommand command = new JobTransitionCommand(
                    jobId,
                    current.status(),
                    current.version(),
                    cancellation.targetStatus(),
                    Optional.empty(),
                    Optional.empty(),
                    cancellation.eventType(),
                    requestId,
                    cancellation.payload());
            try {
                return transitions.transition(command);
            } catch (JobTransitionConflictException conflict) {
                // Re-read the owner-scoped snapshot; the competing transition decides the next legal edge.
            }
        }
        throw new AnalysisJobException(AnalysisJobException.Reason.CONFLICT);
    }

    private StoredAnalysisJob requireOwned(OwnerContext owner, UUID jobId) {
        if (owner == null || jobId == null) {
            throw new AnalysisJobException(AnalysisJobException.Reason.INVALID_REQUEST);
        }
        return jobs.findById(owner.ownerId(), jobId)
                .orElseThrow(() -> new AnalysisJobException(AnalysisJobException.Reason.RESOURCE_NOT_FOUND));
    }

    private Cancellation cancellationFor(JobStatus status) {
        return switch (status) {
            case CREATED, QUEUED -> new Cancellation(
                    JobStatus.CANCELED, JobEventType.JOB_CANCELED, "{\"status\":\"CANCELED\"}");
            case CONSUMING, PROBING, CONTEXT_BUILDING, INDEXING, ANSWERING -> new Cancellation(
                    JobStatus.CANCEL_REQUESTED,
                    JobEventType.JOB_CANCEL_REQUESTED,
                    "{\"status\":\"CANCEL_REQUESTED\"}");
            default -> throw new AnalysisJobException(AnalysisJobException.Reason.JOB_NOT_CANCELABLE);
        };
    }

    private boolean isTerminal(JobStatus status) {
        return switch (status) {
            case READY, COMPLETED, COMPLETED_WITH_WARNINGS, CANCELED, FAILED, DEAD_LETTERED -> true;
            default -> false;
        };
    }

    private record Cancellation(JobStatus targetStatus, JobEventType eventType, String payload) {
    }
}
