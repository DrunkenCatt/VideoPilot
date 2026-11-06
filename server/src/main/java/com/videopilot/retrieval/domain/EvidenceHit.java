package com.videopilot.retrieval.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record EvidenceHit(
        UUID chunkId,
        List<UUID> segmentIds,
        long startMs,
        long endMs,
        String excerpt,
        List<Channel> channels,
        Scores scores) {

    public EvidenceHit {
        Objects.requireNonNull(chunkId, "chunkId");
        segmentIds = List.copyOf(Objects.requireNonNull(segmentIds, "segmentIds"));
        channels = List.copyOf(Objects.requireNonNull(channels, "channels"));
        scores = Objects.requireNonNull(scores, "scores");
        if (segmentIds.isEmpty() || startMs < 0 || endMs <= startMs
                || excerpt == null || excerpt.isBlank() || channels.isEmpty()) {
            throw new IllegalArgumentException("evidence hit is invalid");
        }
    }

    public enum Channel {
        ASR,
        OCR,
        KEYFRAME
    }

    public enum DegradedChannel {
        VECTOR_STORE,
        SEMANTIC,
        OCR
    }

    public record Scores(
            double semanticRaw,
            double semanticNormalized,
            double semanticWeighted,
            double keywordRaw,
            double keywordNormalized,
            double keywordWeighted,
            double visualRaw,
            double visualNormalized,
            double visualWeighted,
            double total) {

        public Scores {
            if (!finiteNonNegative(semanticRaw, semanticNormalized, semanticWeighted,
                    keywordRaw, keywordNormalized, keywordWeighted,
                    visualRaw, visualNormalized, visualWeighted, total)) {
                throw new IllegalArgumentException("evidence scores must be finite and non-negative");
            }
        }

        private static boolean finiteNonNegative(double... values) {
            for (double value : values) {
                if (!Double.isFinite(value) || value < 0) return false;
            }
            return true;
        }
    }
}
