package com.videopilot.media.application;

import com.videopilot.media.domain.MediaStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record MediaAssetView(
        UUID mediaId,
        String originalFilename,
        long contentLength,
        String sha256,
        MediaStatus status,
        Optional<Long> durationMs,
        Instant createdAt,
        Instant updatedAt) {
}
