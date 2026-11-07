package com.videopilot.analysis.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@FunctionalInterface
public interface ReliabilityOperationsStore {

    Snapshot load(String ownerId, Instant windowStart, Instant windowEnd);

    record Snapshot(
            long pendingOutbox,
            List<Count> failureCounts,
            List<StageLatency> stageDurations,
            List<ProviderUsage> providerCalls) {
    }

    record Count(String code, long count) {
    }

    record StageLatency(String stage, long p50Ms, long p95Ms) {
    }

    record ProviderUsage(
            String provider,
            long totalCalls,
            long failedCalls,
            long inputUnits,
            long outputUnits,
            BigDecimal estimatedCost,
            String currency) {
    }
}
