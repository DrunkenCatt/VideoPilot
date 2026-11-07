package com.videopilot.analysis.application;

public final class ExecutionBudgetExceededException extends RuntimeException {

    private final Reason reason;
    private final String limitVersion;

    public ExecutionBudgetExceededException(Reason reason, String limitVersion) {
        super(reason.name());
        this.reason = reason;
        this.limitVersion = limitVersion;
    }

    public Reason reason() { return reason; }
    public String limitVersion() { return limitVersion; }

    public enum Reason {
        DEADLINE,
        CALL_COUNT,
        INPUT_UNITS,
        OUTPUT_UNITS,
        KNOWN_COST
    }
}
