package com.videopilot.analysis.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface ExecutionBudgetStore {

    Usage load(UUID jobId);

    record Usage(
            Instant jobCreatedAt,
            Map<String, OperationUsage> usageByOperation,
            BigDecimal knownCost,
            boolean unknownPrice) {
        public Usage {
            usageByOperation = Map.copyOf(usageByOperation);
        }

        public Map<String, Long> callsByOperation() {
            return usageByOperation.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> entry.getValue().calls()));
        }

        public long inputUnits() { return usageByOperation.values().stream().mapToLong(OperationUsage::inputUnits).sum(); }
        public long outputUnits() { return usageByOperation.values().stream().mapToLong(OperationUsage::outputUnits).sum(); }
    }

    record OperationUsage(long calls, long inputUnits, long outputUnits) { }
}
