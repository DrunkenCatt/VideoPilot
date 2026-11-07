package com.videopilot.infrastructure.analysis.persistence;

import java.time.Instant;

final class ReplayAuditRow {
    private String operationFingerprint;
    private int operationAttempt;
    private Instant occurredAt;

    String getOperationFingerprint() { return operationFingerprint; }
    int getOperationAttempt() { return operationAttempt; }
    Instant getOccurredAt() { return occurredAt; }
}
