package com.videopilot.evaluation.application;

public final class EvaluationException extends RuntimeException {

    private final Reason reason;

    public EvaluationException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public EvaluationException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND,
        IDEMPOTENCY_KEY_REUSED,
        DATASET_NOT_READY,
        EXECUTION_FAILED
    }
}
