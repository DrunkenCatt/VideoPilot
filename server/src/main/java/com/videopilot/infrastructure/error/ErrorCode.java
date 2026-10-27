package com.videopilot.infrastructure.error;

public enum ErrorCode {

    INVALID_REQUEST("The request is invalid.", false),
    RESOURCE_NOT_FOUND("The requested resource was not found.", false),
    CONFLICT("The request conflicts with the current state.", false),
    DEPENDENCY_UNAVAILABLE("A required dependency is unavailable.", true),
    INTERNAL_ERROR("An unexpected error occurred.", false);

    private final String publicMessage;
    private final boolean retryable;

    ErrorCode(String publicMessage, boolean retryable) {
        this.publicMessage = publicMessage;
        this.retryable = retryable;
    }

    public String publicMessage() {
        return publicMessage;
    }

    public boolean retryable() {
        return retryable;
    }
}
