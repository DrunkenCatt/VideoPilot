package com.videopilot.context.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record VideoContext(
        UUID contextId,
        UUID sourceJobId,
        UUID mediaId,
        String inputFingerprint,
        String pipelineVersion,
        String extractionVersion,
        String alignmentVersion,
        long durationMs,
        VideoContextStatus status,
        ContextChannelState asrState,
        ContextChannelState ocrState,
        int segmentCount,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public VideoContext {
        Objects.requireNonNull(contextId, "contextId");
        Objects.requireNonNull(sourceJobId, "sourceJobId");
        Objects.requireNonNull(mediaId, "mediaId");
        requireText(inputFingerprint, "inputFingerprint");
        requireText(pipelineVersion, "pipelineVersion");
        requireText(extractionVersion, "extractionVersion");
        requireText(alignmentVersion, "alignmentVersion");
        if (durationMs <= 0 || segmentCount < 0 || version < 0) {
            throw new IllegalArgumentException("context numeric values are invalid");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(asrState, "asrState");
        Objects.requireNonNull(ocrState, "ocrState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
