package com.videopilot.infrastructure.web.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class M5ApiContracts {

    public static final int MAX_REPETITIONS = 3;

    private M5ApiContracts() {
    }

    public static final class Paths {

        public static final String DATASETS = "/api/v1/evaluation-datasets";
        public static final String RUNS = "/api/v1/evaluation-runs";
        public static final String RUN_COMPARISON = RUNS + "/comparison";

        private Paths() {
        }

        public static String dataset(UUID datasetId) {
            return DATASETS + "/" + Objects.requireNonNull(datasetId, "datasetId");
        }

        public static String datasetRuns(UUID datasetId) {
            return dataset(datasetId) + "/runs";
        }

        public static String run(UUID runId) {
            return RUNS + "/" + Objects.requireNonNull(runId, "runId");
        }

        public static String runResults(UUID runId) {
            return run(runId) + "/results";
        }

        public static String runReport(UUID runId) {
            return run(runId) + "/report";
        }
    }

    public enum DatasetSplit {
        DEV,
        TEST
    }

    public enum DatasetStatus {
        DRAFT,
        FROZEN
    }

    public enum BaselineType {
        BASELINE_V1
    }

    public enum RunStatus {
        REQUESTED,
        RUNNING,
        COMPLETED,
        FAILED
    }

    public enum CaseType {
        RETRIEVAL,
        ANSWER,
        RELIABILITY
    }

    public enum CaseOutcome {
        COMPLETED,
        REFUSED,
        ERROR,
        UNKNOWN
    }

    public record ExecutionVersions(
            @NotBlank String codeCommit,
            @NotBlank String pipelineVersion,
            @NotBlank String promptVersion,
            @NotBlank String modelVersion,
            @NotBlank String embeddingVersion,
            @NotBlank String chunkVersion,
            @NotBlank String retrievalFormulaVersion,
            @NotBlank String evidenceGateVersion,
            @NotBlank String limitsVersion,
            @NotBlank String configurationVersion) {

        public List<String> values() {
            return List.of(codeCommit, pipelineVersion, promptVersion, modelVersion, embeddingVersion,
                    chunkVersion, retrievalFormulaVersion, evidenceGateVersion, limitsVersion,
                    configurationVersion);
        }
    }

    public record CreateRunRequest(
            @NotNull BaselineType baselineType,
            @Min(1) @Max(MAX_REPETITIONS) int repetitions,
            @NotNull @Valid ExecutionVersions versions) {
    }

    public record DatasetSummary(
            UUID datasetId,
            String dataVersion,
            String datasetFingerprint,
            DatasetSplit split,
            DatasetStatus status,
            int retrievalCaseCount,
            int answerCaseCount,
            int videoCount,
            int approvedCaseCount,
            int totalCaseCount,
            Instant frozenAt) {
    }

    public record RunSummary(
            UUID runId,
            UUID datasetId,
            BaselineType baselineType,
            RunStatus status,
            String executionFingerprint,
            int repetitionIndex,
            int repetitionCount,
            int completedCaseCount,
            int totalCaseCount,
            Instant createdAt,
            Instant completedAt) {
    }

    public record CaseResult(
            String caseId,
            CaseType caseType,
            CaseOutcome outcome,
            Integer rank,
            int claimCount,
            int citationCount,
            int supportedClaimCount,
            int localizedCitationCount,
            long latencyMs,
            long tokenCount,
            BigDecimal estimatedCost,
            String errorCode) {

        public CaseResult(
                String caseId, CaseType caseType, CaseOutcome outcome, Integer rank,
                int claimCount, int citationCount, long latencyMs, long tokenCount,
                BigDecimal estimatedCost, String errorCode) {
            this(caseId, caseType, outcome, rank, claimCount, citationCount, 0, 0,
                    latencyMs, tokenCount, estimatedCost, errorCode);
        }
    }

    public record MetricValue(
            String name, double numerator, long denominator, Double value,
            Map<String, Long> exclusions) {
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
    }

    public record SeriesStatistics(
            int repetitions,
            double mean,
            Double sampleStandardDeviation,
            Map<String, Long> outcomeDistribution) {
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
    }

    public record RunComparison(
            UUID baseRunId,
            UUID candidateRunId,
            List<MetricValue> baseMetrics,
            List<MetricValue> candidateMetrics) {
    }
}
