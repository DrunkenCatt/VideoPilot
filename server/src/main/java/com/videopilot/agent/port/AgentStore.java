package com.videopilot.agent.port;

import com.videopilot.agent.domain.AgentModels.AgentRun;
import com.videopilot.agent.domain.AgentModels.AgentStep;
import com.videopilot.agent.domain.AgentModels.AgentTrace;
import com.videopilot.agent.domain.AgentModels.ClaimedRun;
import com.videopilot.agent.domain.AgentModels.RunStatus;
import com.videopilot.identity.domain.OwnerId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AgentStore {

    boolean ownsReadyJob(OwnerId ownerId, UUID jobId);

    CreateResult create(OwnerId ownerId, AgentRun run);

    Optional<AgentTrace> findTrace(OwnerId ownerId, UUID runId);

    Optional<ClaimedRun> claimNext(String leaseId, Instant leaseUntil, Instant now);

    void appendStep(String leaseId, UUID runId, long expectedVersion, AgentStep step);

    void terminate(
            String leaseId, UUID runId, long expectedVersion,
            RunStatus status, String terminationReason, Instant completedAt);

    record CreateResult(AgentRun run, boolean fingerprintConflict) {
    }
}
