package com.videopilot.analysis.application;

import com.videopilot.analysis.port.ExecutionBudgetStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

public final class ExecutionBudgetPolicy {

    private final ExecutionBudgetStore store;
    private final ExecutionLimits limits;
    private final Supplier<Instant> now;

    public ExecutionBudgetPolicy(ExecutionBudgetStore store, ExecutionLimits limits, Supplier<Instant> now) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
        this.now = java.util.Objects.requireNonNull(now, "now");
    }

    public void beforeCall(UUID jobId, String operation, long projectedInputUnits) {
        beforeCall(jobId, operation, projectedInputUnits, true);
    }

    public void beforeInteractiveCall(UUID jobId, String operation, long projectedInputUnits) {
        beforeCall(jobId, operation, projectedInputUnits, false);
    }

    private void beforeCall(UUID jobId, String operation, long projectedInputUnits, boolean enforceDeadline) {
        if (projectedInputUnits < 0) throw new IllegalArgumentException("projected input units cannot be negative");
        ExecutionBudgetStore.Usage usage = store.load(jobId);
        commonChecks(usage, enforceDeadline);
        Integer maxCalls = limits.maxCallsByOperation().get(operation);
        if (maxCalls == null) throw new IllegalArgumentException("unknown budget operation: " + operation);
        ExecutionBudgetStore.OperationUsage operationUsage = usage.usageByOperation().getOrDefault(
                operation, new ExecutionBudgetStore.OperationUsage(0, 0, 0));
        long calls = operationUsage.calls();
        if (calls >= maxCalls) {
            exceeded(ExecutionBudgetExceededException.Reason.CALL_COUNT);
        }
        if (operationUsage.inputUnits() + projectedInputUnits
                > limits.maxInputUnitsByOperation().get(operation)) {
            exceeded(ExecutionBudgetExceededException.Reason.INPUT_UNITS);
        }
    }

    public void checkJob(UUID jobId) {
        commonChecks(store.load(jobId), true);
    }

    public void afterCall(UUID jobId) {
        commonChecks(store.load(jobId), true);
    }

    public void afterInteractiveCall(UUID jobId) {
        commonChecks(store.load(jobId), false);
    }

    public void afterCall(
            UUID jobId, String operation, long addedInputUnits, long addedOutputUnits, BigDecimal addedKnownCost) {
        afterCall(jobId, operation, addedInputUnits, addedOutputUnits, addedKnownCost, true);
    }

    public void afterInteractiveCall(
            UUID jobId, String operation, long addedInputUnits, long addedOutputUnits, BigDecimal addedKnownCost) {
        afterCall(jobId, operation, addedInputUnits, addedOutputUnits, addedKnownCost, false);
    }

    private void afterCall(
            UUID jobId, String operation, long addedInputUnits, long addedOutputUnits,
            BigDecimal addedKnownCost, boolean enforceDeadline) {
        if (addedInputUnits < 0 || addedOutputUnits < 0 || addedKnownCost == null || addedKnownCost.signum() < 0) {
            throw new IllegalArgumentException("completed call usage is invalid");
        }
        ExecutionBudgetStore.Usage usage = store.load(jobId);
        commonChecks(usage, enforceDeadline);
        if (!limits.maxCallsByOperation().containsKey(operation)) {
            throw new IllegalArgumentException("unknown budget operation: " + operation);
        }
        ExecutionBudgetStore.OperationUsage operationUsage = usage.usageByOperation().getOrDefault(
                operation, new ExecutionBudgetStore.OperationUsage(0, 0, 0));
        if (operationUsage.inputUnits() + addedInputUnits
                > limits.maxInputUnitsByOperation().get(operation)) {
            exceeded(ExecutionBudgetExceededException.Reason.INPUT_UNITS);
        }
        if (operationUsage.outputUnits() + addedOutputUnits
                > limits.maxOutputUnitsByOperation().get(operation)) {
            exceeded(ExecutionBudgetExceededException.Reason.OUTPUT_UNITS);
        }
        if (usage.knownCost().add(addedKnownCost).compareTo(limits.maxKnownCost()) > 0) {
            exceeded(ExecutionBudgetExceededException.Reason.KNOWN_COST);
        }
    }

    public String version() { return limits.version(); }

    private void commonChecks(ExecutionBudgetStore.Usage usage, boolean enforceDeadline) {
        if (enforceDeadline && !now.get().isBefore(usage.jobCreatedAt().plus(limits.jobDeadline()))) {
            exceeded(ExecutionBudgetExceededException.Reason.DEADLINE);
        }
        for (var entry : usage.usageByOperation().entrySet()) {
            Long maxInput = limits.maxInputUnitsByOperation().get(entry.getKey());
            Long maxOutput = limits.maxOutputUnitsByOperation().get(entry.getKey());
            if (maxInput == null || maxOutput == null) {
                throw new IllegalArgumentException("unknown budget operation: " + entry.getKey());
            }
            if (entry.getValue().inputUnits() > maxInput) {
                exceeded(ExecutionBudgetExceededException.Reason.INPUT_UNITS);
            }
            if (entry.getValue().outputUnits() > maxOutput) {
                exceeded(ExecutionBudgetExceededException.Reason.OUTPUT_UNITS);
            }
        }
        if (usage.knownCost().compareTo(limits.maxKnownCost()) > 0) {
            exceeded(ExecutionBudgetExceededException.Reason.KNOWN_COST);
        }
    }

    private void exceeded(ExecutionBudgetExceededException.Reason reason) {
        throw new ExecutionBudgetExceededException(reason, limits.version());
    }
}
