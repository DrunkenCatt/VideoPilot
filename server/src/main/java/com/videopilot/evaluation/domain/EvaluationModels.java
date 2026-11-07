package com.videopilot.evaluation.domain;

import com.videopilot.identity.domain.OwnerId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EvaluationModels {

    private EvaluationModels() {
    }

    public enum DatasetSplit { DEV, TEST }

    public enum DatasetStatus { DRAFT, FROZEN }

    public enum CaseType { RETRIEVAL, ANSWER, RELIABILITY }

    public enum RunStatus { REQUESTED, RUNNING, COMPLETED, FAILED }

    public enum CaseOutcome { COMPLETED, REFUSED, ERROR, UNKNOWN }

    public enum Answerability { ANSWERABLE, UNANSWERABLE, NOT_APPLICABLE }

    public enum ReliabilityFact {
        LOST_TASK_RECOVERED,
        ZERO_DUPLICATE_HIGH_COST_CALL,
        ILLEGAL_TRANSITION_REJECTED,
        RECOVERY_SUCCEEDED
    }

    public record ReliabilityObservation(ReliabilityFact fact, boolean passed) {
    }

    public record AnswerScoring(
            List<String> requiredFacts,
            List<String> forbiddenClaims,
            List<String> allowedChannels) {

        public static final AnswerScoring EMPTY = new AnswerScoring(List.of(), List.of(), List.of());

        public AnswerScoring {
            requiredFacts = requiredFacts == null ? List.of() : List.copyOf(requiredFacts);
            forbiddenClaims = forbiddenClaims == null ? List.of() : List.copyOf(forbiddenClaims);
            allowedChannels = allowedChannels == null ? List.of() : List.copyOf(allowedChannels);
        }
    }

    public record ExecutionVersions(
            String codeCommit,
            String pipelineVersion,
            String promptVersion,
            String modelVersion,
            String embeddingVersion,
            String chunkVersion,
            String retrievalFormulaVersion,
            String evidenceGateVersion,
            String limitsVersion,
            String configurationVersion) {

        public List<String> values() {
            return List.of(codeCommit, pipelineVersion, promptVersion, modelVersion, embeddingVersion,
                    chunkVersion, retrievalFormulaVersion, evidenceGateVersion, limitsVersion,
                    configurationVersion);
        }
    }

    public record EvaluationCase(
            String caseId,
            int ordinal,
            CaseType type,
            UUID jobId,
            String question,
            List<UUID> expectedSegmentIds,
            Answerability answerability,
            AnswerScoring answerScoring,
            ReliabilityObservation reliability,
            String reviewStatus,
            int reviewerCount,
            String fingerprint) {

        public EvaluationCase {
            expectedSegmentIds = List.copyOf(expectedSegmentIds);
            answerScoring = answerScoring == null ? AnswerScoring.EMPTY : answerScoring;
        }

        public EvaluationCase(
                String caseId, int ordinal, CaseType type, UUID jobId, String question,
                List<UUID> expectedSegmentIds, Answerability answerability,
                String reviewStatus, int reviewerCount, String fingerprint) {
            this(caseId, ordinal, type, jobId, question, expectedSegmentIds, answerability,
                    AnswerScoring.EMPTY, null, reviewStatus, reviewerCount, fingerprint);
        }

        public EvaluationCase(
                String caseId, int ordinal, CaseType type, UUID jobId, String question,
                List<UUID> expectedSegmentIds, String reviewStatus, int reviewerCount,
                String fingerprint) {
            this(caseId, ordinal, type, jobId, question, expectedSegmentIds,
                    type == CaseType.ANSWER ? Answerability.ANSWERABLE : Answerability.NOT_APPLICABLE,
                    AnswerScoring.EMPTY, null, reviewStatus, reviewerCount, fingerprint);
        }
    }

    public record EvaluationDataset(
            UUID datasetId,
            String dataVersion,
            String fingerprint,
            DatasetSplit split,
            DatasetStatus status,
            int videoCount,
            List<EvaluationCase> cases,
            Instant frozenAt,
            Instant createdAt) {

        public EvaluationDataset {
            cases = List.copyOf(cases);
        }
    }

    public record EvaluationRun(
            UUID runId,
            UUID seriesId,
            UUID datasetId,
            String datasetFingerprint,
            String idempotencyKey,
            String requestFingerprint,
            String executionFingerprint,
            ExecutionVersions versions,
            RunStatus status,
            int repetitionIndex,
            int repetitionCount,
            int completedCaseCount,
            int totalCaseCount,
            String errorCode,
            Instant createdAt,
            Instant completedAt) {
    }

    public record CaseResult(
            UUID runId,
            String caseId,
            CaseType caseType,
            CaseOutcome outcome,
            Integer rank,
            int claimCount,
            int citationCount,
            int supportedClaimCount,
            int localizedCitationCount,
            UUID answerId,
            long latencyMs,
            long tokenCount,
            BigDecimal estimatedCost,
            String errorCode,
            Instant completedAt) {

        public CaseResult(
                UUID runId, String caseId, CaseType caseType, CaseOutcome outcome, Integer rank,
                int claimCount, int citationCount, long latencyMs, long tokenCount,
                BigDecimal estimatedCost, String errorCode, Instant completedAt) {
            this(runId, caseId, caseType, outcome, rank, claimCount, citationCount, 0, 0,
                    null, latencyMs, tokenCount, estimatedCost, errorCode, completedAt);
        }
    }

    public record MetricValue(
            String name,
            double numerator,
            long denominator,
            Double value,
            Map<String, Long> exclusions) {

        public MetricValue {
            exclusions = Map.copyOf(exclusions);
        }
    }

    public record UsageSummary(
            long totalCalls,
            long inputUnits,
            long outputUnits,
            long latencyMs,
            BigDecimal estimatedCost,
            String currency,
            long unavailablePriceCalls,
            Map<String, Long> callOutcomes) {

        public UsageSummary {
            callOutcomes = Map.copyOf(callOutcomes);
        }
    }

    public record SeriesStatistics(
            int repetitions,
            double mean,
            Double sampleStandardDeviation,
            Map<String, Long> outcomeDistribution) {

        public SeriesStatistics {
            outcomeDistribution = Map.copyOf(outcomeDistribution);
        }
    }

    public record EvaluationReport(
            UUID runId,
            UUID datasetId,
            String datasetFingerprint,
            String executionFingerprint,
            ExecutionVersions versions,
            List<MetricValue> metrics,
            UsageSummary usage,
            Map<String, SeriesStatistics> series,
            Instant generatedAt) {

        public EvaluationReport {
            metrics = List.copyOf(metrics);
            series = Map.copyOf(series);
        }
    }

    public record ClaimedRun(
            OwnerId ownerId,
            EvaluationRun run,
            List<EvaluationCase> cases,
            List<CaseResult> completedResults) {

        public ClaimedRun {
            cases = List.copyOf(cases);
            completedResults = List.copyOf(completedResults);
        }
    }
}
