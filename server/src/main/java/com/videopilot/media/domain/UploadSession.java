package com.videopilot.media.domain;

import com.videopilot.identity.domain.OwnerId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record UploadSession(
        UUID uploadId,
        UUID mediaId,
        OwnerId ownerId,
        String objectKey,
        String providerUploadId,
        UploadStatus status,
        long partSizeBytes,
        int maxParts,
        Instant expiresAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public UploadSession {
        Objects.requireNonNull(uploadId, "uploadId");
        Objects.requireNonNull(mediaId, "mediaId");
        Objects.requireNonNull(ownerId, "ownerId");
        requireText(objectKey, "objectKey");
        requireText(providerUploadId, "providerUploadId");
        Objects.requireNonNull(status, "status");
        if (partSizeBytes <= 0 || maxParts <= 0) throw new IllegalArgumentException("invalid part limits");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public UploadSession completed(Instant at) {
        return withStatus(UploadStatus.COMPLETED, at);
    }

    public UploadSession withStatus(UploadStatus next, Instant at) {
        return new UploadSession(
                uploadId, mediaId, ownerId, objectKey, providerUploadId, next,
                partSizeBytes, maxParts, expiresAt, version + 1, createdAt, at);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
