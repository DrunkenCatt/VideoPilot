package com.videopilot.context.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record VideoSegment(
        UUID segmentId,
        int ordinal,
        long startMs,
        long endMs,
        Optional<String> asrText,
        String ocrObservations,
        String keyframes,
        String contentDigest) {

    public VideoSegment {
        Objects.requireNonNull(segmentId, "segmentId");
        if (ordinal < 0 || startMs < 0 || endMs <= startMs) {
            throw new IllegalArgumentException("segment position is invalid");
        }
        asrText = Objects.requireNonNull(asrText, "asrText");
        requireText(ocrObservations, "ocrObservations");
        requireText(keyframes, "keyframes");
        if (contentDigest == null || !contentDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentDigest must be lowercase SHA-256");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
