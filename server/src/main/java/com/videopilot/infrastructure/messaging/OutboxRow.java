package com.videopilot.infrastructure.messaging;

import java.time.Instant;
import java.util.UUID;

record OutboxRow(
        long outboxId,
        UUID messageId,
        UUID jobId,
        int schemaVersion,
        String requestId,
        Instant occurredAt,
        int publishAttempts) {
}
