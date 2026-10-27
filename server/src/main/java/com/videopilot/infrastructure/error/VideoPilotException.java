package com.videopilot.infrastructure.error;

import java.util.Objects;

public final class VideoPilotException extends RuntimeException {

    private final ErrorCode errorCode;

    public VideoPilotException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode, "errorCode").publicMessage());
        this.errorCode = errorCode;
    }

    public VideoPilotException(ErrorCode errorCode, Throwable cause) {
        super(Objects.requireNonNull(errorCode, "errorCode").publicMessage(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
