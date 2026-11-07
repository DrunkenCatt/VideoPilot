package com.videopilot.answer.port;

import com.videopilot.answer.application.AnswerEvidenceGate;
import com.videopilot.identity.domain.OwnerId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface AnswerStore {

    ClaimResult claim(OwnerId ownerId, ClaimRequest request);

    boolean ownsJob(OwnerId ownerId, UUID jobId);

    List<AnswerEvidenceGate.EvidenceSource> loadEvidence(
            OwnerId ownerId, UUID jobId, Set<UUID> chunkIds, Set<UUID> selectableSegmentIds);

    void startModelCall(ModelCallStart call);

    StoredAnswer publish(OwnerId ownerId, Publication publication);

    void fail(UUID answerId, UUID modelCallId, String callStatus, String category, String code, Instant completedAt);

    Optional<StoredAnswer> find(OwnerId ownerId, UUID jobId, UUID answerId);

    List<StoredAnswer> list(OwnerId ownerId, UUID jobId, Long cursor, int pageSize);

    record ClaimRequest(
            UUID answerId, UUID jobId, String idempotencyKey, String inputFingerprint,
            String question, String promptVersion, String modelVersion, Instant createdAt) { }

    record ClaimResult(boolean created, boolean fingerprintConflict, StoredAnswer answer) { }

    record ModelCallStart(
            UUID modelCallId, UUID answerId, UUID jobId, String requestId, String provider,
            String modelVersion, String promptVersion, String inputFingerprint,
            UUID evaluationRunId, String evaluationCaseId, Instant startedAt) {

        public ModelCallStart(
                UUID modelCallId, UUID answerId, UUID jobId, String requestId, String provider,
                String modelVersion, String promptVersion, String inputFingerprint, Instant startedAt) {
            this(modelCallId, answerId, jobId, requestId, provider, modelVersion, promptVersion,
                    inputFingerprint, null, null, startedAt);
        }
    }

    record Publication(
            UUID answerId,
            UUID modelCallId,
            String status,
            String refusalReason,
            List<String> degradedChannels,
            String embeddingVersion,
            String retrievalFormulaVersion,
            String chunkVersion,
            long inputTokens,
            long outputTokens,
            long latencyMs,
            List<AnswerEvidenceGate.VerifiedClaim> claims,
            Instant publishedAt) {
        public Publication {
            degradedChannels = List.copyOf(degradedChannels);
            claims = List.copyOf(claims);
        }
    }

    record StoredCitation(
            UUID citationId, UUID segmentId, long startMs, long endMs,
            String channel, String quote, String alignmentType) { }

    record StoredClaim(UUID claimId, String text, List<StoredCitation> citations) {
        public StoredClaim { citations = List.copyOf(citations); }
    }

    record StoredAnswer(
            long sequence,
            UUID answerId,
            String status,
            String question,
            String refusalReason,
            List<String> degradedChannels,
            String promptVersion,
            String modelVersion,
            String embeddingVersion,
            String retrievalFormulaVersion,
            String chunkVersion,
            List<StoredClaim> claims,
            Instant createdAt) {
        public StoredAnswer {
            degradedChannels = List.copyOf(degradedChannels);
            claims = List.copyOf(claims);
        }
    }
}
