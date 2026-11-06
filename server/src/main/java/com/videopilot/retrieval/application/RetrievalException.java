package com.videopilot.retrieval.application;

public final class RetrievalException extends RuntimeException {

    private final Reason reason;

    public RetrievalException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND,
        NOT_READY
    }
}
