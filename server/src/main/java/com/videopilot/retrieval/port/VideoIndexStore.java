package com.videopilot.retrieval.port;

import com.videopilot.identity.domain.OwnerId;
import com.videopilot.retrieval.domain.VideoChunk;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface VideoIndexStore {

    PublishResult publishChunks(OwnerId ownerId, IndexPublication publication);

    List<VideoChunk> findChunks(OwnerId ownerId, UUID indexId);

    Map<UUID, List<Float>> findEmbeddings(OwnerId ownerId, UUID indexId, String embeddingVersion);

    Optional<RetrievalSnapshot> findRetrievalSnapshot(OwnerId ownerId, UUID jobId, int candidateLimit);

    Optional<ModelCallState> findEmbeddingCall(UUID jobId, String inputFingerprint);

    void startEmbeddingCall(EmbeddingCallStart call);

    void markEmbeddingCallUnknown(String modelCallId, Instant completedAt);

    void completeEmbedding(OwnerId ownerId, EmbeddingCompletion completion);

    void failEmbedding(EmbeddingFailure failure);

    int nextRetrievalEmbeddingAttempt(
            UUID jobId, String operationType, String embeddingVersion, String inputFingerprint);

    void startRetrievalEmbeddingCall(RetrievalEmbeddingCallStart call);

    void completeRetrievalEmbeddingCall(RetrievalEmbeddingCompletion completion);

    void completeIndex(OwnerId ownerId, IndexCompletion completion);

    record IndexPublication(
            UUID indexId,
            UUID sourceJobId,
            UUID contextId,
            UUID mediaId,
            String inputFingerprint,
            String sourceDigest,
            String chunkVersion,
            String schemaVersion,
            List<VideoChunk> chunks,
            Instant createdAt) {
        public IndexPublication {
            chunks = List.copyOf(chunks);
            if (chunks.isEmpty()) throw new IllegalArgumentException("index requires chunks");
            if (inputFingerprint == null || !inputFingerprint.matches("[0-9a-f]{64}")
                    || sourceDigest == null || !sourceDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("index fingerprints must be lowercase SHA-256");
            }
            for (int ordinal = 0; ordinal < chunks.size(); ordinal++) {
                VideoChunk chunk = chunks.get(ordinal);
                if (!chunk.contextId().equals(contextId) || !chunk.mediaId().equals(mediaId)
                        || chunk.ordinal() != ordinal) {
                    throw new IllegalArgumentException("index chunks must belong to one ordered aggregate");
                }
            }
        }
    }

    enum PublishResult {
        CREATED,
        REUSED
    }

    record ModelCallState(String modelCallId, String status, String errorCategory, int attempt) { }

    record EmbeddingCallStart(
            UUID modelCallId,
            UUID jobId,
            String requestId,
            String provider,
            String model,
            String embeddingVersion,
            String inputFingerprint,
            int attempt,
            Instant startedAt) { }

    record EmbeddedChunk(UUID chunkId, List<Float> vector, String inputFingerprint, String vectorDigest) {
        public EmbeddedChunk {
            vector = List.copyOf(vector);
            if (vector.isEmpty() || inputFingerprint == null || !inputFingerprint.matches("[0-9a-f]{64}")
                    || vectorDigest == null || !vectorDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("embedded chunk is invalid");
            }
        }
    }

    record EmbeddingCompletion(
            UUID modelCallId,
            UUID indexId,
            String embeddingVersion,
            List<EmbeddedChunk> chunks,
            long inputTokens,
            long latencyMs,
            Instant completedAt) {
        public EmbeddingCompletion {
            chunks = List.copyOf(chunks);
            if (chunks.isEmpty() || inputTokens < 0 || latencyMs < 0) {
                throw new IllegalArgumentException("embedding completion is invalid");
            }
        }
    }

    record EmbeddingFailure(
            UUID modelCallId,
            String status,
            String errorCategory,
            String errorCode,
            long latencyMs,
            Instant completedAt) { }

    record RetrievalEmbeddingCallStart(
            UUID modelCallId,
            UUID jobId,
            String requestId,
            String operationType,
            String provider,
            String model,
            String embeddingVersion,
            String inputFingerprint,
            int attempt,
            Instant startedAt) { }

    record RetrievalEmbeddingCompletion(
            UUID modelCallId,
            long inputTokens,
            long latencyMs,
            Instant completedAt) { }

    record IndexCompletion(
            UUID indexId,
            UUID jobId,
            String stageInputFingerprint,
            String sourceDigest,
            String schemaVersion,
            String embeddingVersion,
            String status,
            String vectorCollection,
            String degradedReason,
            int chunkCount,
            Instant completedAt) { }

    record RetrievalSnapshot(
            UUID indexId,
            UUID jobId,
            UUID contextId,
            UUID mediaId,
            String status,
            String ocrState,
            String embeddingVersion,
            String vectorCollection,
            String chunkVersion,
            List<VideoChunk> chunks,
            Map<UUID, List<Float>> embeddings) {
        public RetrievalSnapshot {
            chunks = List.copyOf(chunks);
            embeddings = Map.copyOf(embeddings);
            if (candidateLimitExceeded(chunks)) {
                throw new IllegalArgumentException("retrieval snapshot exceeds its bounded candidate set");
            }
        }

        private static boolean candidateLimitExceeded(List<VideoChunk> chunks) {
            return chunks.size() > 200;
        }
    }
}
