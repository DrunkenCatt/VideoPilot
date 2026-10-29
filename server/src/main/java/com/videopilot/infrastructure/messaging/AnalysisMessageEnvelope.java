package com.videopilot.infrastructure.messaging;

import com.videopilot.infrastructure.trace.TraceContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AnalysisMessageEnvelope(
        UUID messageId,
        UUID jobId,
        EventType eventType,
        int schemaVersion,
        String requestId,
        Instant occurredAt) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public AnalysisMessageEnvelope {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported schemaVersion");
        }
        if (TraceContext.optionalId(requestId) == null) {
            throw new IllegalArgumentException("requestId must be a safe trace identifier");
        }
    }

    public enum EventType {
        EXECUTE_JOB
    }
}
