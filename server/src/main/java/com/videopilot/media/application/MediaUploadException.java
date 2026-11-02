package com.videopilot.media.application;

public final class MediaUploadException extends RuntimeException {

    private final Reason reason;

    public MediaUploadException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public MediaUploadException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_REQUEST,
        RESOURCE_NOT_FOUND,
        UPLOAD_EXPIRED,
        CONFLICT,
        DEPENDENCY_UNAVAILABLE
    }
}
