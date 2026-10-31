package com.videopilot.analysis.port;

import com.videopilot.analysis.domain.AnalysisDispatchMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRelayStore {

    List<ClaimedOutboxMessage> claimBatch(int limit, Duration leaseDuration, Instant now);

    boolean markPublished(long outboxId, UUID leaseId, Instant publishedAt);

    boolean markRetry(
            long outboxId,
            UUID leaseId,
            Instant availableAt,
            String errorSummary,
            boolean deadLettered);

    record ClaimedOutboxMessage(
            long outboxId,
            UUID leaseId,
            AnalysisDispatchMessage message,
            int publishAttempts) {
    }
}
