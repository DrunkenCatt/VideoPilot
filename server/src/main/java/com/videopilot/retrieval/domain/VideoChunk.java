package com.videopilot.retrieval.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record VideoChunk(
        UUID chunkId,
        UUID contextId,
        UUID mediaId,
        int ordinal,
        long startMs,
        long endMs,
        List<UUID> segmentIds,
        String summary,
        List<String> keywords,
        String asrText,
        String ocrText,
        String inputFingerprint,
        String chunkVersion) {

    public VideoChunk {
        Objects.requireNonNull(chunkId, "chunkId");
        Objects.requireNonNull(contextId, "contextId");
        Objects.requireNonNull(mediaId, "mediaId");
        segmentIds = List.copyOf(Objects.requireNonNull(segmentIds, "segmentIds"));
        keywords = List.copyOf(Objects.requireNonNull(keywords, "keywords"));
        if (ordinal < 0 || startMs < 0 || endMs <= startMs || segmentIds.isEmpty()) {
            throw new IllegalArgumentException("chunk position and segments are required");
        }
        summary = requireText(summary, "summary");
        asrText = Objects.requireNonNull(asrText, "asrText");
        ocrText = Objects.requireNonNull(ocrText, "ocrText");
        if (!inputFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("inputFingerprint must be lowercase SHA-256");
        }
        chunkVersion = requireText(chunkVersion, "chunkVersion");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
