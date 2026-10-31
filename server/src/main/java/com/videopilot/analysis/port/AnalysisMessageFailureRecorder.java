package com.videopilot.analysis.port;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisMessageFailureRecorder {

    void record(MessageFailure failure);

    record MessageFailure(
            String failureKey,
            Optional<UUID> messageId,
            Optional<UUID> jobId,
            String errorCode,
            String summary,
            int deliveryAttempts,
            Instant occurredAt) {

        public MessageFailure {
            if (failureKey == null || !failureKey.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("failureKey must be a lowercase SHA-256 digest");
            }
            messageId = Objects.requireNonNull(messageId, "messageId");
            jobId = Objects.requireNonNull(jobId, "jobId");
            errorCode = requireText(errorCode, "errorCode", 100);
            summary = requireText(summary, "summary", 1000);
            if (deliveryAttempts < 1) {
                throw new IllegalArgumentException("deliveryAttempts must be positive");
            }
            Objects.requireNonNull(occurredAt, "occurredAt");
        }

        private static String requireText(String value, String field, int maxLength) {
            if (value == null || value.isBlank() || value.length() > maxLength) {
                throw new IllegalArgumentException(field + " must contain 1 to " + maxLength + " characters");
            }
            return value;
        }
    }
}
