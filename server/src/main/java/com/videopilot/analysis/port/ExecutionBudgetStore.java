package com.videopilot.analysis.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface ExecutionBudgetStore {

    Usage load(UUID jobId);

    record Usage(
            Instant jobCreatedAt,
            Map<String, Long> callsByOperation,
            long inputUnits,
            long outputUnits,
            BigDecimal knownCost,
            boolean unknownPrice) {
        public Usage {
            callsByOperation = Map.copyOf(callsByOperation);
        }
    }
}
