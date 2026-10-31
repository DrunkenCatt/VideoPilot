package com.videopilot.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.analysis.port.AnalysisMessagePublisher;
import com.videopilot.analysis.port.OutboxRelayStore;
import com.videopilot.analysis.domain.AnalysisDispatchMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    private static final UUID LEASE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void confirmsPublishedMessagesOnlyAfterPublisherConfirmation() {
        FakeStore store = new FakeStore(message(1, 1));
        OutboxRelay relay = relay(store, envelope -> confirmed());

        assertThat(relay.relayOnce()).isOne();
        assertThat(store.published).containsExactly(1L);
        assertThat(store.retries).isEmpty();
    }

    @Test
    void keepsFailedMessagesRetryableUntilTheAttemptLimit() {
        FakeStore store = new FakeStore(message(2, 1));
        OutboxRelay relay = relay(store, envelope -> completed(AnalysisMessagePublisher.PublishResult.NEGATIVE_ACK));

        relay.relayOnce();

        assertThat(store.published).isEmpty();
        assertThat(store.retries).containsExactly(
                new Retry(2L, false, "NEGATIVE_ACK", NOW.plusSeconds(5)));
    }

    @Test
    void marksTheLastFailedAttemptDeadLettered() {
        FakeStore store = new FakeStore(message(3, 3));
        OutboxRelay relay = relay(store, envelope -> completed(AnalysisMessagePublisher.PublishResult.UNROUTABLE));

        relay.relayOnce();

        assertThat(store.retries).containsExactly(
                new Retry(3L, true, "UNROUTABLE", NOW.plusSeconds(20)));
    }

    @Test
    void publisherExceptionsBecomeFiniteRetryOutcomes() {
        FakeStore store = new FakeStore(message(4, 1));
        OutboxRelay relay = relay(store, envelope -> {
            throw new IllegalStateException("confirm timeout");
        });

        relay.relayOnce();

        assertThat(store.retries).containsExactly(
                new Retry(4L, false, "PUBLISH_ERROR", NOW.plusSeconds(5)));
    }

    @Test
    void dispatchesTheEntireBatchBeforeWaitingForTheFirstConfirmation() throws Exception {
        FakeStore store = new FakeStore(message(1, 1), message(2, 1), message(3, 1));
        CountDownLatch batchDispatched = new CountDownLatch(3);
        AtomicInteger calls = new AtomicInteger();
        List<CompletableFuture<AnalysisMessagePublisher.PublishResult>> confirmations = List.of(
                new CompletableFuture<>(), new CompletableFuture<>(), new CompletableFuture<>());
        AnalysisMessagePublisher publisher = envelope -> {
            int call = calls.getAndIncrement();
            batchDispatched.countDown();
            return confirmations.get(call);
        };

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Integer> relayed = executor.submit(() -> relay(store, publisher).relayOnce());
            try {
                assertThat(batchDispatched.await(1, TimeUnit.SECONDS)).isTrue();
                confirmations.get(2).complete(AnalysisMessagePublisher.PublishResult.CONFIRMED);
                confirmations.get(1).complete(AnalysisMessagePublisher.PublishResult.CONFIRMED);
                confirmations.get(0).complete(AnalysisMessagePublisher.PublishResult.CONFIRMED);
                assertThat(relayed.get(1, TimeUnit.SECONDS)).isEqualTo(3);
            } finally {
                confirmations.forEach(future -> future.complete(AnalysisMessagePublisher.PublishResult.PUBLISH_ERROR));
            }
        }
        assertThat(store.published).containsExactly(1L, 2L, 3L);
    }

    @Test
    void settlesEachAsynchronousConfirmationIndependently() {
        FakeStore store = new FakeStore(message(1, 1), message(2, 1), message(3, 3));
        List<AnalysisMessagePublisher.PublishResult> results = List.of(
                AnalysisMessagePublisher.PublishResult.CONFIRMED,
                AnalysisMessagePublisher.PublishResult.NEGATIVE_ACK,
                AnalysisMessagePublisher.PublishResult.UNROUTABLE);
        AtomicInteger calls = new AtomicInteger();

        assertThat(relay(store, envelope -> completed(results.get(calls.getAndIncrement()))).relayOnce())
                .isEqualTo(3);

        assertThat(store.published).containsExactly(1L);
        assertThat(store.retries).containsExactly(
                new Retry(2L, false, "NEGATIVE_ACK", NOW.plusSeconds(5)),
                new Retry(3L, true, "UNROUTABLE", NOW.plusSeconds(20)));
    }

    @Test
    void rejectsUnsafeConfiguration() {
        assertThatThrownBy(() -> new OutboxRelay(
                new FakeStore(message(1, 1)),
                envelope -> confirmed(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                0,
                Duration.ofSeconds(1),
                1,
                Duration.ZERO,
                Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    private OutboxRelay relay(OutboxRelayStore store, AnalysisMessagePublisher publisher) {
        return new OutboxRelay(
                store,
                publisher,
                Clock.fixed(NOW, ZoneOffset.UTC),
                20,
                Duration.ofSeconds(30),
                3,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30));
    }

    private static CompletableFuture<AnalysisMessagePublisher.PublishResult> confirmed() {
        return completed(AnalysisMessagePublisher.PublishResult.CONFIRMED);
    }

    private static CompletableFuture<AnalysisMessagePublisher.PublishResult> completed(
            AnalysisMessagePublisher.PublishResult result) {
        return CompletableFuture.completedFuture(result);
    }

    private ClaimedMessage message(long outboxId, int attempts) {
        return new ClaimedMessage(outboxId, attempts);
    }

    private static AnalysisDispatchMessage message(long id) {
        return new AnalysisDispatchMessage(
                UUID.nameUUIDFromBytes(("message-" + id).getBytes()),
                UUID.nameUUIDFromBytes(("job-" + id).getBytes()),
                1,
                "request-" + id,
                NOW);
    }

    private record ClaimedMessage(long outboxId, int attempts) {
    }

    private record Retry(long outboxId, boolean deadLettered, String summary, Instant availableAt) {
    }

    private static final class FakeStore implements OutboxRelayStore {
        private final List<ClaimedMessage> messages;
        private final List<Long> published = new ArrayList<>();
        private final List<Retry> retries = new ArrayList<>();

        private FakeStore(ClaimedMessage... messages) {
            this.messages = Arrays.asList(messages);
        }

        @Override
        public List<ClaimedOutboxMessage> claimBatch(int limit, Duration leaseDuration, Instant now) {
            return messages.stream()
                    .limit(limit)
                    .map(message -> new ClaimedOutboxMessage(
                            message.outboxId(), LEASE, message(message.outboxId()), message.attempts()))
                    .toList();
        }

        @Override
        public boolean markPublished(long outboxId, UUID leaseId, Instant publishedAt) {
            published.add(outboxId);
            return true;
        }

        @Override
        public boolean markRetry(
                long outboxId,
                UUID leaseId,
                Instant availableAt,
                String errorSummary,
                boolean deadLettered) {
            retries.add(new Retry(outboxId, deadLettered, errorSummary, availableAt));
            return true;
        }
    }
}
