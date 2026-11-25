package com.videopilot.media.application;

public final class PlaybackProgressException extends RuntimeException {

    private final Reason reason;

    public PlaybackProgressException(Reason reason) {
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
