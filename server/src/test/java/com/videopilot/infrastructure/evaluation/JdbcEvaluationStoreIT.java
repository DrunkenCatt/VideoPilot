package com.videopilot.infrastructure.evaluation;

import static com.videopilot.evaluation.domain.EvaluationModels.CaseOutcome.COMPLETED;
import static com.videopilot.evaluation.domain.EvaluationModels.CaseType.RETRIEVAL;
import static com.videopilot.evaluation.domain.EvaluationModels.DatasetSplit.DEV;
import static com.videopilot.evaluation.domain.EvaluationModels.DatasetStatus.FROZEN;
import static com.videopilot.evaluation.domain.EvaluationModels.RunStatus.REQUESTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.evaluation.application.EvaluationException;
import com.videopilot.evaluation.domain.EvaluationModels.CaseResult;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationCase;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationDataset;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationRun;
import com.videopilot.evaluation.domain.EvaluationModels.ExecutionVersions;
import com.videopilot.identity.domain.OwnerId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("integration")
@TestPropertySource(properties = "videopilot.evaluation.worker-enabled=false")
class JdbcEvaluationStoreIT {

    private final OwnerId ownerId = new OwnerId(UUID.randomUUID());
    private final UUID mediaId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID datasetId = UUID.randomUUID();

    @Autowired
    private JdbcEvaluationStore store;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO media_asset (
                    media_id, owner_id, media_fingerprint, object_key, original_filename, media_status
                ) VALUES (:mediaId,:owner,REPEAT('a',64),:objectKey,'evaluation.mp4','UPLOADED')
                """).param("mediaId", mediaId.toString()).param("owner", ownerId.value().toString())
                .param("objectKey", "evaluation/" + mediaId + ".mp4").update();
        jdbc.sql("""
                INSERT INTO analysis_job (
                    job_id, owner_id, media_id, analysis_key, idempotency_key, request_fingerprint,
                    goal, mode, pipeline_version, status
                ) VALUES (:jobId,:owner,:mediaId,:analysisKey,:key,REPEAT('b',64),
                    'Evaluate lesson','STANDARD','pipeline-v1','READY')
                """).param("jobId", jobId.toString()).param("owner", ownerId.value().toString())
                .param("mediaId", mediaId.toString()).param("analysisKey", "evaluation-" + jobId)
                .param("key", "evaluation-" + jobId).update();
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM evaluation_case_result WHERE dataset_id = :datasetId")
                .param("datasetId", datasetId.toString()).update();
        jdbc.sql("DELETE FROM evaluation_run WHERE dataset_id = :datasetId")
                .param("datasetId", datasetId.toString()).update();
        jdbc.sql("DELETE FROM evaluation_case WHERE dataset_id = :datasetId")
                .param("datasetId", datasetId.toString()).update();
        jdbc.sql("DELETE FROM evaluation_dataset WHERE dataset_id = :datasetId")
                .param("datasetId", datasetId.toString()).update();
        jdbc.sql("DELETE FROM analysis_job WHERE job_id = :jobId")
                .param("jobId", jobId.toString()).update();
        jdbc.sql("DELETE FROM media_asset WHERE media_id = :mediaId")
                .param("mediaId", mediaId.toString()).update();
    }

    @Test
    void persistsIdempotentRunsAndResumesOnlyMissingCasesAfterLeaseExpiry() {
        Instant now = Instant.parse("2026-11-08T02:00:00Z");
        EvaluationDataset dataset = dataset(now);
        assertThat(store.saveDataset(ownerId, dataset)).isEqualTo(dataset);
        assertThat(store.saveDataset(ownerId, dataset).datasetId()).isEqualTo(datasetId);
        assertThat(store.findDataset(new OwnerId(UUID.randomUUID()), datasetId)).isEmpty();

        UUID seriesId = UUID.randomUUID();
        String requestFingerprint = "c".repeat(64);
        List<EvaluationRun> requested = List.of(run(seriesId, 1, now), run(seriesId, 2, now));
        List<EvaluationRun> created = store.createRuns(
                ownerId, dataset, "intent-1", requestFingerprint, seriesId, requested);
        List<EvaluationRun> duplicate = store.createRuns(
                ownerId, dataset, "intent-1", requestFingerprint, UUID.randomUUID(), requested);
        assertThat(duplicate).extracting(EvaluationRun::runId)
                .containsExactlyElementsOf(created.stream().map(EvaluationRun::runId).toList());
        assertThatThrownBy(() -> store.createRuns(
                ownerId, dataset, "intent-1", "d".repeat(64), UUID.randomUUID(), requested))
                .isInstanceOf(EvaluationException.class);

        var firstClaim = store.claimNext("lease-000000000000000000000000000001",
                now.plusSeconds(60), now).orElseThrow();
        EvaluationCase firstCase = firstClaim.cases().getFirst();
        store.saveResult("lease-000000000000000000000000000001",
                result(firstClaim.run().runId(), firstCase.caseId(), now));
        jdbc.sql("UPDATE evaluation_run SET lease_until = :expired WHERE run_id = :runId")
                .param("expired", now.minusSeconds(1)).param("runId", firstClaim.run().runId().toString())
                .update();

        var resumed = store.claimNext("lease-000000000000000000000000000002",
                now.plusSeconds(120), now).orElseThrow();
        assertThat(resumed.run().runId()).isEqualTo(firstClaim.run().runId());
        assertThat(resumed.completedResults()).extracting(CaseResult::caseId)
                .containsExactly(firstCase.caseId());
        EvaluationCase secondCase = resumed.cases().get(1);
        store.saveResult("lease-000000000000000000000000000002",
                result(resumed.run().runId(), secondCase.caseId(), now));
        store.completeRun("lease-000000000000000000000000000002", resumed.run().runId(), now);

        EvaluationRun completed = store.findRun(ownerId, resumed.run().runId()).orElseThrow();
        assertThat(completed.status().name()).isEqualTo("COMPLETED");
        assertThat(completed.completedCaseCount()).isEqualTo(2);
        assertThat(store.findResults(ownerId, completed.runId())).hasSize(2);
    }

    private EvaluationDataset dataset(Instant now) {
        return new EvaluationDataset(
                datasetId, "m5-dev-" + datasetId, "e".repeat(64), DEV, FROZEN, 1,
                List.of(evaluationCase("case-1", 1, "f".repeat(64)),
                        evaluationCase("case-2", 2, "0".repeat(64))), now, now);
    }

    private EvaluationCase evaluationCase(String caseId, int ordinal, String fingerprint) {
        return new EvaluationCase(
                caseId, ordinal, RETRIEVAL, jobId, "question " + ordinal,
                List.of(UUID.randomUUID()), "APPROVED", 1, fingerprint);
    }

    private EvaluationRun run(UUID seriesId, int repetition, Instant now) {
        return new EvaluationRun(
                UUID.randomUUID(), seriesId, datasetId, "e".repeat(64), "intent-1", "c".repeat(64),
                "1".repeat(64), versions(), REQUESTED, repetition, 2, 0, 2, null, now, null);
    }

    private CaseResult result(UUID runId, String caseId, Instant now) {
        return new CaseResult(
                runId, caseId, RETRIEVAL, COMPLETED, 1, 0, 0, 10, 0, null, null, now);
    }

    private ExecutionVersions versions() {
        return new ExecutionVersions(
                "commit", "pipeline", "prompt", "model", "embedding", "chunk",
                "formula", "gate", "limits", "config");
    }
}
