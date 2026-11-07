package com.videopilot.answer.application;

public final class AnswerException extends RuntimeException {

    private final Reason reason;

    public AnswerException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public AnswerException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND,
        NOT_READY,
        IDEMPOTENCY_KEY_REUSED,
        IN_PROGRESS,
        BUDGET_EXHAUSTED,
        DEPENDENCY_UNAVAILABLE
    }
}
