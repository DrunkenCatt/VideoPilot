package com.videopilot.infrastructure.evaluation;

import com.videopilot.evaluation.domain.EvaluationModels.MetricValue;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class M5ReleaseGate {

    private static final Map<String, Double> HARD_GATES = Map.of(
            "recallAt3", 0.85,
            "strictClaimEvidenceSupport", 0.85,
            "citationLocalization", 0.95);

    private M5ReleaseGate() {
    }

    static void assertPassed(String split, List<List<MetricValue>> repetitions) {
        if (repetitions.size() != 3) {
            throw new IllegalStateException(split + " requires exactly 3 completed repetitions");
        }
        for (int index = 0; index < repetitions.size(); index++) {
            Map<String, MetricValue> metrics = repetitions.get(index).stream()
                    .collect(Collectors.toUnmodifiableMap(MetricValue::name, Function.identity()));
            for (var gate : HARD_GATES.entrySet()) {
                MetricValue metric = metrics.get(gate.getKey());
                if (metric == null || metric.value() == null) {
                    throw new IllegalStateException(split + " repetition " + (index + 1) + ' '
                            + gate.getKey() + " is missing");
                }
                if (metric.value() < gate.getValue()) {
                    throw new IllegalStateException(split + " repetition " + (index + 1) + ' '
                            + gate.getKey() + '=' + metric.value() + " is below " + gate.getValue());
                }
            }
        }
    }
}
