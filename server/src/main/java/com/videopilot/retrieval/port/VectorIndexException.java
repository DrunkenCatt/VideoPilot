package com.videopilot.retrieval.port;

public final class VectorIndexException extends RuntimeException {
    public VectorIndexException(String code) {
        super(code);
    }

    public VectorIndexException(String code, Throwable cause) {
        super(code, cause);
    }
}
