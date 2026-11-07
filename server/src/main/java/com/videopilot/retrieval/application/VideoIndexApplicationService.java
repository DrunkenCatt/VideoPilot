package com.videopilot.retrieval.application;

import com.videopilot.analysis.application.ExecutionBudgetPolicy;
import com.videopilot.context.domain.VideoContext;
import com.videopilot.context.domain.VideoContextStatus;
import com.videopilot.context.domain.VideoSegment;
import com.videopilot.context.port.VideoContextStore;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.retrieval.port.VideoIndexStore;
import com.videopilot.retrieval.port.EmbeddingException;
import com.videopilot.retrieval.port.EmbeddingPort;
import com.videopilot.retrieval.port.VectorIndexException;
import com.videopilot.retrieval.port.VectorIndexPort;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public final class VideoIndexApplicationService {

    private static final int PAGE_SIZE = 500;
    private final VideoContextStore contexts;
    private final VideoIndexStore indexes;
    private final VideoChunkBuilder chunks;
    private final EmbeddingPort embeddings;
    private final VectorIndexPort vectors;
    private final String embeddingProvider;
    private final String embeddingModel;
    private final String embeddingVersion;
    private final String vectorCollection;
    private final int embeddingDimensions;
    private final int embeddingBatchSize;
    private final int embeddingTextCharacters;
    private final int maxEmbeddingAttempts;
    private final Supplier<Instant> now;
    private final Supplier<UUID> ids;
    private final ExecutionBudgetPolicy budget;

    public VideoIndexApplicationService(
            VideoContextStore contexts,
            VideoIndexStore indexes,
            VideoChunkBuilder chunks,
            EmbeddingPort embeddings,
            VectorIndexPort vectors,
            String embeddingProvider,
            String embeddingModel,
            String embeddingVersion,
            String vectorCollection,
            int embeddingDimensions,
            int embeddingBatchSize,
            int embeddingTextCharacters,
            int maxEmbeddingAttempts,
            Supplier<Instant> now,
            Supplier<UUID> ids) {
        this(contexts, indexes, chunks, embeddings, vectors, embeddingProvider, embeddingModel,
                embeddingVersion, vectorCollection, embeddingDimensions, embeddingBatchSize,
                embeddingTextCharacters, maxEmbeddingAttempts, now, ids, null);
    }

    public VideoIndexApplicationService(
            VideoContextStore contexts,
            VideoIndexStore indexes,
            VideoChunkBuilder chunks,
            EmbeddingPort embeddings,
            VectorIndexPort vectors,
            String embeddingProvider,
            String embeddingModel,
            String embeddingVersion,
            String vectorCollection,
            int embeddingDimensions,
            int embeddingBatchSize,
            int embeddingTextCharacters,
            int maxEmbeddingAttempts,
            Supplier<Instant> now,
            Supplier<UUID> ids,
            ExecutionBudgetPolicy budget) {
        this.contexts = contexts;
        this.indexes = indexes;
        this.chunks = chunks;
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.embeddingProvider = embeddingProvider;
        this.embeddingModel = embeddingModel;
        this.embeddingVersion = embeddingVersion;
        this.vectorCollection = vectorCollection;
        if (embeddingDimensions < 1 || embeddingBatchSize < 1 || embeddingTextCharacters < 1
                || maxEmbeddingAttempts < 1) {
            throw new IllegalArgumentException("embedding orchestration limits must be positive");
        }
        this.embeddingDimensions = embeddingDimensions;
        this.embeddingBatchSize = embeddingBatchSize;
        this.embeddingTextCharacters = embeddingTextCharacters;
        this.maxEmbeddingAttempts = maxEmbeddingAttempts;
        this.now = now;
        this.ids = ids;
        this.budget = budget;
    }

    public IndexOutcome index(OwnerContext owner, UUID jobId) {
        VideoIndexStore.IndexPublication publication = publication(owner, jobId);
        VideoIndexStore.PublishResult chunksPublished = indexes.publishChunks(owner.ownerId(), publication);
        List<com.videopilot.retrieval.domain.VideoChunk> storedChunks =
                indexes.findChunks(owner.ownerId(), publication.indexId());
        Map<UUID, List<Float>> stored = new LinkedHashMap<>(
                indexes.findEmbeddings(owner.ownerId(), publication.indexId(), embeddingVersion));
        for (int offset = 0; offset < storedChunks.size(); offset += embeddingBatchSize) {
            List<com.videopilot.retrieval.domain.VideoChunk> batch = storedChunks.subList(
                    offset, Math.min(offset + embeddingBatchSize, storedChunks.size())).stream()
                    .filter(chunk -> !stored.containsKey(chunk.chunkId())).toList();
            if (batch.isEmpty()) continue;
            IndexOutcome outcome = embedBatch(owner, publication, batch, stored);
            if (outcome != null) return outcome;
        }
        if (stored.size() != storedChunks.size()) {
            return completeDegraded(owner, publication, "SEMANTIC_DEGRADED", "EMBEDDING_INCOMPLETE", null);
        }
        try {
            vectors.publish(new VectorIndexPort.Publication(
                    vectorCollection, owner.ownerId(), embeddingDimensions,
                    storedChunks.stream().map(chunk -> new VectorIndexPort.VectorPoint(
                            chunk.chunkId(), publication.indexId(), chunk.mediaId(), chunk.contextId(),
                            chunk.startMs(), chunk.endMs(), stored.get(chunk.chunkId()))).toList()));
        } catch (VectorIndexException exception) {
            return completeDegraded(owner, publication, "VECTOR_STORE_DEGRADED", exception.getMessage(), vectorCollection);
        }
        complete(owner, publication, "READY", null, vectorCollection);
        return chunksPublished == VideoIndexStore.PublishResult.CREATED ? IndexOutcome.CREATED : IndexOutcome.REUSED;
    }

    private IndexOutcome embedBatch(
            OwnerContext owner,
            VideoIndexStore.IndexPublication publication,
            List<com.videopilot.retrieval.domain.VideoChunk> batch,
            Map<UUID, List<Float>> stored) {
        String fingerprint = batchFingerprint(publication.indexId(), batch);
        var prior = indexes.findEmbeddingCall(publication.sourceJobId(), fingerprint).orElse(null);
        if (prior != null && "STARTED".equals(prior.status())) {
            indexes.markEmbeddingCallUnknown(prior.modelCallId(), now.get());
            return IndexOutcome.REVIEW_REQUIRED;
        }
        if (prior != null && "UNKNOWN_OUTCOME".equals(prior.status())) {
            return IndexOutcome.REVIEW_REQUIRED;
        }
        if (prior != null && "SUCCEEDED".equals(prior.status())) {
            return completeDegraded(owner, publication, "SEMANTIC_DEGRADED", "EMBEDDING_FACT_MISSING", null);
        }
        if (prior != null && "NON_RETRYABLE".equals(prior.errorCategory())) {
            return completeDegraded(owner, publication, "SEMANTIC_DEGRADED", "EMBEDDING_NON_RETRYABLE", null);
        }
        int attempt = prior == null ? 1 : prior.attempt() + 1;
        if (attempt > maxEmbeddingAttempts) {
            return completeDegraded(owner, publication, "SEMANTIC_DEGRADED", "EMBEDDING_RETRIES_EXHAUSTED", null);
        }
        if (budget != null) budget.beforeCall(publication.sourceJobId(), "EMBEDDING", 0);
        UUID callId = ids.get();
        Instant startedAt = now.get();
        indexes.startEmbeddingCall(new VideoIndexStore.EmbeddingCallStart(
                callId, publication.sourceJobId(), ids.get().toString(), embeddingProvider,
                embeddingModel, embeddingVersion, fingerprint, attempt, startedAt));
        try {
            EmbeddingPort.Result result = embeddings.embed(new EmbeddingPort.Request(
                    batch.stream().map(this::embeddingText).toList(), fingerprint));
            if (result.vectors().size() != batch.size()) throw invalidEmbedding();
            List<VideoIndexStore.EmbeddedChunk> completed = new ArrayList<>();
            for (int index = 0; index < batch.size(); index++) {
                List<Float> vector = result.vectors().get(index);
                if (vector.size() != embeddingDimensions) throw invalidEmbedding();
                var chunk = batch.get(index);
                completed.add(new VideoIndexStore.EmbeddedChunk(
                        chunk.chunkId(), vector, chunk.inputFingerprint(), vectorDigest(vector)));
                stored.put(chunk.chunkId(), vector);
            }
            Instant completedAt = now.get();
            indexes.completeEmbedding(owner.ownerId(), new VideoIndexStore.EmbeddingCompletion(
                    callId, publication.indexId(), embeddingVersion, completed, result.inputTokens(),
                    latency(startedAt, completedAt), completedAt));
            if (budget != null) budget.afterCall(publication.sourceJobId());
            return null;
        } catch (EmbeddingException exception) {
            Instant completedAt = now.get();
            String status = exception.reason() == EmbeddingException.Reason.UNKNOWN_OUTCOME
                    ? "UNKNOWN_OUTCOME" : "FAILED";
            indexes.failEmbedding(new VideoIndexStore.EmbeddingFailure(
                    callId, status, exception.reason().name(), exception.code(),
                    latency(startedAt, completedAt), completedAt));
            if (exception.reason() == EmbeddingException.Reason.UNKNOWN_OUTCOME) {
                return IndexOutcome.REVIEW_REQUIRED;
            }
            if (exception.reason() == EmbeddingException.Reason.RETRYABLE && attempt < maxEmbeddingAttempts) {
                return IndexOutcome.RETRY_PENDING;
            }
            return completeDegraded(owner, publication, "SEMANTIC_DEGRADED", exception.code(), null);
        }
    }

    private IndexOutcome completeDegraded(
            OwnerContext owner,
            VideoIndexStore.IndexPublication publication,
            String status,
            String reason,
            String collection) {
        complete(owner, publication, status, reason, collection);
        return "VECTOR_STORE_DEGRADED".equals(status)
                ? IndexOutcome.VECTOR_STORE_DEGRADED : IndexOutcome.SEMANTIC_DEGRADED;
    }

    private void complete(
            OwnerContext owner,
            VideoIndexStore.IndexPublication publication,
            String status,
            String reason,
            String collection) {
        indexes.completeIndex(owner.ownerId(), new VideoIndexStore.IndexCompletion(
                publication.indexId(), publication.sourceJobId(), publication.inputFingerprint(),
                publication.sourceDigest(), publication.schemaVersion(), embeddingVersion, status,
                collection, reason, publication.chunks().size(), now.get()));
    }

    public String inputFingerprint(OwnerContext owner, UUID jobId) {
        return publication(owner, jobId).inputFingerprint();
    }

    private VideoIndexStore.IndexPublication publication(OwnerContext owner, UUID jobId) {
        VideoContext context = contexts.findContextByJob(owner.ownerId(), jobId)
                .filter(value -> value.status() == VideoContextStatus.READY)
                .orElseThrow(() -> new IllegalStateException("READY video context is required"));
        VideoChunkBuilder.BuildResult result = chunks.build(context, loadSegments(owner, context));
        UUID indexId = UUID.nameUUIDFromBytes((owner.ownerId().value() + "|" + context.contextId() + "|"
                + result.inputFingerprint()).getBytes(StandardCharsets.UTF_8));
        return new VideoIndexStore.IndexPublication(
                indexId, jobId, context.contextId(), context.mediaId(), result.inputFingerprint(),
                result.sourceDigest(), VideoChunkBuilder.CHUNK_VERSION, VideoChunkBuilder.INDEX_SCHEMA_VERSION,
                result.chunks(), now.get());
    }

    private List<VideoSegment> loadSegments(OwnerContext owner, VideoContext context) {
        List<VideoSegment> result = new ArrayList<>();
        long cursor = -1;
        while (true) {
            List<VideoSegment> page = contexts.findSegments(
                    owner.ownerId(), context.contextId(), cursor, 0, context.durationMs(), PAGE_SIZE);
            if (page.isEmpty()) break;
            result.addAll(page);
            cursor = page.getLast().ordinal();
            if (page.size() < PAGE_SIZE) break;
        }
        return List.copyOf(result);
    }

    private String embeddingText(com.videopilot.retrieval.domain.VideoChunk chunk) {
        String text = (chunk.summary() + "\n" + chunk.asrText() + "\n" + chunk.ocrText()).strip();
        return text.length() <= embeddingTextCharacters ? text : text.substring(0, embeddingTextCharacters);
    }

    private String batchFingerprint(
            UUID indexId, List<com.videopilot.retrieval.domain.VideoChunk> batch) {
        return sha256(indexId + "|" + embeddingVersion + "|" + batch.stream()
                .map(chunk -> chunk.chunkId() + ":" + chunk.inputFingerprint())
                .reduce((left, right) -> left + "|" + right).orElseThrow());
    }

    private String vectorDigest(List<Float> vector) {
        ByteBuffer bytes = ByteBuffer.allocate(vector.size() * Float.BYTES);
        vector.forEach(bytes::putFloat);
        return sha256(bytes.array());
    }

    private String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long latency(Instant startedAt, Instant completedAt) {
        return Math.max(0, Duration.between(startedAt, completedAt).toMillis());
    }

    private EmbeddingException invalidEmbedding() {
        return new EmbeddingException(EmbeddingException.Reason.NON_RETRYABLE, "INVALID_RESPONSE");
    }

    public enum IndexOutcome {
        CREATED,
        REUSED,
        VECTOR_STORE_DEGRADED,
        SEMANTIC_DEGRADED,
        RETRY_PENDING,
        REVIEW_REQUIRED
    }
}
