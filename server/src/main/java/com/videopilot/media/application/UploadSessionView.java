package com.videopilot.media.application;

import java.time.Instant;
import java.util.UUID;

public record UploadSessionView(
        UUID uploadId,
        UUID mediaId,
        long partSizeBytes,
        int maxParts,
        Instant expiresAt) {
}
