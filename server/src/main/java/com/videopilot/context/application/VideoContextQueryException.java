package com.videopilot.context.application;

public final class VideoContextQueryException extends RuntimeException {

    private final Reason reason;

    public VideoContextQueryException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND,
        NOT_READY,
        DEPENDENCY_UNAVAILABLE
    }
}
