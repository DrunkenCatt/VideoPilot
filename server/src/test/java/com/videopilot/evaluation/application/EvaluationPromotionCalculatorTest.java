package com.videopilot.evaluation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.videopilot.evaluation.domain.EvaluationModels.EvaluationReport;
import com.videopilot.evaluation.domain.EvaluationModels.ExecutionMode;
import com.videopilot.evaluation.domain.EvaluationModels.ExecutionVersions;
import com.videopilot.evaluation.domain.EvaluationModels.MetricValue;
import com.videopilot.evaluation.domain.EvaluationModels.OperationalSummary;
import com.videopilot.evaluation.domain.EvaluationModels.UsageSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationPromotionCalculatorTest {

    private final EvaluationPromotionCalculator calculator = new EvaluationPromotionCalculator();

    @Test
    void acceptsEightPointGainOnlyWhenLatencyCostAndM5GatesPass() {
        var decision = calculator.decide(report(0.70, 0.85, 0.95, 100, "1.00"),
                report(0.78, 0.85, 0.95, 130, "1.30"));

        assertThat(decision.ruleOneMet()).isTrue();
        assertThat(decision.promotionEligible()).isTrue();
        assertThat(decision.reason()).isEqualTo("RULE_1");
    }

    @Test
    void blocksPromotionWhenOriginalM5GatesStillFail() {
        var decision = calculator.decide(report(0.70, 0.85, 0.95, 100, "1.00"),
                report(0.80, 0.84, 0.95, 100, "1.00"));

        assertThat(decision.ruleOneMet()).isTrue();
        assertThat(decision.promotionEligible()).isFalse();
        assertThat(decision.reason()).isEqualTo("M5_GATES_NOT_MET");
    }

    @Test
    void keepsComplexSubsetRuleNonEligibleAndUnknownCostFromPassingRuleOne() {
        var baseline = report(0.70, 0.85, 0.95, 100, null);
        var candidate = report(0.80, 0.90, 0.98, 100, null);

        var decision = calculator.decide(baseline, candidate);

        assertThat(decision.ruleOneMet()).isFalse();
        assertThat(decision.complexSubsetRuleEligible()).isFalse();
        assertThat(decision.promotionEligible()).isFalse();
        assertThat(decision.reason()).isEqualTo("UNKNOWN_COST");
    }

    private EvaluationReport report(
            double hardGate, double support, double localization, long p95, String cost) {
        var versions = new ExecutionVersions("c", "p", "pr", "m", "e", "ch", "r", "g", "l", "cfg");
        var amount = cost == null ? null : new BigDecimal(cost);
        return new EvaluationReport(
                UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), "b".repeat(64), versions,
                ExecutionMode.AGENT, "policy-v1", List.of(
                metric("answerHardGatePassRate", hardGate), metric("strictClaimEvidenceSupport", support),
                metric("citationLocalization", localization)),
                new UsageSummary(1, 1, 1, p95, amount, "CNY", cost == null ? 1 : 0, Map.of()),
                new OperationalSummary(p95, amount, cost == null ? 1 : 0, Map.of()), Map.of(), Instant.EPOCH);
    }

    private MetricValue metric(String name, double value) {
        return new MetricValue(name, value, 1, value, Map.of());
    }
}
