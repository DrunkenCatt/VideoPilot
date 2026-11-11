package com.videopilot.retrieval.application;

import com.videopilot.analysis.application.ExecutionBudgetPolicy;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.retrieval.domain.EvidenceHit;
import com.videopilot.retrieval.domain.VideoChunk;
import com.videopilot.retrieval.port.EmbeddingException;
import com.videopilot.retrieval.port.EmbeddingPort;
import com.videopilot.retrieval.port.VectorIndexException;
import com.videopilot.retrieval.port.VectorIndexPort;
import com.videopilot.retrieval.port.VideoIndexStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class RetrievalApplicationService {

    public static final String FORMULA_VERSION = "hybrid-0.60-0.25-0.15-v1";
    private static final double SEMANTIC_WEIGHT = 0.60;
    private static final double KEYWORD_WEIGHT = 0.25;
    private static final double VISUAL_WEIGHT = 0.15;
    private static final int EXCERPT_LIMIT = 320;
    private static final Set<String> READY_STATUSES = Set.of("READY", "VECTOR_STORE_DEGRADED", "SEMANTIC_DEGRADED");
    private static final Pattern TOKEN_RUN = Pattern.compile("[\\p{IsHan}]+|[\\p{L}\\p{N}]{2,}");

    private final VideoIndexStore store;
    private final EmbeddingPort embeddings;
    private final VectorIndexPort vectors;
    private final String embeddingProvider;
    private final String embeddingModel;
    private final String embeddingVersion;
    private final String vectorCollection;
    private final int embeddingDimensions;
    private final int candidateLimit;
    private final int maxQueryLength;
    private final Supplier<Instant> now;
    private final Supplier<UUID> ids;
    private final ExecutionBudgetPolicy budget;

    public RetrievalApplicationService(
            VideoIndexStore store,
            EmbeddingPort embeddings,
            VectorIndexPort vectors,
            String embeddingProvider,
            String embeddingModel,
            String embeddingVersion,
            String vectorCollection,
            int embeddingDimensions,
            int candidateLimit,
            int maxQueryLength,
            Supplier<Instant> now,
            Supplier<UUID> ids) {
        this(store, embeddings, vectors, embeddingProvider, embeddingModel, embeddingVersion,
                vectorCollection, embeddingDimensions, candidateLimit, maxQueryLength, now, ids, null);
    }

    public RetrievalApplicationService(
            VideoIndexStore store,
            EmbeddingPort embeddings,
            VectorIndexPort vectors,
            String embeddingProvider,
            String embeddingModel,
            String embeddingVersion,
            String vectorCollection,
            int embeddingDimensions,
            int candidateLimit,
            int maxQueryLength,
            Supplier<Instant> now,
            Supplier<UUID> ids,
            ExecutionBudgetPolicy budget) {
        this.store = store;
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.embeddingProvider = requireText(embeddingProvider);
        this.embeddingModel = requireText(embeddingModel);
        this.embeddingVersion = requireText(embeddingVersion);
        this.vectorCollection = requireText(vectorCollection);
        if (embeddingDimensions < 1 || candidateLimit < 1 || candidateLimit > 200 || maxQueryLength < 1) {
            throw new IllegalArgumentException("retrieval limits are invalid");
        }
        this.embeddingDimensions = embeddingDimensions;
        this.candidateLimit = candidateLimit;
        this.maxQueryLength = maxQueryLength;
        this.now = now;
        this.ids = ids;
        this.budget = budget;
    }

    public RetrievalResult retrieve(OwnerContext owner, UUID jobId, String query, int topK) {
        return retrieve(owner, jobId, query, topK, null, null, null);
    }

    public RetrievalResult retrieve(
            OwnerContext owner, UUID jobId, String query, int topK, UUID executionRunId) {
        return retrieve(owner, jobId, query, topK, null, null, executionRunId);
    }

    public RetrievalResult retrieve(
            OwnerContext owner, UUID jobId, String query, int topK,
            UUID evaluationRunId, String evaluationCaseId) {
        return retrieve(owner, jobId, query, topK, evaluationRunId, evaluationCaseId, null);
    }

    private RetrievalResult retrieve(
            OwnerContext owner, UUID jobId, String query, int topK,
            UUID evaluationRunId, String evaluationCaseId, UUID executionRunId) {
        String normalizedQuery = normalizeQuery(query);
        if (topK < 1 || topK > 20) throw new RetrievalException(RetrievalException.Reason.INVALID_REQUEST);
        VideoIndexStore.RetrievalSnapshot snapshot = store
                .findRetrievalSnapshot(owner.ownerId(), jobId, candidateLimit)
                .orElseThrow(() -> new RetrievalException(RetrievalException.Reason.NOT_FOUND));
        if (!READY_STATUSES.contains(snapshot.status())) {
            throw new RetrievalException(RetrievalException.Reason.NOT_READY);
        }

        EnumSet<EvidenceHit.DegradedChannel> degraded = EnumSet.noneOf(EvidenceHit.DegradedChannel.class);
        if (!"AVAILABLE".equals(snapshot.ocrState())) degraded.add(EvidenceHit.DegradedChannel.OCR);
        Map<UUID, Double> semantic = semantic(
                owner, snapshot, normalizedQuery, degraded,
                evaluationRunId, evaluationCaseId, executionRunId);
        Set<String> queryTokens = tokens(normalizedQuery);
        Map<UUID, Double> keyword = new LinkedHashMap<>();
        Map<UUID, Double> visual = new LinkedHashMap<>();
        for (VideoChunk chunk : snapshot.chunks()) {
            keyword.put(chunk.chunkId(), overlap(queryTokens, tokens(
                    chunk.asrText() + " " + String.join(" ", chunk.keywords()))));
            visual.put(chunk.chunkId(), overlap(queryTokens, tokens(chunk.ocrText())));
        }

        Map<UUID, Double> semanticNormalized = normalizeScores(semantic);
        Map<UUID, Double> keywordNormalized = normalizeScores(keyword);
        Map<UUID, Double> visualNormalized = normalizeScores(visual);
        List<EvidenceHit> hits = snapshot.chunks().stream()
                .map(chunk -> hit(chunk, semantic, semanticNormalized, keyword, keywordNormalized, visual, visualNormalized))
                .filter(hit -> hit.scores().total() > 0)
                .sorted(Comparator.comparingDouble((EvidenceHit hit) -> hit.scores().total()).reversed()
                        .thenComparingLong(EvidenceHit::startMs)
                        .thenComparing(hit -> hit.chunkId().toString()))
                .limit(topK)
                .toList();
        return new RetrievalResult(normalizedQuery, FORMULA_VERSION, snapshot.embeddingVersion(),
                snapshot.chunkVersion(), List.copyOf(degraded), hits);
    }

    private Map<UUID, Double> semantic(
            OwnerContext owner,
            VideoIndexStore.RetrievalSnapshot snapshot,
            String query,
            EnumSet<EvidenceHit.DegradedChannel> degraded,
            UUID evaluationRunId,
            String evaluationCaseId,
            UUID executionRunId) {
        if ("SEMANTIC_DEGRADED".equals(snapshot.status()) || snapshot.embeddings().isEmpty()) {
            degraded.add(EvidenceHit.DegradedChannel.SEMANTIC);
            return Map.of();
        }
        String fingerprint = sha256(snapshot.indexId() + "|" + embeddingVersion + "|" + query);
        if (budget != null) {
            if (evaluationRunId == null) {
                budget.beforeInteractiveCall(snapshot.jobId(), "EMBEDDING", 0);
            } else {
                budget.beforeInteractiveCall(snapshot.jobId(), "EMBEDDING", 0, evaluationRunId);
            }
        }
        int attempt = store.nextRetrievalEmbeddingAttempt(
                snapshot.jobId(), "RETRIEVAL_EMBEDDING", embeddingVersion, fingerprint);
        UUID callId = ids.get();
        Instant startedAt = now.get();
        store.startRetrievalEmbeddingCall(new VideoIndexStore.RetrievalEmbeddingCallStart(
                callId, snapshot.jobId(), ids.get().toString(), "RETRIEVAL_EMBEDDING",
                embeddingProvider, embeddingModel, embeddingVersion, fingerprint, attempt,
                executionRunId, evaluationRunId, evaluationCaseId, startedAt));
        List<Float> queryVector;
        try {
            EmbeddingPort.Result result = embeddings.embed(new EmbeddingPort.Request(
                    List.of(query), fingerprint));
            if (result.vectors().size() != 1 || result.vectors().getFirst().size() != embeddingDimensions) {
                throw new EmbeddingException(EmbeddingException.Reason.NON_RETRYABLE, "INVALID_RESPONSE");
            }
            queryVector = result.vectors().getFirst();
            Instant completedAt = now.get();
            store.completeRetrievalEmbeddingCall(new VideoIndexStore.RetrievalEmbeddingCompletion(
                    callId, result.inputTokens(), latency(startedAt, completedAt), completedAt));
            if (budget != null) {
                if (evaluationRunId == null) {
                    budget.afterInteractiveCall(snapshot.jobId());
                } else {
                    budget.afterInteractiveCall(snapshot.jobId(), evaluationRunId);
                }
            }
        } catch (EmbeddingException exception) {
            Instant completedAt = now.get();
            store.failEmbedding(new VideoIndexStore.EmbeddingFailure(
                    callId,
                    exception.reason() == EmbeddingException.Reason.UNKNOWN_OUTCOME ? "UNKNOWN_OUTCOME" : "FAILED",
                    exception.reason().name(), exception.code(), latency(startedAt, completedAt), completedAt));
            degraded.add(EvidenceHit.DegradedChannel.SEMANTIC);
            return Map.of();
        }
        if ("VECTOR_STORE_DEGRADED".equals(snapshot.status())) {
            degraded.add(EvidenceHit.DegradedChannel.VECTOR_STORE);
            return cosineScores(snapshot, queryVector);
        }
        try {
            Map<UUID, Double> result = new LinkedHashMap<>();
            vectors.query(new VectorIndexPort.Query(
                    snapshot.vectorCollection() == null ? vectorCollection : snapshot.vectorCollection(),
                    owner.ownerId(), snapshot.mediaId(), snapshot.contextId(), queryVector, candidateLimit))
                    .forEach(point -> result.put(point.pointId(), Math.max(0, point.score())));
            return result;
        } catch (VectorIndexException exception) {
            degraded.add(EvidenceHit.DegradedChannel.VECTOR_STORE);
            return cosineScores(snapshot, queryVector);
        }
    }

    private Map<UUID, Double> cosineScores(VideoIndexStore.RetrievalSnapshot snapshot, List<Float> query) {
        Map<UUID, Double> result = new LinkedHashMap<>();
        snapshot.chunks().forEach(chunk -> {
            List<Float> vector = snapshot.embeddings().get(chunk.chunkId());
            if (vector != null && vector.size() == query.size()) result.put(chunk.chunkId(), cosine(query, vector));
        });
        return result;
    }

    private EvidenceHit hit(
            VideoChunk chunk,
            Map<UUID, Double> semantic,
            Map<UUID, Double> semanticNormalized,
            Map<UUID, Double> keyword,
            Map<UUID, Double> keywordNormalized,
            Map<UUID, Double> visual,
            Map<UUID, Double> visualNormalized) {
        double sr = semantic.getOrDefault(chunk.chunkId(), 0.0);
        double sn = semanticNormalized.getOrDefault(chunk.chunkId(), 0.0);
        double kr = keyword.getOrDefault(chunk.chunkId(), 0.0);
        double kn = keywordNormalized.getOrDefault(chunk.chunkId(), 0.0);
        double vr = visual.getOrDefault(chunk.chunkId(), 0.0);
        double vn = visualNormalized.getOrDefault(chunk.chunkId(), 0.0);
        double sw = sn * SEMANTIC_WEIGHT;
        double kw = kn * KEYWORD_WEIGHT;
        double vw = vn * VISUAL_WEIGHT;
        List<EvidenceHit.Channel> channels = new ArrayList<>();
        if (kr > 0 || (sr > 0 && !chunk.asrText().isBlank())) channels.add(EvidenceHit.Channel.ASR);
        if (vr > 0 || (sr > 0 && !chunk.ocrText().isBlank())) channels.add(EvidenceHit.Channel.OCR);
        if (channels.isEmpty()) channels.add(EvidenceHit.Channel.ASR);
        String excerpt = chunk.summary().substring(0, Math.min(EXCERPT_LIMIT, chunk.summary().length()));
        return new EvidenceHit(chunk.chunkId(), chunk.segmentIds(), chunk.startMs(), chunk.endMs(), excerpt,
                channels, new EvidenceHit.Scores(sr, sn, sw, kr, kn, kw, vr, vn, vw, sw + kw + vw));
    }

    private Map<UUID, Double> normalizeScores(Map<UUID, Double> raw) {
        double max = raw.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        if (max <= 0) return Map.of();
        Map<UUID, Double> normalized = new LinkedHashMap<>();
        raw.forEach((key, value) -> normalized.put(key, Math.max(0, value) / max));
        return normalized;
    }

    private double overlap(Set<String> query, Set<String> document) {
        if (query.isEmpty() || document.isEmpty()) return 0;
        long matches = query.stream().filter(document::contains).count();
        return (double) matches / query.size();
    }

    private Set<String> tokens(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        TOKEN_RUN.matcher(value.toLowerCase(Locale.ROOT)).results().forEach(match -> {
            String token = match.group();
            if (token.codePoints().allMatch(point -> Character.UnicodeScript.of(point)
                    == Character.UnicodeScript.HAN)) {
                int[] points = token.codePoints().toArray();
                if (points.length == 1) result.add(token);
                for (int index = 0; index + 1 < points.length; index++) {
                    result.add(new String(points, index, 2));
                }
            } else {
                result.add(token);
            }
        });
        return result;
    }

    private double cosine(List<Float> left, List<Float> right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.size(); index++) {
            double l = left.get(index);
            double r = right.get(index);
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }
        if (leftNorm == 0 || rightNorm == 0) return 0;
        return Math.max(0, dot / Math.sqrt(leftNorm * rightNorm));
    }

    private String normalizeQuery(String query) {
        if (query == null) throw new RetrievalException(RetrievalException.Reason.INVALID_REQUEST);
        String value = query.replaceAll("\\s+", " ").trim();
        if (value.isEmpty() || value.length() > maxQueryLength) {
            throw new RetrievalException(RetrievalException.Reason.INVALID_REQUEST);
        }
        return value;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private long latency(Instant startedAt, Instant completedAt) {
        return Math.max(0, Duration.between(startedAt, completedAt).toMillis());
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("retrieval configuration is required");
        return value;
    }

    public record RetrievalResult(
            String query,
            String formulaVersion,
            String embeddingVersion,
            String chunkVersion,
            List<EvidenceHit.DegradedChannel> degradedChannels,
            List<EvidenceHit> hits) {
        public RetrievalResult {
            degradedChannels = List.copyOf(degradedChannels);
            hits = List.copyOf(hits);
        }
    }
}
