package com.videopilot.retrieval.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.context.domain.VideoContext;
import com.videopilot.context.domain.VideoContextStatus;
import com.videopilot.context.domain.VideoSegment;
import com.videopilot.retrieval.domain.VideoChunk;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class VideoChunkBuilder {

    public static final String CHUNK_VERSION = "chunk-5m-v1";
    public static final String INDEX_SCHEMA_VERSION = "m3-index-schema-v1";
    private static final long WINDOW_MS = 300_000;
    private static final int SUMMARY_LIMIT = 600;
    private static final int KEYWORD_LIMIT = 12;
    private static final Pattern TOKEN = Pattern.compile("[\\p{IsHan}]{2,}|[\\p{L}\\p{N}]{2,}");

    private final ObjectMapper json;

    public VideoChunkBuilder(ObjectMapper json) {
        this.json = json;
    }

    public BuildResult build(VideoContext context, List<VideoSegment> sourceSegments) {
        if (context.status() != VideoContextStatus.READY) {
            throw new IllegalArgumentException("only READY context can be chunked");
        }
        List<VideoSegment> segments = sourceSegments.stream()
                .sorted(Comparator.comparingInt(VideoSegment::ordinal))
                .toList();
        if (segments.isEmpty()) throw new IllegalArgumentException("context has no segments");
        String sourceDigest = sha256(segments.stream()
                .map(segment -> segment.contentDigest() + '|' + normalize(segment.asrText().orElse(""))
                        + '|' + normalize(ocrText(segment.ocrObservations())))
                .reduce("", (left, right) -> left + '\n' + right));
        String indexFingerprint = sha256(context.contextId() + "|" + context.version() + "|" + CHUNK_VERSION
                + "|" + sourceDigest + "|" + INDEX_SCHEMA_VERSION);
        List<VideoChunk> chunks = new ArrayList<>();
        int ordinal = 0;
        for (long start = 0; start < context.durationMs(); start += WINDOW_MS) {
            long end = Math.min(start + WINDOW_MS, context.durationMs());
            long windowStart = start;
            long windowEnd = end;
            List<VideoSegment> members = segments.stream()
                    .filter(segment -> segment.endMs() > windowStart && segment.startMs() < windowEnd)
                    .toList();
            if (members.isEmpty()) continue;
            String asr = normalize(members.stream().flatMap(segment -> segment.asrText().stream())
                    .reduce("", (left, right) -> left + ' ' + right));
            String ocr = normalize(members.stream().map(segment -> ocrText(segment.ocrObservations()))
                    .reduce("", (left, right) -> left + ' ' + right));
            String searchable = normalize(asr + ' ' + ocr);
            String summary = searchable.substring(0, Math.min(SUMMARY_LIMIT, searchable.length()));
            String chunkFingerprint = sha256(indexFingerprint + '|' + ordinal + '|' + start + '|' + end
                    + '|' + members.stream().map(VideoSegment::contentDigest).reduce("", String::concat));
            UUID chunkId = UUID.nameUUIDFromBytes(
                    (context.contextId() + "|" + indexFingerprint + "|" + ordinal).getBytes(StandardCharsets.UTF_8));
            chunks.add(new VideoChunk(chunkId, context.contextId(), context.mediaId(), ordinal, start, end,
                    members.stream().map(VideoSegment::segmentId).toList(), summary, keywords(searchable),
                    asr, ocr, chunkFingerprint, CHUNK_VERSION));
            ordinal++;
        }
        return new BuildResult(indexFingerprint, sourceDigest, List.copyOf(chunks));
    }

    private String ocrText(String observations) {
        try {
            JsonNode root = json.readTree(observations);
            LinkedHashSet<String> text = new LinkedHashSet<>();
            root.findValuesAsText("text").stream().map(this::normalize).filter(value -> !value.isBlank())
                    .forEach(text::add);
            return String.join(" ", text);
        } catch (Exception exception) {
            throw new IllegalArgumentException("OCR observations are invalid JSON", exception);
        }
    }

    private List<String> keywords(String text) {
        Map<String, Integer> counts = new HashMap<>();
        TOKEN.matcher(text.toLowerCase(Locale.ROOT)).results()
                .map(match -> match.group())
                .forEach(token -> counts.merge(token, 1, Integer::sum));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(KEYWORD_LIMIT)
                .map(Map.Entry::getKey)
                .toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    public record BuildResult(String inputFingerprint, String sourceDigest, List<VideoChunk> chunks) {
        public BuildResult {
            chunks = List.copyOf(chunks);
        }
    }
}
