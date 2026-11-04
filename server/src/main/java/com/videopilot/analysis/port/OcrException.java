package com.videopilot.analysis.port;

public final class OcrException extends RuntimeException {

    private final Reason reason;
    private final String errorCode;

    public OcrException(Reason reason, String errorCode) {
        super(errorCode);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
        this.errorCode = requireCode(errorCode);
    }

    public OcrException(Reason reason, String errorCode, Throwable cause) {
        super(errorCode, cause);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
        this.errorCode = requireCode(errorCode);
    }

    public Reason reason() {
        return reason;
    }

    public String errorCode() {
        return errorCode;
    }

    private static String requireCode(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("errorCode is required");
        return value;
    }

    public enum Reason {
        RETRYABLE,
        NON_RETRYABLE,
        CANCELED
    }
}
