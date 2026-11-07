package com.videopilot.infrastructure.error;

public enum ErrorCode {

    INVALID_REQUEST("The request is invalid.", false),
    AUTHENTICATION_REQUIRED("Authentication is required.", false),
    INVALID_CREDENTIALS("The credentials are invalid.", false),
    CSRF_REJECTED("The request could not be verified.", false),
    RESOURCE_NOT_FOUND("The requested resource was not found.", false),
    IDEMPOTENCY_KEY_REUSED("The idempotency key was reused with a different request.", false),
    UPLOAD_EXPIRED("The upload session has expired.", false),
    MEDIA_NOT_READY("The media asset is not ready for analysis.", false),
    JOB_NOT_CANCELABLE("The analysis job cannot be canceled in its current state.", false),
    RATE_LIMITED("Too many requests. Try again later.", true),
    BUDGET_EXHAUSTED("The execution budget has been exhausted.", false),
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
