package com.videopilot.analysis.application;

import com.videopilot.analysis.port.QueueDepthProbe;
import com.videopilot.analysis.port.ReliabilityOperationsStore;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class ReliabilityOperationsService {

    private final ReliabilityOperationsStore store;
    private final QueueDepthProbe queue;
    private final Duration window;
    private final Supplier<Instant> now;

    public ReliabilityOperationsService(
            ReliabilityOperationsStore store,
            QueueDepthProbe queue,
            Duration window,
            Supplier<Instant> now) {
        this.store = Objects.requireNonNull(store, "store");
        this.queue = Objects.requireNonNull(queue, "queue");
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("reliability window must be positive");
        }
        this.window = window;
        this.now = Objects.requireNonNull(now, "now");
    }

    public Summary summary(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId is required");
        Instant generatedAt = now.get();
        Instant windowStart = generatedAt.minus(window);
        List<String> degraded = new ArrayList<>(2);
        Long pendingOutbox = null;
        List<Count> failures = List.of();
        List<StageDuration> stages = List.of();
        List<ProviderCalls> providers = List.of();
        try {
            ReliabilityOperationsStore.Snapshot snapshot = store.load(ownerId, windowStart, generatedAt);
            pendingOutbox = snapshot.pendingOutbox();
            failures = snapshot.failureCounts().stream().map(value -> new Count(value.code(), value.count())).toList();
            stages = snapshot.stageDurations().stream()
                    .map(value -> new StageDuration(value.stage(), value.p50Ms(), value.p95Ms())).toList();
            providers = snapshot.providerCalls().stream().map(value -> new ProviderCalls(
                    value.provider(), value.totalCalls(), value.failedCalls(), value.inputUnits(),
                    value.outputUnits(), value.estimatedCost(), value.currency())).toList();
        } catch (RuntimeException unavailable) {
            degraded.add("MYSQL");
        }
        Long queueBacklog = null;
        try {
            queueBacklog = queue.backlog();
        } catch (RuntimeException unavailable) {
            degraded.add("RABBITMQ");
        }
        return new Summary(windowStart, generatedAt, pendingOutbox, queueBacklog,
                failures, stages, providers, List.copyOf(degraded), generatedAt);
    }

    public record Summary(
            Instant windowStart,
            Instant windowEnd,
            Long pendingOutbox,
            Long queueBacklog,
            List<Count> failureCounts,
            List<StageDuration> stageDurations,
            List<ProviderCalls> providerCalls,
            List<String> degradedSources,
            Instant generatedAt) {
    }

    public record Count(String code, long count) {
    }

    public record StageDuration(String stage, long p50Ms, long p95Ms) {
    }

    public record ProviderCalls(
            String provider,
            long totalCalls,
            long failedCalls,
            long inputUnits,
            long outputUnits,
            BigDecimal estimatedCost,
            String currency) {
    }
}
