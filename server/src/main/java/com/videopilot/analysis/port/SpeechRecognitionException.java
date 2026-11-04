package com.videopilot.analysis.port;

public final class SpeechRecognitionException extends RuntimeException {

    private final Reason reason;
    private final String errorCode;

    public SpeechRecognitionException(Reason reason, String errorCode) {
        super(reason + ": " + errorCode);
        this.reason = reason;
        this.errorCode = errorCode;
    }

    public SpeechRecognitionException(Reason reason, String errorCode, Throwable cause) {
        super(reason + ": " + errorCode, cause);
        this.reason = reason;
        this.errorCode = errorCode;
    }

    public Reason reason() {
        return reason;
    }

    public String errorCode() {
        return errorCode;
    }

    public enum Reason {
        RETRYABLE,
        NON_RETRYABLE,
        UNKNOWN_OUTCOME
    }
}
