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
    private static final UUID EVALUATION_RUN_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
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
                STARTED, Map.of(
                        "ANSWER", new ExecutionBudgetStore.OperationUsage(1, 0, 0),
                        "ASR", new ExecutionBudgetStore.OperationUsage(0, 10, 2)),
                BigDecimal.ZERO, true);
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
                "m4-v1", Duration.ZERO, Map.of("ASR", 1),
                Map.of("ASR", 1L), Map.of("ASR", 1L), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesProjectedAndCompletedUsageAtTheBoundary() {
        ExecutionBudgetPolicy policy = policy(usage(STARTED, Map.of(), 10, 2, "0.25"));

        assertThat(policy.version()).isEqualTo("m4-limits-v1");
        policy.checkJob(JOB_ID);
        policy.afterCall(JOB_ID);
        policy.afterCall(JOB_ID, "ASR", 1, 1, new BigDecimal("0.01"));
        assertThatThrownBy(() -> policy.beforeCall(JOB_ID, "ASR", -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.beforeCall(JOB_ID, "UNKNOWN", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.beforeCall(JOB_ID, "ASR", 91))
                .isInstanceOfSatisfying(ExecutionBudgetExceededException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(ExecutionBudgetExceededException.Reason.INPUT_UNITS));
        assertThatThrownBy(() -> policy.afterCall(JOB_ID, "ASR", -1, 0, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.afterCall(JOB_ID, "ASR", 91, 0, BigDecimal.ZERO))
                .isInstanceOfSatisfying(ExecutionBudgetExceededException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(ExecutionBudgetExceededException.Reason.INPUT_UNITS));
        assertThatThrownBy(() -> policy.afterCall(JOB_ID, "ASR", 0, 9, BigDecimal.ZERO))
                .isInstanceOfSatisfying(ExecutionBudgetExceededException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(ExecutionBudgetExceededException.Reason.OUTPUT_UNITS));
        assertThatThrownBy(() -> policy.afterCall(JOB_ID, "ASR", 0, 0, new BigDecimal("0.76")))
                .isInstanceOfSatisfying(ExecutionBudgetExceededException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(ExecutionBudgetExceededException.Reason.KNOWN_COST));
    }

    @Test
    void combinesIndexAndQueryEmbeddingCallsUnderOneLimit() {
        ExecutionBudgetPolicy policy = policy(usage(
                STARTED, Map.of("EMBEDDING", 2L), 0, 0, "0"));

        assertThatThrownBy(() -> policy.beforeCall(JOB_ID, "EMBEDDING", 0))
                .isInstanceOfSatisfying(ExecutionBudgetExceededException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(ExecutionBudgetExceededException.Reason.CALL_COUNT));
    }

    @Test
    void doesNotAddIncompatibleUnitsAcrossOperations() {
        ExecutionBudgetStore.Usage usage = new ExecutionBudgetStore.Usage(
                STARTED, Map.of(
                        "ASR", new ExecutionBudgetStore.OperationUsage(1, 36_466, 94),
                        "OCR", new ExecutionBudgetStore.OperationUsage(3, 1_229_760, 66)),
                BigDecimal.ZERO, false);
        ExecutionBudgetPolicy policy = new ExecutionBudgetPolicy(ignored -> usage, new ExecutionLimits(
                "m4-limits-v3", Duration.ofMinutes(1), Map.of("ASR", 2, "OCR", 4),
                Map.of("ASR", 100_000L, "OCR", 2_000_000L),
                Map.of("ASR", 1_000L, "OCR", 1_000L), BigDecimal.ONE),
                () -> STARTED.plusSeconds(30));

        policy.checkJob(JOB_ID);
    }

    @Test
    void interactiveCallsIgnoreAnalysisDeadlineButKeepEveryOtherHardGate() {
        Instant expired = STARTED.minusSeconds(31);
        ExecutionBudgetPolicy allowed = policy(usage(expired, Map.of(), 0, 0, "0"));

        allowed.beforeInteractiveCall(JOB_ID, "EMBEDDING", 1);
        allowed.afterInteractiveCall(JOB_ID);
        allowed.afterInteractiveCall(JOB_ID, "ANSWER", 1, 1, BigDecimal.ZERO);

        assertThatThrownBy(() -> allowed.beforeCall(JOB_ID, "EMBEDDING", 1))
                .isInstanceOfSatisfying(ExecutionBudgetExceededException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(ExecutionBudgetExceededException.Reason.DEADLINE));
        assertThatThrownBy(() -> policy(usage(expired, Map.of("ANSWER", 2L), 0, 0, "0"))
                .beforeInteractiveCall(JOB_ID, "ANSWER", 0))
                .isInstanceOfSatisfying(ExecutionBudgetExceededException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(ExecutionBudgetExceededException.Reason.CALL_COUNT));
        assertThatThrownBy(() -> policy(usage(expired, Map.of(), 100, 0, "0"))
                .beforeInteractiveCall(JOB_ID, "ASR", 1))
                .isInstanceOfSatisfying(ExecutionBudgetExceededException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(ExecutionBudgetExceededException.Reason.INPUT_UNITS));
        assertThatThrownBy(() -> policy(usage(expired, Map.of(), 0, 10, "0"))
                .afterInteractiveCall(JOB_ID, "ASR", 0, 1, BigDecimal.ZERO))
                .isInstanceOfSatisfying(ExecutionBudgetExceededException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(ExecutionBudgetExceededException.Reason.OUTPUT_UNITS));
        assertThatThrownBy(() -> policy(usage(expired, Map.of(), 0, 0, "1.01"))
                .afterInteractiveCall(JOB_ID))
                .isInstanceOfSatisfying(ExecutionBudgetExceededException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(ExecutionBudgetExceededException.Reason.KNOWN_COST));
    }

    @Test
    void formalEvaluationUsesItsSeriesBudgetInsteadOfHistoricalJobUsage() {
        ExecutionBudgetStore.Usage exhaustedJob = usage(
                STARTED, Map.of("ANSWER", 2L), 0, 0, "0");
        ExecutionBudgetStore.Usage freshSeries = usage(
                STARTED, Map.of("ANSWER", 1L), 0, 0, "0");
        ExecutionBudgetStore store = new ExecutionBudgetStore() {
            @Override
            public Usage load(UUID jobId) {
                return exhaustedJob;
            }

            @Override
            public Usage loadEvaluationSeries(UUID jobId, UUID evaluationRunId) {
                assertThat(jobId).isEqualTo(JOB_ID);
                assertThat(evaluationRunId).isEqualTo(EVALUATION_RUN_ID);
                return freshSeries;
            }
        };
        ExecutionBudgetPolicy policy = new ExecutionBudgetPolicy(store, new ExecutionLimits(
                "m4-limits-v1", Duration.ofMinutes(1), Map.of("ANSWER", 2),
                Map.of("ANSWER", 100L), Map.of("ANSWER", 10L), BigDecimal.ONE),
                () -> STARTED.plusSeconds(30));

        policy.beforeInteractiveCall(JOB_ID, "ANSWER", 0, EVALUATION_RUN_ID);
        policy.afterInteractiveCall(JOB_ID, "ANSWER", 0, 1, BigDecimal.ZERO, EVALUATION_RUN_ID);

        assertThatThrownBy(() -> policy.beforeInteractiveCall(JOB_ID, "ANSWER", 0))
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
                Map.of("ASR", 100L, "ANSWER", 100L, "EMBEDDING", 100L),
                Map.of("ASR", 10L, "ANSWER", 10L, "EMBEDDING", 10L),
                BigDecimal.ONE), () -> STARTED.plusSeconds(30));
    }

    private ExecutionBudgetStore.Usage usage(
            Instant createdAt, Map<String, Long> calls, long input, long output, String cost) {
        Map<String, ExecutionBudgetStore.OperationUsage> operations = new java.util.LinkedHashMap<>();
        calls.forEach((operation, count) -> operations.put(
                operation, new ExecutionBudgetStore.OperationUsage(count, 0, 0)));
        if (input > 0 || output > 0) {
            long callsForAsr = calls.getOrDefault("ASR", 0L);
            operations.put("ASR", new ExecutionBudgetStore.OperationUsage(callsForAsr, input, output));
        }
        return new ExecutionBudgetStore.Usage(createdAt, operations, new BigDecimal(cost), false);
    }
}
