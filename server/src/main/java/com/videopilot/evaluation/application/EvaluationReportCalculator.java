package com.videopilot.evaluation.application;

import com.videopilot.evaluation.domain.EvaluationModels.Answerability;
import com.videopilot.evaluation.domain.EvaluationModels.CaseOutcome;
import com.videopilot.evaluation.domain.EvaluationModels.CaseResult;
import com.videopilot.evaluation.domain.EvaluationModels.CaseType;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationCase;
import com.videopilot.evaluation.domain.EvaluationModels.MetricValue;
import com.videopilot.evaluation.domain.EvaluationModels.ReliabilityFact;
import com.videopilot.evaluation.domain.EvaluationModels.SeriesStatistics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

public final class EvaluationReportCalculator {

    public List<MetricValue> metrics(List<EvaluationCase> cases, List<CaseResult> results) {
        Map<String, CaseResult> byCase = new LinkedHashMap<>();
        results.forEach(result -> byCase.put(result.caseId(), result));
        List<MetricValue> metrics = new ArrayList<>(8);
        List<EvaluationCase> retrieval = cases.stream()
                .filter(value -> value.type() == CaseType.RETRIEVAL && !value.expectedSegmentIds().isEmpty())
                .toList();
        long missingExpected = cases.stream().filter(value -> value.type() == CaseType.RETRIEVAL
                && value.expectedSegmentIds().isEmpty()).count();
        metrics.add(ratio("recallAt3", retrieval, byCase,
                result -> result.rank() == null ? 0 : 1, Map.of("MISSING_EXPECTED_HIT", missingExpected)));
        metrics.add(ratio("hitAt1", retrieval, byCase,
                result -> Integer.valueOf(1).equals(result.rank()) ? 1 : 0,
                Map.of("MISSING_EXPECTED_HIT", missingExpected)));
        metrics.add(ratio("mrrAt3", retrieval, byCase,
                result -> result.rank() == null || result.rank() < 1 ? 0 : 1.0 / result.rank(),
                Map.of("MISSING_EXPECTED_HIT", missingExpected)));

        List<CaseResult> published = results.stream()
                .filter(value -> value.caseType() == CaseType.ANSWER && value.outcome() == CaseOutcome.COMPLETED)
                .toList();
        Map<String, EvaluationCase> casesById = new LinkedHashMap<>();
        cases.forEach(value -> casesById.put(value.caseId(), value));
        long missingCitationTargets = published.stream()
                .filter(value -> casesById.get(value.caseId()) == null
                        || casesById.get(value.caseId()).expectedSegmentIds().isEmpty())
                .mapToLong(CaseResult::citationCount).sum();
        metrics.add(countRatio("strictClaimEvidenceSupport", published.stream()
                .mapToLong(CaseResult::supportedClaimCount).sum(), published.stream()
                .mapToLong(CaseResult::claimCount).sum(), Map.of(
                "NO_PUBLISHED_CLAIM", results.stream().filter(value ->
                        value.caseType() == CaseType.ANSWER
                                && value.outcome() == CaseOutcome.REFUSED).count(),
                "MISSING_REQUIRED_CITATION", missingCitationTargets)));
        metrics.add(countRatio("citationLocalization", published.stream()
                .mapToLong(CaseResult::localizedCitationCount).sum(), published.stream()
                .mapToLong(CaseResult::citationCount).sum(),
                Map.of("MISSING_REQUIRED_CITATION", missingCitationTargets)));

        List<EvaluationCase> unanswerable = cases.stream()
                .filter(value -> value.type() == CaseType.ANSWER
                        && value.answerability() == Answerability.UNANSWERABLE).toList();
        metrics.add(ratio("refusalAccuracy", unanswerable, byCase,
                result -> result.outcome() == CaseOutcome.REFUSED ? 1 : 0,
                outcomeExclusions(unanswerable, byCase)));
        List<EvaluationCase> answerable = cases.stream()
                .filter(value -> value.type() == CaseType.ANSWER
                        && value.answerability() == Answerability.ANSWERABLE).toList();
        metrics.add(ratio("answerableStatusAccuracy", answerable, byCase,
                result -> result.outcome() == CaseOutcome.COMPLETED ? 1 : 0,
                outcomeExclusions(answerable, byCase)));
        List<EvaluationCase> answers = cases.stream()
                .filter(value -> value.type() == CaseType.ANSWER).toList();
        metrics.add(ratio("answerHardGatePassRate", answers, byCase,
                result -> hardGatePassed(casesById.get(result.caseId()), result) ? 1 : 0,
                outcomeExclusions(answers, byCase)));
        for (ReliabilityFact fact : ReliabilityFact.values()) {
            List<EvaluationCase> reliability = cases.stream()
                    .filter(value -> value.type() == CaseType.RELIABILITY && value.reliability() != null
                            && value.reliability().fact() == fact).toList();
            if (!reliability.isEmpty()) {
                metrics.add(ratio(reliabilityMetricName(fact), reliability, byCase,
                        result -> result.outcome() == CaseOutcome.COMPLETED ? 1 : 0,
                        outcomeExclusions(reliability, byCase)));
            }
        }
        return List.copyOf(metrics);
    }

    private boolean hardGatePassed(EvaluationCase evaluationCase, CaseResult result) {
        if (evaluationCase == null) return false;
        if (evaluationCase.answerability() == Answerability.UNANSWERABLE) {
            return result.outcome() == CaseOutcome.REFUSED;
        }
        return result.outcome() == CaseOutcome.COMPLETED
                && result.claimCount() > 0 && result.supportedClaimCount() == result.claimCount()
                && result.citationCount() > 0
                && result.localizedCitationCount() == result.citationCount();
    }

    private String reliabilityMetricName(ReliabilityFact fact) {
        return switch (fact) {
            case LOST_TASK_RECOVERED -> "lostTaskRecoveryRate";
            case ZERO_DUPLICATE_HIGH_COST_CALL -> "zeroDuplicateHighCostCallRate";
            case ILLEGAL_TRANSITION_REJECTED -> "illegalTransitionRejectionRate";
            case RECOVERY_SUCCEEDED -> "recoverySuccessRate";
        };
    }

    public SeriesStatistics seriesStatistics(List<RunObservation> observations) {
        if (observations.isEmpty()) return new SeriesStatistics(0, 0, null, Map.of());
        double mean = observations.stream().mapToDouble(RunObservation::value).average().orElseThrow();
        Double deviation = observations.size() < 2 ? null : Math.sqrt(observations.stream()
                .mapToDouble(value -> Math.pow(value.value() - mean, 2)).sum() / (observations.size() - 1));
        Map<String, Long> distribution = new LinkedHashMap<>();
        observations.forEach(value -> value.outcomes().forEach((outcome, count) ->
                distribution.merge(outcome.name(), count, Long::sum)));
        return new SeriesStatistics(observations.size(), mean, deviation, distribution);
    }

    private MetricValue ratio(
            String name, List<EvaluationCase> cases, Map<String, CaseResult> results,
            ToDoubleFunction<CaseResult> score, Map<String, Long> baseExclusions) {
        double numerator = 0;
        Map<String, Long> exclusions = new LinkedHashMap<>(baseExclusions);
        for (EvaluationCase evaluationCase : cases) {
            CaseResult result = results.get(evaluationCase.caseId());
            if (result == null) {
                exclusions.merge("MISSING_RESULT", 1L, Long::sum);
            } else {
                numerator += score.applyAsDouble(result);
            }
        }
        return metric(name, numerator, cases.size(), exclusions);
    }

    private MetricValue countRatio(String name, long numerator, long denominator, Map<String, Long> exclusions) {
        return metric(name, numerator, denominator, exclusions);
    }

    private MetricValue metric(
            String name, double numerator, long denominator, Map<String, Long> exclusions) {
        return new MetricValue(name, numerator, denominator,
                denominator == 0 ? null : numerator / denominator, clean(exclusions));
    }

    private Map<String, Long> outcomeExclusions(
            List<EvaluationCase> cases, Map<String, CaseResult> results) {
        Map<String, Long> exclusions = new LinkedHashMap<>();
        for (EvaluationCase evaluationCase : cases) {
            CaseResult result = results.get(evaluationCase.caseId());
            if (result == null) exclusions.merge("MISSING_RESULT", 1L, Long::sum);
            else if (result.outcome() == CaseOutcome.UNKNOWN || result.outcome() == CaseOutcome.ERROR) {
                exclusions.merge(result.outcome().name(), 1L, Long::sum);
            }
        }
        return exclusions;
    }

    private Map<String, Long> clean(Map<String, Long> values) {
        Map<String, Long> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value != null && value > 0) result.put(key, value);
        });
        return result;
    }

    public record RunObservation(int repetitionIndex, double value, Map<CaseOutcome, Long> outcomes) {
        public RunObservation {
            outcomes = Map.copyOf(outcomes);
        }
    }
}
