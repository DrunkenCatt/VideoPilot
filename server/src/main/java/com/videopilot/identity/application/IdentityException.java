package com.videopilot.identity.application;

import java.util.Objects;

public final class IdentityException extends RuntimeException {

    private final Reason reason;

    public IdentityException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").name());
        this.reason = reason;
    }

    public IdentityException(Reason reason, Throwable cause) {
        super(Objects.requireNonNull(reason, "reason").name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_CREDENTIALS,
        AUTHENTICATION_REQUIRED,
        CSRF_REJECTED,
        RATE_LIMITED,
        DEPENDENCY_UNAVAILABLE
    }
}
