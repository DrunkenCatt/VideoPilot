package com.videopilot.context.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ContextRecoveryPolicy {

    private final Duration staleAfter;

    public ContextRecoveryPolicy(Duration staleAfter) {
        this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter");
        if (staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
    }

    public ContextRecoveryDecision decide(
            ContextWorkItemStatus status,
            boolean retryable,
            Instant updatedAt,
            Instant now) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(now, "now");

        return switch (status) {
            case PENDING -> ContextRecoveryDecision.CLAIM;
            case COMPLETED -> ContextRecoveryDecision.REUSE;
            case STARTED -> updatedAt.plus(staleAfter).isAfter(now)
                    ? ContextRecoveryDecision.WAIT
                    : ContextRecoveryDecision.CLAIM;
            case FAILED -> retryable ? ContextRecoveryDecision.CLAIM : ContextRecoveryDecision.STOP;
            case UNKNOWN -> ContextRecoveryDecision.REVIEW;
            case CANCELED -> ContextRecoveryDecision.STOP;
        };
    }
}
