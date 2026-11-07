package com.videopilot.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.analysis.port.QueueDepthProbe;
import com.videopilot.analysis.port.ReliabilityOperationsStore;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReliabilityOperationsServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

    @Test
    void combinesOwnerScopedMysqlFactsWithQueueDepthAndFrozenWindow() {
        ReliabilityOperationsStore store = (ownerId, from, to) -> {
            assertThat(ownerId).isEqualTo("owner-1");
            assertThat(from).isEqualTo(NOW.minus(Duration.ofHours(24)));
            assertThat(to).isEqualTo(NOW);
            return new ReliabilityOperationsStore.Snapshot(
                    3,
                    List.of(new ReliabilityOperationsStore.Count("RETRYABLE", 2)),
                    List.of(new ReliabilityOperationsStore.StageLatency("INDEXING", 100, 300)),
                    List.of(new ReliabilityOperationsStore.ProviderUsage(
                            "SiliconFlow", 5, 1, 400, 20, new BigDecimal("0.75"), "USD")));
        };
        QueueDepthProbe queue = () -> 7;

        var summary = new ReliabilityOperationsService(store, queue, Duration.ofHours(24), () -> NOW)
                .summary("owner-1");

        assertThat(summary.pendingOutbox()).isEqualTo(3);
        assertThat(summary.queueBacklog()).isEqualTo(7);
        assertThat(summary.degradedSources()).isEmpty();
        assertThat(summary.providerCalls().getFirst().estimatedCost()).isEqualByComparingTo("0.75");
    }

    @Test
    void keepsMysqlMetricsWhenRabbitIsUnavailableAndNeverInventsQueueZero() {
        ReliabilityOperationsStore store = (ownerId, from, to) ->
                new ReliabilityOperationsStore.Snapshot(4, List.of(), List.of(), List.of());
        QueueDepthProbe queue = () -> { throw new IllegalStateException("management unavailable"); };

        var summary = new ReliabilityOperationsService(store, queue, Duration.ofHours(24), () -> NOW)
                .summary("owner-1");

        assertThat(summary.pendingOutbox()).isEqualTo(4);
        assertThat(summary.queueBacklog()).isNull();
        assertThat(summary.degradedSources()).containsExactly("RABBITMQ");
    }

    @Test
    void keepsRabbitDepthWhenMysqlIsUnavailableAndMarksMysqlDegraded() {
        ReliabilityOperationsStore store = (ownerId, from, to) -> {
            throw new IllegalStateException("database unavailable");
        };

        var summary = new ReliabilityOperationsService(store, () -> 2, Duration.ofHours(24), () -> NOW)
                .summary("owner-1");

        assertThat(summary.pendingOutbox()).isNull();
        assertThat(summary.queueBacklog()).isEqualTo(2);
        assertThat(summary.degradedSources()).containsExactly("MYSQL");
        assertThat(summary.failureCounts()).isEmpty();
    }

    @Test
    void rejectsMissingOwnerAndNonPositiveWindow() {
        ReliabilityOperationsStore store = (ownerId, from, to) ->
                new ReliabilityOperationsStore.Snapshot(0, List.of(), List.of(), List.of());
        QueueDepthProbe queue = () -> 0;

        ReliabilityOperationsService service =
                new ReliabilityOperationsService(store, queue, Duration.ofHours(24), () -> NOW);
        assertThatThrownBy(() -> service.summary(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ownerId is required");
        assertThatThrownBy(() -> new ReliabilityOperationsService(store, queue, Duration.ZERO, () -> NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reliability window must be positive");
    }
}
