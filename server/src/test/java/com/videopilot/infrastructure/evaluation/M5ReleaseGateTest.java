package com.videopilot.infrastructure.evaluation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.evaluation.domain.EvaluationModels.MetricValue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class M5ReleaseGateTest {

    @Test
    void requiresAllThreeRepetitionsToMeetEveryHardMetric() {
        M5ReleaseGate.assertPassed("DEV", List.of(
                passing(), passing(), passing()));

        assertThatThrownBy(() -> M5ReleaseGate.assertPassed("DEV", List.of(
                passing(), metrics(0.84, 0.90, 0.99), passing())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEV repetition 2")
                .hasMessageContaining("recallAt3");
        assertThatThrownBy(() -> M5ReleaseGate.assertPassed("DEV", List.of(
                passing(), metrics(0.90, null, 0.99), passing())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("strictClaimEvidenceSupport")
                .hasMessageContaining("missing");
        assertThatThrownBy(() -> M5ReleaseGate.assertPassed("DEV", List.of(
                passing(), metrics(0.90, 0.90, 0.94), passing())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("citationLocalization");
    }

    @Test
    void rejectsIncompleteOrUnexpectedSeriesBeforeReadingMetrics() {
        assertThatThrownBy(() -> M5ReleaseGate.assertPassed("DEV", List.of(passing(), passing())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 3");
    }

    private List<MetricValue> passing() {
        return metrics(0.85, 0.85, 0.95);
    }

    private List<MetricValue> metrics(Double recall, Double support, Double localization) {
        return List.of(
                metric("recallAt3", recall),
                metric("strictClaimEvidenceSupport", support),
                metric("citationLocalization", localization));
    }

    private MetricValue metric(String name, Double value) {
        return new MetricValue(name, value == null ? 0 : value, value == null ? 0 : 100, value, Map.of());
    }
}
