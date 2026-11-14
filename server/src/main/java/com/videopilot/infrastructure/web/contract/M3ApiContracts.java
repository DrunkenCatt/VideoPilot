package com.videopilot.infrastructure.web.contract;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class M3ApiContracts {

    public static final int DEFAULT_RETRIEVAL_TOP_K = 5;
    public static final int MAX_RETRIEVAL_TOP_K = 20;
    public static final int MAX_QUERY_LENGTH = 1_000;
    public static final int DEFAULT_ANSWER_PAGE_SIZE = 20;
    public static final int MAX_ANSWER_PAGE_SIZE = 100;
    public static final int MAX_CLAIMS_PER_ANSWER = 8;

    private M3ApiContracts() {
    }

    public static final class Paths {

        private Paths() {
        }

        public static String jobRetrieval(UUID jobId) {
            return job(jobId) + "/retrieval";
        }

        public static String jobAnswers(UUID jobId) {
            return job(jobId) + "/answers";
        }

        public static String jobAnswer(UUID jobId, UUID answerId) {
            return jobAnswers(jobId) + "/" + requireId(answerId, "answerId");
        }

        private static String job(UUID jobId) {
            return M1ApiContracts.Paths.ANALYSIS_JOBS + "/" + requireId(jobId, "jobId");
        }

        private static UUID requireId(UUID value, String field) {
            return Objects.requireNonNull(value, field);
        }
    }

    public enum EvidenceChannel {
        ASR,
        OCR,
        KEYFRAME
    }

    public enum DegradedChannel {
        VECTOR_STORE,
        SEMANTIC,
        OCR
    }

    public enum AnswerStatus {
        ANSWERED,
        PARTIALLY_ANSWERED,
        REFUSED
    }

    public enum RefusalReason {
        NO_SUFFICIENT_EVIDENCE,
        EVIDENCE_BELOW_THRESHOLD,
        CITATION_VALIDATION_FAILED,
        QUESTION_OUT_OF_SCOPE
    }

    public record ScoreBreakdown(
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
    }

    public record EvidenceHitResponse(
            UUID chunkId,
            List<UUID> segmentIds,
            long startMs,
            long endMs,
            String excerpt,
            List<EvidenceChannel> channels,
            ScoreBreakdown scores) {
    }

    public record RetrievalResponse(
            String query,
            String retrievalFormulaVersion,
            String embeddingVersion,
            String chunkVersion,
            List<DegradedChannel> degradedChannels,
            List<EvidenceHitResponse> hits) {
    }

    public record AnswerRequest(@NotBlank @Size(max = MAX_QUERY_LENGTH) String question) {
    }

    public record CitedEvidenceResponse(
            UUID citationId,
            UUID segmentId,
            long startMs,
            long endMs,
            EvidenceChannel channel,
            String quote) {
    }

    public record AnswerClaimResponse(
            UUID claimId,
            String text,
            List<CitedEvidenceResponse> citations) {
    }

    public record ExecutionVersions(
            String promptVersion,
            String modelVersion,
            String embeddingVersion,
            String retrievalFormulaVersion,
            String chunkVersion) {
    }

    public record AnswerResponse(
            UUID answerId,
            AnswerStatus status,
            String question,
            List<AnswerClaimResponse> claims,
            RefusalReason refusalReason,
            List<String> missingRequirements,
            List<DegradedChannel> degradedChannels,
            ExecutionVersions versions,
            Instant createdAt) {
        public AnswerResponse(
                UUID answerId, AnswerStatus status, String question,
                List<AnswerClaimResponse> claims, RefusalReason refusalReason,
                List<DegradedChannel> degradedChannels, ExecutionVersions versions,
                Instant createdAt) {
            this(answerId, status, question, claims, refusalReason, List.of(),
                    degradedChannels, versions, createdAt);
        }
    }

    public record AnswerPageResponse(
            List<AnswerResponse> data,
            Long nextCursor,
            boolean hasMore) {
    }
}
