package com.videopilot.answer.port;

public final class LanguageModelException extends RuntimeException {

    private final Reason reason;
    private final String code;
    private final String diagnosticSummary;

    public LanguageModelException(Reason reason, String code) {
        this(reason, code, null, null);
    }

    public LanguageModelException(Reason reason, String code, Throwable cause) {
        this(reason, code, null, cause);
    }

    public LanguageModelException(
            Reason reason, String code, String diagnosticSummary, Throwable cause) {
        super(code, cause);
        this.reason = reason;
        this.code = code;
        this.diagnosticSummary = diagnosticSummary;
    }

    public Reason reason() { return reason; }

    public String code() { return code; }

    public String diagnosticSummary() { return diagnosticSummary; }

    public enum Reason {
        RETRYABLE,
        NON_RETRYABLE,
        UNKNOWN_OUTCOME
    }
}
