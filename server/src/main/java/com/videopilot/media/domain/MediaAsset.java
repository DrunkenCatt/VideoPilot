package com.videopilot.media.domain;

import com.videopilot.identity.domain.OwnerId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record MediaAsset(
        UUID mediaId,
        OwnerId ownerId,
        String fingerprint,
        String objectKey,
        String originalFilename,
        long contentLength,
        String declaredContentType,
        MediaStatus status,
        Optional<Long> durationMs,
        Optional<MediaFailure> failure,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public MediaAsset {
        Objects.requireNonNull(mediaId, "mediaId");
        Objects.requireNonNull(ownerId, "ownerId");
        requireText(fingerprint, "fingerprint");
        requireText(objectKey, "objectKey");
        requireText(originalFilename, "originalFilename");
        if (contentLength <= 0) throw new IllegalArgumentException("contentLength must be positive");
        requireText(declaredContentType, "declaredContentType");
        Objects.requireNonNull(status, "status");
        durationMs = Objects.requireNonNull(durationMs, "durationMs");
        failure = Objects.requireNonNull(failure, "failure");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public MediaAsset(
            UUID mediaId,
            OwnerId ownerId,
            String fingerprint,
            String objectKey,
            String originalFilename,
            long contentLength,
            String declaredContentType,
            MediaStatus status,
            Optional<Long> durationMs,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this(mediaId, ownerId, fingerprint, objectKey, originalFilename, contentLength,
                declaredContentType, status, durationMs, Optional.empty(), version, createdAt, updatedAt);
    }

    public MediaAsset uploaded(Instant at) {
        return new MediaAsset(
                mediaId, ownerId, fingerprint, objectKey, originalFilename, contentLength,
                declaredContentType, MediaStatus.UPLOADED, durationMs, Optional.empty(),
                version + 1, createdAt, at);
    }

    public MediaAsset probing(Instant at) {
        if (status != MediaStatus.UPLOADED && status != MediaStatus.PROBING) {
            throw new IllegalStateException("only uploaded media can be probed");
        }
        return new MediaAsset(
                mediaId, ownerId, fingerprint, objectKey, originalFilename, contentLength,
                declaredContentType, MediaStatus.PROBING, Optional.empty(), Optional.empty(),
                version + 1, createdAt, at);
    }

    public MediaAsset ready(long trustedDurationMs, Instant at) {
        if (status != MediaStatus.PROBING || trustedDurationMs < 1) {
            throw new IllegalStateException("probing media and positive duration are required");
        }
        return new MediaAsset(
                mediaId, ownerId, fingerprint, objectKey, originalFilename, contentLength,
                declaredContentType, MediaStatus.READY, Optional.of(trustedDurationMs), Optional.empty(),
                version + 1, createdAt, at);
    }

    public MediaAsset failed(MediaFailure mediaFailure, Instant at) {
        if (status != MediaStatus.PROBING) {
            throw new IllegalStateException("only probing media can fail validation");
        }
        return new MediaAsset(
                mediaId, ownerId, fingerprint, objectKey, originalFilename, contentLength,
                declaredContentType, MediaStatus.FAILED, Optional.empty(), Optional.of(mediaFailure),
                version + 1, createdAt, at);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
