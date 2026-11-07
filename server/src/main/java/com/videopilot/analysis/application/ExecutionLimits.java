package com.videopilot.analysis.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

public record ExecutionLimits(
        String version,
        Duration jobDeadline,
        Map<String, Integer> maxCallsByOperation,
        long maxInputUnits,
        long maxOutputUnits,
        BigDecimal maxKnownCost) {

    public ExecutionLimits {
        if (version == null || version.isBlank() || jobDeadline == null
                || jobDeadline.isZero() || jobDeadline.isNegative()
                || maxCallsByOperation == null || maxCallsByOperation.isEmpty()
                || maxCallsByOperation.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                        || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue() < 1)
                || maxInputUnits < 1 || maxOutputUnits < 1 || maxKnownCost == null
                || maxKnownCost.signum() <= 0) {
            throw new IllegalArgumentException("execution limits are invalid");
        }
        maxCallsByOperation = Map.copyOf(maxCallsByOperation);
    }
}
