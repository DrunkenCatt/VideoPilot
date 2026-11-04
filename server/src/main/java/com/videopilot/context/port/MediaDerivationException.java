package com.videopilot.context.port;

public final class MediaDerivationException extends RuntimeException {

    private final Reason reason;

    public MediaDerivationException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public MediaDerivationException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_MEDIA,
        TIMEOUT,
        OUTPUT_LIMIT,
        UNAVAILABLE
    }
}
