package com.videopilot.media.port;

public final class MediaStorageException extends RuntimeException {

    private final Reason reason;

    public MediaStorageException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public MediaStorageException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        PARTS_MISMATCH,
        OBJECT_MISMATCH,
        DEPENDENCY_UNAVAILABLE
    }
}
