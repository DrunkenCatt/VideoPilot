package com.videopilot.media.domain;

import com.videopilot.identity.domain.OwnerId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PlaybackProgress(
        UUID mediaId,
        OwnerId ownerId,
        long positionMs,
        Instant updatedAt) {

    public PlaybackProgress {
        Objects.requireNonNull(mediaId, "mediaId");
        Objects.requireNonNull(ownerId, "ownerId");
        if (positionMs < 0) throw new IllegalArgumentException("positionMs must not be negative");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
