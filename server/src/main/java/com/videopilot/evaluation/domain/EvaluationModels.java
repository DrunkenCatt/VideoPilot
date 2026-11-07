package com.videopilot.evaluation.domain;

import com.videopilot.identity.domain.OwnerId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EvaluationModels {

    private EvaluationModels() {
    }

    public enum DatasetSplit { DEV, TEST }

    public enum DatasetStatus { DRAFT, FROZEN }

    public enum CaseType { RETRIEVAL, ANSWER, RELIABILITY }

    public enum RunStatus { REQUESTED, RUNNING, COMPLETED, FAILED }

    public enum CaseOutcome { COMPLETED, REFUSED, ERROR, UNKNOWN }

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
            String reviewStatus,
            int reviewerCount,
            String fingerprint) {

        public EvaluationCase {
            expectedSegmentIds = List.copyOf(expectedSegmentIds);
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
            long latencyMs,
            long tokenCount,
            BigDecimal estimatedCost,
            String errorCode,
            Instant completedAt) {
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
