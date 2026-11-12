package com.videopilot.evaluation.application;

import static com.videopilot.evaluation.domain.EvaluationModels.Answerability.ANSWERABLE;
import static com.videopilot.evaluation.domain.EvaluationModels.Answerability.NOT_APPLICABLE;
import static com.videopilot.evaluation.domain.EvaluationModels.Answerability.UNANSWERABLE;
import static com.videopilot.evaluation.domain.EvaluationModels.CaseOutcome.COMPLETED;
import static com.videopilot.evaluation.domain.EvaluationModels.CaseOutcome.ERROR;
import static com.videopilot.evaluation.domain.EvaluationModels.CaseOutcome.REFUSED;
import static com.videopilot.evaluation.domain.EvaluationModels.CaseOutcome.UNKNOWN;
import static com.videopilot.evaluation.domain.EvaluationModels.CaseType.ANSWER;
import static com.videopilot.evaluation.domain.EvaluationModels.CaseType.RETRIEVAL;
import static org.assertj.core.api.Assertions.assertThat;

import com.videopilot.evaluation.domain.EvaluationModels.CaseResult;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationCase;
import com.videopilot.evaluation.domain.EvaluationModels.SeriesStatistics;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationReportCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-11-08T02:00:00Z");
    private final EvaluationReportCalculator calculator = new EvaluationReportCalculator();

    @Test
    void calculatesAllFrozenMetricsWithExplicitDenominatorsAndExclusions() {
        UUID runId = UUID.randomUUID();
        List<EvaluationCase> cases = List.of(
                evaluationCase("r1", RETRIEVAL, NOT_APPLICABLE, true),
                evaluationCase("r2", RETRIEVAL, NOT_APPLICABLE, true),
                evaluationCase("r-empty", RETRIEVAL, NOT_APPLICABLE, false),
                evaluationCase("a-ok", ANSWER, ANSWERABLE, false),
                evaluationCase("a-refuse", ANSWER, UNANSWERABLE, false),
                evaluationCase("a-unknown", ANSWER, UNANSWERABLE, false));
        List<CaseResult> results = List.of(
                result(runId, "r1", RETRIEVAL, COMPLETED, 1, 0, 0, 0, 0),
                result(runId, "r2", RETRIEVAL, COMPLETED, null, 0, 0, 0, 0),
                result(runId, "r-empty", RETRIEVAL, COMPLETED, null, 0, 0, 0, 0),
                result(runId, "a-ok", ANSWER, COMPLETED, null, 2, 3, 2, 2),
                result(runId, "a-refuse", ANSWER, REFUSED, null, 0, 0, 0, 0),
                result(runId, "a-unknown", ANSWER, UNKNOWN, null, 0, 0, 0, 0));

        var metrics = calculator.metrics(cases, results);

        assertThat(metrics).extracting(value -> value.name()).containsExactly(
                "recallAt3", "hitAt1", "mrrAt3", "strictClaimEvidenceSupport",
                "citationLocalization", "refusalAccuracy", "answerableStatusAccuracy",
                "answerHardGatePassRate");
        assertMetric(metrics, "recallAt3", 1.0, 2, 0.5);
        assertMetric(metrics, "hitAt1", 1.0, 2, 0.5);
        assertMetric(metrics, "mrrAt3", 1.0, 2, 0.5);
        assertMetric(metrics, "strictClaimEvidenceSupport", 2.0, 2, 1.0);
        assertMetric(metrics, "citationLocalization", 2.0, 3, 2.0 / 3.0);
        assertMetric(metrics, "refusalAccuracy", 1.0, 2, 0.5);
        assertMetric(metrics, "answerableStatusAccuracy", 1.0, 1, 1.0);
        assertMetric(metrics, "answerHardGatePassRate", 1.0, 3, 1.0 / 3.0);
        assertThat(metric(metrics, "recallAt3").exclusions()).containsEntry("MISSING_EXPECTED_HIT", 1L);
        assertThat(metric(metrics, "refusalAccuracy").exclusions()).containsEntry("UNKNOWN", 1L);
    }

    @Test
    void keepsEmptyDenominatorNullAndMrrFractionalNumerator() {
        UUID runId = UUID.randomUUID();
        var empty = calculator.metrics(List.of(), List.of());
        assertThat(metric(empty, "recallAt3").value()).isNull();
        assertThat(metric(empty, "recallAt3").denominator()).isZero();

        var metrics = calculator.metrics(
                List.of(evaluationCase("r1", RETRIEVAL, NOT_APPLICABLE, true)),
                List.of(result(runId, "r1", RETRIEVAL, COMPLETED, 2, 0, 0, 0, 0)));
        assertMetric(metrics, "mrrAt3", 0.5, 1, 0.5);
    }

    @Test
    void treatsInvalidZeroRankAsAMissInsteadOfProducingInfinity() {
        UUID runId = UUID.randomUUID();
        var metrics = calculator.metrics(
                List.of(evaluationCase("r-zero", RETRIEVAL, NOT_APPLICABLE, true)),
                List.of(result(runId, "r-zero", RETRIEVAL, COMPLETED, 0, 0, 0, 0, 0)));

        assertThat(metric(metrics, "mrrAt3").value()).isZero().isFinite();
    }

    @Test
    void reportsSampleDeviationOutcomeDistributionAndUnknownAcrossThreeRepetitions() {
        SeriesStatistics statistics = calculator.seriesStatistics(List.of(
                new EvaluationReportCalculator.RunObservation(1, 10.0, Map.of(COMPLETED, 2L)),
                new EvaluationReportCalculator.RunObservation(2, 20.0, Map.of(COMPLETED, 1L, UNKNOWN, 1L)),
                new EvaluationReportCalculator.RunObservation(3, 30.0, Map.of(ERROR, 2L))));

        assertThat(statistics.repetitions()).isEqualTo(3);
        assertThat(statistics.mean()).isEqualTo(20.0);
        assertThat(statistics.sampleStandardDeviation()).isEqualTo(10.0);
        assertThat(statistics.outcomeDistribution()).containsEntry("UNKNOWN", 1L)
                .containsEntry("ERROR", 2L).containsEntry("COMPLETED", 3L);
    }

    private void assertMetric(
            List<com.videopilot.evaluation.domain.EvaluationModels.MetricValue> metrics,
            String name, double numerator, long denominator, double value) {
        assertThat(metric(metrics, name).numerator()).isEqualTo(numerator);
        assertThat(metric(metrics, name).denominator()).isEqualTo(denominator);
        assertThat(metric(metrics, name).value()).isCloseTo(value, org.assertj.core.data.Offset.offset(0.000001));
    }

    private com.videopilot.evaluation.domain.EvaluationModels.MetricValue metric(
            List<com.videopilot.evaluation.domain.EvaluationModels.MetricValue> metrics, String name) {
        return metrics.stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow();
    }

    private EvaluationCase evaluationCase(
            String id,
            com.videopilot.evaluation.domain.EvaluationModels.CaseType type,
            com.videopilot.evaluation.domain.EvaluationModels.Answerability answerability,
            boolean expectedHit) {
        return new EvaluationCase(id, 1, type, UUID.randomUUID(), "question",
                expectedHit ? List.of(UUID.randomUUID()) : List.of(), answerability,
                "APPROVED", 2, "a".repeat(64));
    }

    private CaseResult result(
            UUID runId, String id, com.videopilot.evaluation.domain.EvaluationModels.CaseType type,
            com.videopilot.evaluation.domain.EvaluationModels.CaseOutcome outcome, Integer rank,
            int claims, int citations, int supported, int localized) {
        return new CaseResult(runId, id, type, outcome, rank, claims, citations, supported, localized,
                UUID.randomUUID(), 10, 0, (BigDecimal) null, null, NOW);
    }
}
