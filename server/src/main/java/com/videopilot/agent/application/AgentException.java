package com.videopilot.agent.application;

public final class AgentException extends RuntimeException {

    public enum Reason { INVALID_REQUEST, NOT_FOUND, IDEMPOTENCY_KEY_REUSED, CONFLICT }

    private final Reason reason;

    public AgentException(Reason reason) {
        this.reason = reason;
    }

    public AgentException(Reason reason, Throwable cause) {
        super(cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
