package com.videopilot.evaluation.application;

import com.videopilot.evaluation.domain.EvaluationModels.EvaluationReport;
import com.videopilot.evaluation.domain.EvaluationModels.MetricValue;
import java.math.BigDecimal;

public final class EvaluationPromotionCalculator {

    public PromotionDecision decide(EvaluationReport baseline, EvaluationReport candidate) {
        double baselineHardGate = metric(baseline, "answerHardGatePassRate");
        double candidateHardGate = metric(candidate, "answerHardGatePassRate");
        double hardGateDelta = candidateHardGate - baselineHardGate;
        boolean latencyWithinThirty = growthWithin(
                baseline.operations().p95LatencyMs(), candidate.operations().p95LatencyMs(), 0.30);
        boolean costKnown = baseline.operations().averageCost() != null
                && candidate.operations().averageCost() != null
                && baseline.operations().unavailablePriceCalls() == 0
                && candidate.operations().unavailablePriceCalls() == 0;
        boolean costWithinThirty = costKnown && growthWithin(
                baseline.operations().averageCost(), candidate.operations().averageCost(), 0.30);
        boolean ruleOne = hardGateDelta >= 0.08 && latencyWithinThirty && costWithinThirty;
        boolean latencyReduced = reductionAtLeast(
                baseline.operations().p95LatencyMs(), candidate.operations().p95LatencyMs(), 0.20);
        boolean costReduced = costKnown && reductionAtLeast(
                baseline.operations().averageCost(), candidate.operations().averageCost(), 0.20);
        boolean ruleTwo = hardGateDelta >= -0.02 && (latencyReduced || costReduced);
        boolean m5Gates = metric(candidate, "strictClaimEvidenceSupport") >= 0.85
                && metric(candidate, "citationLocalization") >= 0.95;
        boolean eligible = (ruleOne || ruleTwo) && m5Gates;
        String reason = eligible ? (ruleOne ? "RULE_1" : "RULE_2")
                : !m5Gates ? "M5_GATES_NOT_MET"
                : !costKnown && hardGateDelta >= 0.08 ? "UNKNOWN_COST" : "NO_PROMOTION_RULE_MET";
        return new PromotionDecision(
                hardGateDelta, ruleOne, ruleTwo, false, m5Gates, costKnown, eligible, reason);
    }

    private double metric(EvaluationReport report, String name) {
        return report.metrics().stream().filter(value -> value.name().equals(name))
                .map(MetricValue::value).filter(java.util.Objects::nonNull).findFirst().orElse(0.0);
    }

    private boolean growthWithin(long baseline, long candidate, double threshold) {
        if (baseline == 0) return candidate == 0;
        return candidate <= baseline * (1 + threshold);
    }

    private boolean growthWithin(BigDecimal baseline, BigDecimal candidate, double threshold) {
        if (baseline.signum() == 0) return candidate.signum() == 0;
        return candidate.compareTo(baseline.multiply(BigDecimal.valueOf(1 + threshold))) <= 0;
    }

    private boolean reductionAtLeast(long baseline, long candidate, double threshold) {
        return baseline > 0 && candidate <= baseline * (1 - threshold);
    }

    private boolean reductionAtLeast(BigDecimal baseline, BigDecimal candidate, double threshold) {
        return baseline.signum() > 0
                && candidate.compareTo(baseline.multiply(BigDecimal.valueOf(1 - threshold))) <= 0;
    }

    public record PromotionDecision(
            double hardGatePassRateDelta,
            boolean ruleOneMet,
            boolean ruleTwoMet,
            boolean complexSubsetRuleEligible,
            boolean m5GatesMet,
            boolean costKnown,
            boolean promotionEligible,
            String reason) {
    }
}
