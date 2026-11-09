package com.videopilot.analysis.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

public record ExecutionLimits(
        String version,
        Duration jobDeadline,
        Map<String, Integer> maxCallsByOperation,
        Map<String, Long> maxInputUnitsByOperation,
        Map<String, Long> maxOutputUnitsByOperation,
        BigDecimal maxKnownCost) {

    public ExecutionLimits {
        if (version == null || version.isBlank() || jobDeadline == null
                || jobDeadline.isZero() || jobDeadline.isNegative()
                || maxCallsByOperation == null || maxCallsByOperation.isEmpty()
                || maxCallsByOperation.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                        || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue() < 1)
                || invalidUnitLimits(maxInputUnitsByOperation, maxCallsByOperation)
                || invalidUnitLimits(maxOutputUnitsByOperation, maxCallsByOperation)
                || maxKnownCost == null
                || maxKnownCost.signum() <= 0) {
            throw new IllegalArgumentException("execution limits are invalid");
        }
        maxCallsByOperation = Map.copyOf(maxCallsByOperation);
        maxInputUnitsByOperation = Map.copyOf(maxInputUnitsByOperation);
        maxOutputUnitsByOperation = Map.copyOf(maxOutputUnitsByOperation);
    }

    private static boolean invalidUnitLimits(Map<String, Long> limits, Map<String, Integer> calls) {
        return limits == null || !limits.keySet().equals(calls.keySet())
                || limits.values().stream().anyMatch(value -> value == null || value < 1);
    }
}
