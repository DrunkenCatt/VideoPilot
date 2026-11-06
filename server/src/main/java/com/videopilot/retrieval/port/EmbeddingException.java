package com.videopilot.retrieval.port;

public final class EmbeddingException extends RuntimeException {

    private final Reason reason;
    private final String code;

    public EmbeddingException(Reason reason, String code) {
        super(code);
        this.reason = reason;
        this.code = code;
    }

    public EmbeddingException(Reason reason, String code, Throwable cause) {
        super(code, cause);
        this.reason = reason;
        this.code = code;
    }

    public Reason reason() {
        return reason;
    }

    public String code() {
        return code;
    }

    public enum Reason {
        RETRYABLE,
        NON_RETRYABLE,
        UNKNOWN_OUTCOME
    }
}
