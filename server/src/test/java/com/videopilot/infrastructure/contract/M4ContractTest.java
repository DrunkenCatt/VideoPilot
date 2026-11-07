package com.videopilot.infrastructure.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.videopilot.infrastructure.web.contract.M1ApiContracts;
import com.videopilot.infrastructure.web.contract.M4ApiContracts;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M4ContractTest {

    @Test
    void freezesOwnerScopedReplayAndReliabilityPaths() {
        UUID jobId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertThat(M4ApiContracts.Paths.jobReplays(jobId))
                .isEqualTo("/api/v1/analysis-jobs/00000000-0000-0000-0000-000000000001/replays");
        assertThat(M4ApiContracts.Paths.RELIABILITY_SUMMARY)
                .isEqualTo("/api/v1/operations/reliability");
    }

    @Test
    void keepsRecoveryStateAndOperatorActionExplicit() {
        M4ApiContracts.JobReliability reliability = new M4ApiContracts.JobReliability(
                M4ApiContracts.RecoveryState.MANUAL_ACTION_REQUIRED,
                M4ApiContracts.OperatorAction.MANUAL_REPLAY,
                2,
                null,
                "RETRY_EXHAUSTED");

        assertThat(reliability.recoveryState())
                .isEqualTo(M4ApiContracts.RecoveryState.MANUAL_ACTION_REQUIRED);
        assertThat(reliability.operatorAction()).isEqualTo(M4ApiContracts.OperatorAction.MANUAL_REPLAY);
        assertThat(reliability.nextRetryAt()).isNull();
    }

    @Test
    void requiresReplayConfirmationVersionReasonAndIdempotency() {
        M4ApiContracts.ReplayRequest request = new M4ApiContracts.ReplayRequest(
                7,
                true,
                "Provider recovered; replay the failed execution.");
        M4ApiContracts.ReplayResponse response = new M4ApiContracts.ReplayResponse(
                UUID.randomUUID(),
                3,
                M4ApiContracts.ReplayStatus.ACCEPTED,
                Instant.parse("2026-11-07T10:05:00Z"));

        assertThat(request.expectedVersion()).isEqualTo(7);
        assertThat(request.confirmed()).isTrue();
        assertThat(request.reason()).isNotBlank();
        assertThat(M1ApiContracts.IDEMPOTENCY_KEY_HEADER).isEqualTo("Idempotency-Key");
        assertThat(response.attempt()).isEqualTo(3);
    }

    @Test
    void exposesOnlyAggregatedReliabilityOperationsData() {
        M4ApiContracts.ReliabilitySummary summary = new M4ApiContracts.ReliabilitySummary(
                Instant.parse("2026-11-07T09:00:00Z"),
                Instant.parse("2026-11-07T10:00:00Z"),
                4,
                1,
                List.of(new M4ApiContracts.CountByCode("RETRYABLE", 2)),
                List.of(new M4ApiContracts.StageDuration("INDEXING", 1_200, 2_400)),
                List.of(new M4ApiContracts.ProviderCalls("SILICONFLOW", 5, 1, 1_000, 200, null, "CNY")),
                List.of(M4ApiContracts.DegradedSource.RABBITMQ),
                Instant.parse("2026-11-07T10:00:01Z"));

        assertThat(summary.failureCounts()).extracting(M4ApiContracts.CountByCode::code)
                .containsExactly("RETRYABLE");
        assertThat(summary.providerCalls().getFirst().estimatedCost()).isNull();
        assertThat(summary.degradedSources()).containsExactly(M4ApiContracts.DegradedSource.RABBITMQ);
    }
}
