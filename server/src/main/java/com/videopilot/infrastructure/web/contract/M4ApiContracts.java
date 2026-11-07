package com.videopilot.infrastructure.web.contract;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class M4ApiContracts {

    public static final int MAX_REPLAY_REASON_LENGTH = 500;

    private M4ApiContracts() {
    }

    public static final class Paths {

        public static final String RELIABILITY_SUMMARY = "/api/v1/operations/reliability";

        private Paths() {
        }

        public static String jobReplays(UUID jobId) {
            return M1ApiContracts.Paths.ANALYSIS_JOBS + "/"
                    + Objects.requireNonNull(jobId, "jobId") + "/replays";
        }
    }

    public enum RecoveryState {
        NONE,
        AUTO_RECOVERING,
        RETRY_WAIT,
        MANUAL_ACTION_REQUIRED,
        NON_RECOVERABLE,
        BUDGET_EXHAUSTED
    }

    public enum OperatorAction {
        NONE,
        WAIT,
        MANUAL_REPLAY
    }

    public enum ReplayStatus {
        ACCEPTED
    }

    public enum DegradedSource {
        MYSQL,
        RABBITMQ
    }

    public record JobReliability(
            RecoveryState recoveryState,
            OperatorAction operatorAction,
            int attempt,
            Instant nextRetryAt,
            String reasonCode) {
    }

    public record ReplayRequest(
            @PositiveOrZero long expectedVersion,
            @AssertTrue boolean confirmed,
            @NotBlank @Size(max = MAX_REPLAY_REASON_LENGTH) String reason) {
    }

    public record ReplayResponse(
            UUID jobId,
            int attempt,
            ReplayStatus status,
            Instant acceptedAt) {
    }

    public record CountByCode(String code, long count) {
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

    public record ReliabilitySummary(
            Instant windowStart,
            Instant windowEnd,
            long pendingOutbox,
            long queueBacklog,
            List<CountByCode> failureCounts,
            List<StageDuration> stageDurations,
            List<ProviderCalls> providerCalls,
            List<DegradedSource> degradedSources,
            Instant generatedAt) {
    }
}
