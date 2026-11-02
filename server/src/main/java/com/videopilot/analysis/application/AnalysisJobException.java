package com.videopilot.analysis.application;

public final class AnalysisJobException extends RuntimeException {

    private final Reason reason;

    public AnalysisJobException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_REQUEST,
        RESOURCE_NOT_FOUND,
        MEDIA_NOT_READY,
        IDEMPOTENCY_KEY_REUSED,
        JOB_NOT_CANCELABLE,
        CONFLICT
    }
}
