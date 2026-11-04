package com.videopilot.context.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ContextRecoveryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-04T06:00:00Z");
    private final ContextRecoveryPolicy policy = new ContextRecoveryPolicy(Duration.ofMinutes(5));

    @Test
    void completedFingerprintIsAlwaysANoOp() {
        assertThat(policy.decide(ContextWorkItemStatus.COMPLETED, false, NOW.minusSeconds(600), NOW))
                .isEqualTo(ContextRecoveryDecision.REUSE);
    }

    @Test
    void onlyPendingRetryableFailureAndStaleStartedMayBeClaimed() {
        assertThat(policy.decide(ContextWorkItemStatus.PENDING, false, NOW, NOW))
                .isEqualTo(ContextRecoveryDecision.CLAIM);
        assertThat(policy.decide(ContextWorkItemStatus.FAILED, true, NOW, NOW))
                .isEqualTo(ContextRecoveryDecision.CLAIM);
        assertThat(policy.decide(ContextWorkItemStatus.FAILED, false, NOW, NOW))
                .isEqualTo(ContextRecoveryDecision.STOP);
        assertThat(policy.decide(ContextWorkItemStatus.STARTED, false, NOW.minusSeconds(301), NOW))
                .isEqualTo(ContextRecoveryDecision.CLAIM);
        assertThat(policy.decide(ContextWorkItemStatus.STARTED, false, NOW.minusSeconds(299), NOW))
                .isEqualTo(ContextRecoveryDecision.WAIT);
    }

    @Test
    void unknownAndCanceledAreNeverAutomaticallyRetried() {
        assertThat(policy.decide(ContextWorkItemStatus.UNKNOWN, true, NOW.minusSeconds(600), NOW))
                .isEqualTo(ContextRecoveryDecision.REVIEW);
        assertThat(policy.decide(ContextWorkItemStatus.CANCELED, true, NOW.minusSeconds(600), NOW))
                .isEqualTo(ContextRecoveryDecision.STOP);
    }
}
