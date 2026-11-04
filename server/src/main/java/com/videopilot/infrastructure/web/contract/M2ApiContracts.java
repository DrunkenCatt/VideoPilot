package com.videopilot.infrastructure.web.contract;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class M2ApiContracts {

    public static final int DEFAULT_SEGMENT_PAGE_SIZE = 100;
    public static final int MAX_SEGMENT_PAGE_SIZE = 500;

    private M2ApiContracts() {
    }

    public static final class Paths {

        private Paths() {
        }

        public static String jobContext(UUID jobId) {
            return M1ApiContracts.Paths.ANALYSIS_JOBS + "/" + requireId(jobId, "jobId") + "/context";
        }

        public static String jobContextSegments(UUID jobId) {
            return jobContext(jobId) + "/segments";
        }

        public static String mediaPlayback(UUID mediaId) {
            return M1ApiContracts.Paths.MEDIA_ASSETS + "/" + requireId(mediaId, "mediaId") + "/playback";
        }

        public static String keyframeAccess(UUID jobId, UUID keyframeId) {
            return jobContext(jobId) + "/keyframes/" + requireId(keyframeId, "keyframeId") + "/access";
        }

        private static UUID requireId(UUID value, String field) {
            return Objects.requireNonNull(value, field);
        }
    }

    public enum ContextStatus {
        BUILDING,
        READY,
        FAILED
    }

    public enum ChannelState {
        PENDING,
        AVAILABLE,
        DEGRADED,
        FAILED
    }

    public record ContextLinks(
            String segments,
            String playback) {
    }

    public record ContextResponse(
            UUID contextId,
            UUID jobId,
            UUID mediaId,
            ContextStatus status,
            String extractionVersion,
            String alignmentVersion,
            long durationMs,
            ChannelState asrState,
            ChannelState ocrState,
            long segmentCount,
            Instant updatedAt,
            ContextLinks links) {
    }

    public record OcrObservation(
            String text,
            double confidence) {
    }

    public record KeyframeReference(
            UUID keyframeId,
            long timestampMs,
            String access) {
    }

    public record SegmentResponse(
            UUID segmentId,
            long ordinal,
            long startMs,
            long endMs,
            String asrText,
            List<OcrObservation> ocr,
            List<KeyframeReference> keyframes) {
    }

    public record SegmentPageResponse(
            List<SegmentResponse> data,
            Long nextCursor,
            boolean hasMore) {
    }

    public record TemporaryObjectAccessResponse(
            String url,
            Instant expiresAt) {
    }
}
