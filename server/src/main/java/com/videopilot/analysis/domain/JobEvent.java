package com.videopilot.analysis.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record JobEvent(
        UUID messageId,
        UUID jobId,
        long sequence,
        JobEventType eventType,
        int schemaVersion,
        String requestId,
        String payload,
        Optional<OperationMetadata> operation,
        Instant occurredAt) {

    public JobEvent {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(eventType, "eventType");
        requestId = requireText(requestId, "requestId", 100);
        payload = requireText(payload, "payload", 65535);
        operation = Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (sequence < 1 || schemaVersion < 1) {
            throw new IllegalArgumentException("sequence and schemaVersion must be positive");
        }
    }

    public JobEvent(
            UUID messageId, UUID jobId, long sequence, JobEventType eventType, int schemaVersion,
            String requestId, String payload, Instant occurredAt) {
        this(messageId, jobId, sequence, eventType, schemaVersion, requestId, payload,
                Optional.empty(), occurredAt);
    }

    public record OperationMetadata(String key, String fingerprint, int attempt) {
        public OperationMetadata {
            key = requireText(key, "operation key", 128);
            fingerprint = requireText(fingerprint, "operation fingerprint", 64);
            if (attempt < 1) {
                throw new IllegalArgumentException("operation attempt must be positive");
            }
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1 to " + maxLength + " characters");
        }
        return value;
    }
}
