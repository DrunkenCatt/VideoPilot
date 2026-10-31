package com.videopilot.analysis.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AnalysisDispatchMessage(
        UUID messageId,
        UUID jobId,
        int schemaVersion,
        String requestId,
        Instant occurredAt) {

    public AnalysisDispatchMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
