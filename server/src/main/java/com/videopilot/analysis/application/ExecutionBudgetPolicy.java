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
        if (projectedInputUnits < 0) throw new IllegalArgumentException("projected input units cannot be negative");
        ExecutionBudgetStore.Usage usage = store.load(jobId);
        commonChecks(usage);
        Integer maxCalls = limits.maxCallsByOperation().get(operation);
        if (maxCalls == null) throw new IllegalArgumentException("unknown budget operation: " + operation);
        long calls = usage.callsByOperation().getOrDefault(operation, 0L);
        if ("EMBEDDING".equals(operation)) {
            calls += usage.callsByOperation().getOrDefault("RETRIEVAL_EMBEDDING", 0L);
        }
        if (calls >= maxCalls) {
            exceeded(ExecutionBudgetExceededException.Reason.CALL_COUNT);
        }
        if (usage.inputUnits() + projectedInputUnits > limits.maxInputUnits()) {
            exceeded(ExecutionBudgetExceededException.Reason.INPUT_UNITS);
        }
    }

    public void checkJob(UUID jobId) {
        commonChecks(store.load(jobId));
    }

    public void afterCall(UUID jobId) {
        commonChecks(store.load(jobId));
    }

    public void afterCall(UUID jobId, long addedInputUnits, long addedOutputUnits, BigDecimal addedKnownCost) {
        if (addedInputUnits < 0 || addedOutputUnits < 0 || addedKnownCost == null || addedKnownCost.signum() < 0) {
            throw new IllegalArgumentException("completed call usage is invalid");
        }
        ExecutionBudgetStore.Usage usage = store.load(jobId);
        commonChecks(usage);
        if (usage.inputUnits() + addedInputUnits > limits.maxInputUnits()) {
            exceeded(ExecutionBudgetExceededException.Reason.INPUT_UNITS);
        }
        if (usage.outputUnits() + addedOutputUnits > limits.maxOutputUnits()) {
            exceeded(ExecutionBudgetExceededException.Reason.OUTPUT_UNITS);
        }
        if (usage.knownCost().add(addedKnownCost).compareTo(limits.maxKnownCost()) > 0) {
            exceeded(ExecutionBudgetExceededException.Reason.KNOWN_COST);
        }
    }

    public String version() { return limits.version(); }

    private void commonChecks(ExecutionBudgetStore.Usage usage) {
        if (!now.get().isBefore(usage.jobCreatedAt().plus(limits.jobDeadline()))) {
            exceeded(ExecutionBudgetExceededException.Reason.DEADLINE);
        }
        if (usage.inputUnits() > limits.maxInputUnits()) {
            exceeded(ExecutionBudgetExceededException.Reason.INPUT_UNITS);
        }
        if (usage.outputUnits() > limits.maxOutputUnits()) {
            exceeded(ExecutionBudgetExceededException.Reason.OUTPUT_UNITS);
        }
        if (usage.knownCost().compareTo(limits.maxKnownCost()) > 0) {
            exceeded(ExecutionBudgetExceededException.Reason.KNOWN_COST);
        }
    }

    private void exceeded(ExecutionBudgetExceededException.Reason reason) {
        throw new ExecutionBudgetExceededException(reason, limits.version());
    }
}
