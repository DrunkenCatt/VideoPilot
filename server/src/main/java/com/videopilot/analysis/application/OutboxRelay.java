package com.videopilot.analysis.application;

import com.videopilot.analysis.port.AnalysisMessagePublisher;
import com.videopilot.analysis.port.AnalysisMessagePublisher.PublishResult;
import com.videopilot.analysis.port.OutboxRelayStore;
import com.videopilot.analysis.port.OutboxRelayStore.ClaimedOutboxMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class OutboxRelay {

    private final OutboxRelayStore store;
    private final AnalysisMessagePublisher publisher;
    private final Clock clock;
    private final int batchSize;
    private final Duration leaseDuration;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final Duration maxRetryDelay;

    public OutboxRelay(
            OutboxRelayStore store,
            AnalysisMessagePublisher publisher,
            Clock clock,
            int batchSize,
            Duration leaseDuration,
            int maxAttempts,
            Duration retryDelay,
            Duration maxRetryDelay) {
        if (batchSize < 1 || maxAttempts < 1 || leaseDuration.isNegative() || leaseDuration.isZero()
                || retryDelay.isNegative() || maxRetryDelay.compareTo(retryDelay) < 0) {
            throw new IllegalArgumentException("Invalid outbox relay configuration");
        }
        this.store = store;
        this.publisher = publisher;
        this.clock = clock;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay;
        this.maxRetryDelay = maxRetryDelay;
    }

    public int relayOnce() {
        Instant now = clock.instant();
        List<ClaimedOutboxMessage> messages = store.claimBatch(batchSize, leaseDuration, now);
        int processed = 0;
        for (ClaimedOutboxMessage message : messages) {
            PublishResult result;
            try {
                result = publisher.publish(message.message());
            } catch (RuntimeException exception) {
                result = PublishResult.PUBLISH_ERROR;
            }
            if (result == PublishResult.CONFIRMED) {
                store.markPublished(message.outboxId(), message.leaseId(), clock.instant());
            } else {
                boolean deadLettered = message.publishAttempts() >= maxAttempts;
                String summary = result.name();
                store.markRetry(
                        message.outboxId(),
                        message.leaseId(),
                        clock.instant().plus(backoff(message.publishAttempts())),
                        summary,
                        deadLettered);
            }
            processed++;
        }
        return processed;
    }

    private Duration backoff(int attempt) {
        int shift = Math.min(Math.max(attempt - 1, 0), 30);
        long multiplier = 1L << shift;
        try {
            Duration calculated = retryDelay.multipliedBy(multiplier);
            return calculated.compareTo(maxRetryDelay) > 0 ? maxRetryDelay : calculated;
        } catch (ArithmeticException exception) {
            return maxRetryDelay;
        }
    }
}
