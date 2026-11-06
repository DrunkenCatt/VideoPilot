package com.videopilot.context.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.context.domain.VideoSegment;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class VideoContextAligner {

    private final ObjectMapper json;
    private final long windowMs;

    public VideoContextAligner(ObjectMapper json, long windowMs) {
        this.json = Objects.requireNonNull(json, "json");
        if (windowMs < 1) throw new IllegalArgumentException("windowMs must be positive");
        this.windowMs = windowMs;
    }

    public List<VideoSegment> align(Input input) {
        Objects.requireNonNull(input, "input");
        if (input.durationMs() < 1) throw new IllegalArgumentException("durationMs must be positive");
        List<Transcript> transcripts = input.transcripts().stream()
                .map(value -> clamp(value, input.durationMs()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(Transcript::startMs).thenComparing(Transcript::sourceRef))
                .toList();
        List<OcrObservation> ocr = input.ocr().stream()
                .map(value -> new OcrObservation(clampPoint(value.timestampMs(), input.durationMs()),
                        value.text(), value.confidence(), value.sourceRef()))
                .sorted(Comparator.comparingLong(OcrObservation::timestampMs).thenComparing(OcrObservation::sourceRef))
                .toList();
        List<Keyframe> keyframes = input.keyframes().stream()
                .map(value -> new Keyframe(clampPoint(value.timestampMs(), input.durationMs()),
                        value.sourceRef(), value.sha256()))
                .sorted(Comparator.comparingLong(Keyframe::timestampMs).thenComparing(Keyframe::sourceRef))
                .toList();

        List<VideoSegment> result = new ArrayList<>();
        for (long start = 0; start < input.durationMs(); start += windowMs) {
            long windowStart = start;
            long end = Math.min(input.durationMs(), windowStart + windowMs);
            int ordinal = result.size();
            List<Transcript> segmentTranscripts = overlapping(transcripts, windowStart, end);
            String asr = segmentTranscripts.stream().map(Transcript::text)
                    .filter(value -> !value.isBlank()).distinct().reduce((left, right) -> left + " " + right)
                    .orElse("");
            String ocrJson = write(ocr.stream().filter(value -> inWindow(value.timestampMs(), windowStart, end))
                    .map(value -> new OcrJson(value.timestampMs(), value.text(), value.confidence(), value.sourceRef()))
                    .toList());
            String keyframeJson = write(keyframes.stream()
                    .filter(value -> inWindow(value.timestampMs(), windowStart, end))
                    .map(value -> new KeyframeJson(
                            stableId(value.sourceRef() + '|' + value.timestampMs() + '|' + value.sha256()),
                            value.timestampMs(), value.sourceRef(), value.sha256()))
                    .toList());
            String digest = sha256(ordinal + "|" + windowStart + "|" + end + "|" + asr + "|" + ocrJson
                    + "|" + keyframeJson + "|" + segmentTranscripts.stream().map(Transcript::sourceRef).toList());
            result.add(new VideoSegment(stableId(input.contextId() + "|" + digest), ordinal, windowStart, end,
                    asr.isBlank() ? Optional.empty() : Optional.of(asr), ocrJson, keyframeJson, digest));
        }
        return List.copyOf(result);
    }

    private List<Transcript> overlapping(List<Transcript> values, long start, long end) {
        return values.stream().filter(value -> value.startMs() < end && value.endMs() > start).toList();
    }

    private boolean inWindow(long timestampMs, long start, long end) {
        return timestampMs >= start && timestampMs < end;
    }

    private Transcript clamp(Transcript value, long durationMs) {
        long start = Math.max(0, Math.min(value.startMs(), durationMs));
        long end = Math.max(0, Math.min(value.endMs(), durationMs));
        if (end <= start) return null;
        return new Transcript(start, end, value.text().trim(), value.sourceRef());
    }

    private long clampPoint(long value, long durationMs) {
        return Math.max(0, Math.min(value, durationMs - 1));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("aligned context cannot be serialized", exception);
        }
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    public record Input(
            UUID contextId,
            long durationMs,
            List<Transcript> transcripts,
            List<OcrObservation> ocr,
            List<Keyframe> keyframes) {
        public Input {
            Objects.requireNonNull(contextId, "contextId");
            transcripts = List.copyOf(transcripts);
            ocr = List.copyOf(ocr);
            keyframes = List.copyOf(keyframes);
        }
    }

    public record Transcript(long startMs, long endMs, String text, String sourceRef) {
        public Transcript {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(sourceRef, "sourceRef");
        }
    }

    public record OcrObservation(long timestampMs, String text, double confidence, String sourceRef) {
        public OcrObservation {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(sourceRef, "sourceRef");
        }
    }

    public record Keyframe(long timestampMs, String sourceRef, String sha256) {
        public Keyframe {
            Objects.requireNonNull(sourceRef, "sourceRef");
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must be lowercase SHA-256");
            }
        }
    }

    private record OcrJson(long timestampMs, String text, double confidence, String sourceRef) {}

    private record KeyframeJson(UUID keyframeId, long timestampMs, String sourceRef, String sha256) {}
}
