package com.videopilot.infrastructure.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.videopilot.infrastructure.web.contract.M1ApiContracts;
import com.videopilot.infrastructure.web.contract.M5ApiContracts;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M5ContractTest {

    private static final UUID DATASET_ID = UUID.fromString("00000000-0000-0000-0000-000000000051");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000052");

    @Test
    void freezesDatasetRunTraceComparisonAndReportPaths() {
        assertThat(M5ApiContracts.Paths.DATASETS).isEqualTo("/api/v1/evaluation-datasets");
        assertThat(M5ApiContracts.Paths.dataset(DATASET_ID))
                .isEqualTo("/api/v1/evaluation-datasets/00000000-0000-0000-0000-000000000051");
        assertThat(M5ApiContracts.Paths.datasetRuns(DATASET_ID))
                .isEqualTo("/api/v1/evaluation-datasets/00000000-0000-0000-0000-000000000051/runs");
        assertThat(M5ApiContracts.Paths.runResults(RUN_ID))
                .isEqualTo("/api/v1/evaluation-runs/00000000-0000-0000-0000-000000000052/results");
        assertThat(M5ApiContracts.Paths.runReport(RUN_ID))
                .isEqualTo("/api/v1/evaluation-runs/00000000-0000-0000-0000-000000000052/report");
        assertThat(M5ApiContracts.Paths.RUN_COMPARISON).isEqualTo("/api/v1/evaluation-runs/comparison");
    }

    @Test
    void requiresAnIdempotentFrozenBaselineRunWithBoundedRepetitions() {
        M5ApiContracts.CreateRunRequest request = new M5ApiContracts.CreateRunRequest(
                M5ApiContracts.BaselineType.BASELINE_V1,
                3,
                versions());

        assertThat(request.baselineType()).isEqualTo(M5ApiContracts.BaselineType.BASELINE_V1);
        assertThat(request.repetitions()).isEqualTo(3);
        assertThat(M1ApiContracts.IDEMPOTENCY_KEY_HEADER).isEqualTo("Idempotency-Key");
        assertThat(M5ApiContracts.MAX_REPETITIONS).isEqualTo(3);
    }

    @Test
    void keepsDatasetReviewAndTestFreezeStateExplicit() {
        M5ApiContracts.DatasetSummary dataset = new M5ApiContracts.DatasetSummary(
                DATASET_ID,
                "release-1.0.0",
                "a".repeat(64),
                M5ApiContracts.DatasetSplit.TEST,
                M5ApiContracts.DatasetStatus.FROZEN,
                120,
                30,
                5,
                150,
                150,
                Instant.parse("2026-11-08T10:00:00Z"));

        assertThat(dataset.split()).isEqualTo(M5ApiContracts.DatasetSplit.TEST);
        assertThat(dataset.status()).isEqualTo(M5ApiContracts.DatasetStatus.FROZEN);
        assertThat(dataset.approvedCaseCount()).isEqualTo(dataset.totalCaseCount());
    }

    @Test
    void exposesCompleteExecutionFingerprintWithoutSensitiveCaseContent() {
        M5ApiContracts.CaseResult result = new M5ApiContracts.CaseResult(
                "release-r-001",
                M5ApiContracts.CaseType.RETRIEVAL,
                M5ApiContracts.CaseOutcome.COMPLETED,
                1,
                2,
                2,
                350L,
                120L,
                null,
                null);

        assertThat(versions().values()).doesNotContainNull().allMatch(value -> !value.isBlank());
        assertThat(result.caseId()).isEqualTo("release-r-001");
        assertThat(result).hasNoNullFieldsOrPropertiesExcept("estimatedCost", "errorCode");
    }

    private M5ApiContracts.ExecutionVersions versions() {
        return new M5ApiContracts.ExecutionVersions(
                "c9b907e",
                "m3-evidence-qa-v1",
                "answer-evidence-v1",
                "deepseek-ai/DeepSeek-V4-Flash",
                "siliconflow-bge-m3-1024-v1",
                "chunk-5m-v1",
                "hybrid-0.60-0.25-0.15-v1",
                "citation-gate-v1",
                "m4-limits-v1",
                "m5-baseline-v1");
    }
}
