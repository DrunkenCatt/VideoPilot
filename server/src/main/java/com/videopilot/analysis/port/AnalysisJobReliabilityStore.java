package com.videopilot.analysis.port;

import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.analysis.domain.JobStage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisJobReliabilityStore {

    List<RecoveryCandidate> findRecoveryCandidates(Instant now, Instant staleBefore, int limit);

    boolean enqueueRecovery(RecoveryCandidate candidate, UUID messageId, String requestId, Instant now);

    boolean tryAcquireExecutionLease(UUID jobId, UUID leaseId, Instant now, Instant leaseUntil);

    boolean renewExecutionLease(UUID jobId, UUID leaseId, Instant leaseUntil);

    void releaseExecutionLease(UUID jobId, UUID leaseId);

    record RecoveryCandidate(
            UUID jobId,
            JobStatus status,
            long version,
            long eventSequence,
            Optional<JobStage> currentStage,
            Optional<Instant> nextRetryAt) {
        public RecoveryCandidate(
                UUID jobId, JobStatus status, long version, long eventSequence, Optional<Instant> nextRetryAt) {
            this(jobId, status, version, eventSequence, Optional.empty(), nextRetryAt);
        }
    }
}
