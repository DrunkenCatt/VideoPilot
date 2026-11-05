package com.videopilot.retrieval.port;

import com.videopilot.identity.domain.OwnerId;
import com.videopilot.retrieval.domain.VideoChunk;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface VideoIndexStore {

    PublishResult publish(OwnerId ownerId, IndexPublication publication);

    List<VideoChunk> findChunks(OwnerId ownerId, UUID indexId);

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
}
