package com.videopilot.analysis.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.videopilot.analysis.port.ExecutionBudgetStore;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutionBudgetPolicyTest {

    private static final UUID JOB_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant STARTED = Instant.parse("2026-11-07T00:00:00Z");

    @Test
    void enforcesDeadlineCallInputOutputAndKnownCostIndependently() {
        assertExceeded(usage(STARTED.minusSeconds(61), Map.of(), 0, 0, "0"),
                ExecutionBudgetExceededException.Reason.DEADLINE, false);
        assertExceeded(usage(STARTED, Map.of("ASR", 2L), 0, 0, "0"),
                ExecutionBudgetExceededException.Reason.CALL_COUNT, false);
        assertExceeded(usage(STARTED, Map.of(), 99, 0, "0"),
                ExecutionBudgetExceededException.Reason.INPUT_UNITS, false);
        assertExceeded(usage(STARTED, Map.of(), 0, 11, "0"),
                ExecutionBudgetExceededException.Reason.OUTPUT_UNITS, true);
        assertExceeded(usage(STARTED, Map.of(), 0, 0, "1.01"),
                ExecutionBudgetExceededException.Reason.KNOWN_COST, true);
    }

    @Test
    void unknownPriceDoesNotBecomeZeroOrDisableOtherHardGates() {
        ExecutionBudgetStore.Usage unknown = new ExecutionBudgetStore.Usage(
                STARTED, Map.of("ANSWER", 1L), 10, 2, BigDecimal.ZERO, true);
        ExecutionBudgetPolicy policy = policy(unknown);

        policy.beforeCall(JOB_ID, "ANSWER", 1);

        assertThatThrownBy(() -> policy.beforeCall(JOB_ID, "ASR", 100))
                .isInstanceOf(ExecutionBudgetExceededException.class)
                .extracting(exception -> ((ExecutionBudgetExceededException) exception).reason())
                .isEqualTo(ExecutionBudgetExceededException.Reason.INPUT_UNITS);
    }

    @Test
    void rejectsInvalidLimitConfiguration() {
        assertThatThrownBy(() -> new ExecutionLimits(
                "m4-v1", Duration.ZERO, Map.of("ASR", 1), 1, 1, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesProjectedAndCompletedUsageAtTheBoundary() {
        ExecutionBudgetPolicy policy = policy(usage(STARTED, Map.of(), 10, 2, "0.25"));

        assertThat(policy.version()).isEqualTo("m4-limits-v1");
        policy.checkJob(JOB_ID);
        policy.afterCall(JOB_ID);
        policy.afterCall(JOB_ID, 1, 1, new BigDecimal("0.01"));
        assertThatThrownBy(() -> policy.beforeCall(JOB_ID, "ASR", -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.beforeCall(JOB_ID, "UNKNOWN", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.beforeCall(JOB_ID, "ASR", 91))
                .isInstanceOfSatisfying(ExecutionBudgetExceededException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(ExecutionBudgetExceededException.Reason.INPUT_UNITS));
        assertThatThrownBy(() -> policy.afterCall(JOB_ID, -1, 0, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.afterCall(JOB_ID, 91, 0, BigDecimal.ZERO))
                .isInstanceOfSatisfying(ExecutionBudgetExceededException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(ExecutionBudgetExceededException.Reason.INPUT_UNITS));
        assertThatThrownBy(() -> policy.afterCall(JOB_ID, 0, 9, BigDecimal.ZERO))
                .isInstanceOfSatisfying(ExecutionBudgetExceededException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(ExecutionBudgetExceededException.Reason.OUTPUT_UNITS));
        assertThatThrownBy(() -> policy.afterCall(JOB_ID, 0, 0, new BigDecimal("0.76")))
                .isInstanceOfSatisfying(ExecutionBudgetExceededException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(ExecutionBudgetExceededException.Reason.KNOWN_COST));
    }

    @Test
    void combinesIndexAndQueryEmbeddingCallsUnderOneLimit() {
        ExecutionBudgetPolicy policy = policy(usage(
                STARTED, Map.of("EMBEDDING", 1L, "RETRIEVAL_EMBEDDING", 1L), 0, 0, "0"));

        assertThatThrownBy(() -> policy.beforeCall(JOB_ID, "EMBEDDING", 0))
                .isInstanceOfSatisfying(ExecutionBudgetExceededException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(ExecutionBudgetExceededException.Reason.CALL_COUNT));
    }

    private void assertExceeded(
            ExecutionBudgetStore.Usage usage,
            ExecutionBudgetExceededException.Reason reason,
            boolean after) {
        ExecutionBudgetPolicy policy = policy(usage);
        assertThatThrownBy(() -> {
            if (after) policy.afterCall(JOB_ID);
            else policy.beforeCall(JOB_ID, "ASR", 2);
        }).isInstanceOf(ExecutionBudgetExceededException.class)
                .extracting(exception -> ((ExecutionBudgetExceededException) exception).reason())
                .isEqualTo(reason);
    }

    private ExecutionBudgetPolicy policy(ExecutionBudgetStore.Usage usage) {
        return new ExecutionBudgetPolicy(ignored -> usage, new ExecutionLimits(
                "m4-limits-v1", Duration.ofMinutes(1), Map.of("ASR", 2, "ANSWER", 2, "EMBEDDING", 2),
                100, 10, BigDecimal.ONE), () -> STARTED.plusSeconds(30));
    }

    private ExecutionBudgetStore.Usage usage(
            Instant createdAt, Map<String, Long> calls, long input, long output, String cost) {
        return new ExecutionBudgetStore.Usage(
                createdAt, calls, input, output, new BigDecimal(cost), false);
    }
}
