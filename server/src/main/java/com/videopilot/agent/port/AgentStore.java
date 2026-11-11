package com.videopilot.agent.port;

import com.videopilot.agent.domain.AgentModels.AgentRun;
import com.videopilot.agent.domain.AgentModels.AgentStep;
import com.videopilot.agent.domain.AgentModels.AgentTrace;
import com.videopilot.agent.domain.AgentModels.ClaimedRun;
import com.videopilot.agent.domain.AgentModels.RunStatus;
import com.videopilot.agent.domain.AgentModels.ToolCall;
import com.videopilot.agent.domain.AgentModels.ToolCallStatus;
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

    long beginToolCall(String leaseId, UUID runId, long expectedVersion, ToolCall call);

    void completeToolCallAndStep(
            String leaseId, UUID runId, long expectedVersion,
            ToolCall completedCall, AgentStep step, boolean answerAttempt);

    void failToolCallAndTerminate(
            String leaseId, UUID runId, long expectedVersion, UUID toolCallId,
            ToolCallStatus callStatus, String terminationReason, Instant completedAt);

    void terminate(
            String leaseId, UUID runId, long expectedVersion,
            RunStatus status, String terminationReason, Instant completedAt);

    record CreateResult(AgentRun run, boolean fingerprintConflict) {
    }
}
