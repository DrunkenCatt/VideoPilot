package com.videopilot.context.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ContextWorkItem(
        UUID workItemId,
        UUID mediaId,
        UUID createdByJobId,
        ContextWorkItemKind kind,
        int ordinal,
        long startMs,
        long endMs,
        String inputFingerprint,
        ContextWorkItemStatus status,
        int attempt,
        Optional<UUID> claimId,
        boolean retryable,
        long version,
        Instant updatedAt) {

    public ContextWorkItem {
        Objects.requireNonNull(workItemId, "workItemId");
        Objects.requireNonNull(mediaId, "mediaId");
        Objects.requireNonNull(createdByJobId, "createdByJobId");
        Objects.requireNonNull(kind, "kind");
        if (ordinal < 0 || startMs < 0 || endMs <= startMs || attempt < 0 || version < 0) {
            throw new IllegalArgumentException("work item numeric values are invalid");
        }
        if (inputFingerprint == null || !inputFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("inputFingerprint must be lowercase SHA-256");
        }
        Objects.requireNonNull(status, "status");
        claimId = Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
