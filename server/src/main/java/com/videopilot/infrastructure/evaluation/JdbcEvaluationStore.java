package com.videopilot.infrastructure.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.evaluation.application.EvaluationException;
import com.videopilot.evaluation.domain.EvaluationModels.CaseOutcome;
import com.videopilot.evaluation.domain.EvaluationModels.CaseResult;
import com.videopilot.evaluation.domain.EvaluationModels.CaseType;
import com.videopilot.evaluation.domain.EvaluationModels.ClaimedRun;
import com.videopilot.evaluation.domain.EvaluationModels.DatasetSplit;
import com.videopilot.evaluation.domain.EvaluationModels.DatasetStatus;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationCase;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationDataset;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationRun;
import com.videopilot.evaluation.domain.EvaluationModels.ExecutionVersions;
import com.videopilot.evaluation.domain.EvaluationModels.RunStatus;
import com.videopilot.evaluation.port.EvaluationStore;
import com.videopilot.identity.domain.OwnerId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

public class JdbcEvaluationStore implements EvaluationStore {

    private static final TypeReference<List<UUID>> UUIDS = new TypeReference<>() { };
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcEvaluationStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public EvaluationDataset saveDataset(OwnerId ownerId, EvaluationDataset dataset) {
        try {
            boolean ownsReadyJobs = dataset.cases().stream().allMatch(evaluationCase ->
                    jdbc.sql("""
                            SELECT COUNT(*) FROM analysis_job
                            WHERE job_id = :jobId AND owner_id = :owner AND status = 'READY'
                            """).param("jobId", evaluationCase.jobId().toString())
                            .param("owner", ownerId.value().toString()).query(Long.class).single() == 1);
            if (!ownsReadyJobs) throw new EvaluationException(EvaluationException.Reason.INVALID_REQUEST);
            jdbc.sql("""
                    INSERT INTO evaluation_dataset (
                        dataset_id, owner_id, data_version, dataset_fingerprint, split, status,
                        video_count, frozen_at, created_at
                    ) VALUES (:id,:owner,:version,:fingerprint,:split,:status,:videos,:frozenAt,:createdAt)
                    """).param("id", dataset.datasetId().toString())
                    .param("owner", ownerId.value().toString()).param("version", dataset.dataVersion())
                    .param("fingerprint", dataset.fingerprint()).param("split", dataset.split().name())
                    .param("status", dataset.status().name()).param("videos", dataset.videoCount())
                    .param("frozenAt", dataset.frozenAt()).param("createdAt", dataset.createdAt()).update();
            for (EvaluationCase evaluationCase : dataset.cases()) insertCase(dataset.datasetId(), evaluationCase);
            return dataset;
        } catch (DuplicateKeyException exception) {
            return findByVersion(ownerId, dataset.dataVersion(), dataset.split())
                    .filter(existing -> existing.fingerprint().equals(dataset.fingerprint()))
                    .orElseThrow(() -> new EvaluationException(
                            EvaluationException.Reason.IDEMPOTENCY_KEY_REUSED, exception));
        }
    }

    @Override
    public List<EvaluationDataset> listDatasets(OwnerId ownerId) {
        return jdbc.sql("""
                SELECT * FROM evaluation_dataset WHERE owner_id = :owner
                ORDER BY created_at DESC, dataset_id
                """).param("owner", ownerId.value().toString()).query(this::datasetRow).list().stream()
                .map(this::withCases).toList();
    }

    @Override
    public Optional<EvaluationDataset> findDataset(OwnerId ownerId, UUID datasetId) {
        return jdbc.sql("SELECT * FROM evaluation_dataset WHERE owner_id = :owner AND dataset_id = :id")
                .param("owner", ownerId.value().toString()).param("id", datasetId.toString())
                .query(this::datasetRow).optional().map(this::withCases);
    }

    @Override
    @Transactional
    public List<EvaluationRun> createRuns(
            OwnerId ownerId,
            EvaluationDataset dataset,
            String idempotencyKey,
            String requestFingerprint,
            UUID seriesId,
            List<EvaluationRun> runs) {
        List<EvaluationRun> existing = findByIntent(ownerId, idempotencyKey);
        if (!existing.isEmpty()) return matchingRequest(existing, requestFingerprint);
        try {
            for (EvaluationRun run : runs) {
                jdbc.sql("""
                        INSERT INTO evaluation_run (
                            run_id, series_id, owner_id, dataset_id, dataset_fingerprint,
                            idempotency_key, request_fingerprint, baseline_type, status,
                            execution_fingerprint, execution_versions, repetition_index, repetition_count,
                            completed_case_count, total_case_count, created_at
                        ) VALUES (:runId,:seriesId,:owner,:datasetId,:datasetFingerprint,
                            :key,:requestFingerprint,'BASELINE_V1','REQUESTED',
                            :executionFingerprint,CAST(:versions AS JSON),:repetitionIndex,:repetitionCount,
                            0,:totalCases,:createdAt)
                        """).param("runId", run.runId().toString()).param("seriesId", seriesId.toString())
                        .param("owner", ownerId.value().toString())
                        .param("datasetId", dataset.datasetId().toString())
                        .param("datasetFingerprint", dataset.fingerprint()).param("key", idempotencyKey)
                        .param("requestFingerprint", requestFingerprint)
                        .param("executionFingerprint", run.executionFingerprint())
                        .param("versions", write(run.versions())).param("repetitionIndex", run.repetitionIndex())
                        .param("repetitionCount", run.repetitionCount()).param("totalCases", run.totalCaseCount())
                        .param("createdAt", run.createdAt()).update();
            }
            return runs;
        } catch (DuplicateKeyException exception) {
            existing = findByIntent(ownerId, idempotencyKey);
            if (existing.isEmpty()) throw exception;
            return matchingRequest(existing, requestFingerprint);
        }
    }

    @Override
    public Optional<EvaluationRun> findRun(OwnerId ownerId, UUID runId) {
        return jdbc.sql("SELECT * FROM evaluation_run WHERE owner_id = :owner AND run_id = :runId")
                .param("owner", ownerId.value().toString()).param("runId", runId.toString())
                .query(this::runRow).optional();
    }

    @Override
    public List<CaseResult> findResults(OwnerId ownerId, UUID runId) {
        return jdbc.sql("""
                SELECT result.* FROM evaluation_case_result result
                INNER JOIN evaluation_run run ON run.run_id = result.run_id
                WHERE run.owner_id = :owner AND result.run_id = :runId
                ORDER BY result.case_id
                """).param("owner", ownerId.value().toString()).param("runId", runId.toString())
                .query(this::resultRow).list();
    }

    @Override
    @Transactional
    public Optional<ClaimedRun> claimNext(String leaseId, Instant leaseUntil, Instant now) {
        Optional<ClaimRow> candidate = jdbc.sql("""
                SELECT run_id, owner_id FROM evaluation_run
                WHERE status = 'REQUESTED' OR (status = 'RUNNING' AND lease_until < :now)
                ORDER BY created_at, repetition_index
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """).param("now", now).query((row, ignored) -> new ClaimRow(
                        UUID.fromString(row.getString("run_id")),
                        new OwnerId(UUID.fromString(row.getString("owner_id"))))).optional();
        if (candidate.isEmpty()) return Optional.empty();
        ClaimRow claim = candidate.orElseThrow();
        int updated = jdbc.sql("""
                UPDATE evaluation_run SET status = 'RUNNING', lease_id = :leaseId, lease_until = :leaseUntil
                WHERE run_id = :runId AND (status = 'REQUESTED' OR (status = 'RUNNING' AND lease_until < :now))
                """).param("leaseId", leaseId).param("leaseUntil", leaseUntil)
                .param("runId", claim.runId().toString()).param("now", now).update();
        if (updated != 1) return Optional.empty();
        EvaluationRun run = findRun(claim.ownerId(), claim.runId()).orElseThrow();
        EvaluationDataset dataset = findDataset(claim.ownerId(), run.datasetId()).orElseThrow();
        return Optional.of(new ClaimedRun(
                claim.ownerId(), run, dataset.cases(), findResults(claim.ownerId(), claim.runId())));
    }

    @Override
    @Transactional
    public void saveResult(String leaseId, CaseResult result) {
        jdbc.sql("""
                INSERT INTO evaluation_case_result (
                    run_id, dataset_id, case_id, case_type, outcome, result_rank, claim_count,
                    citation_count, latency_ms, token_count, estimated_cost, error_code, completed_at
                ) SELECT run_id, dataset_id, :caseId, :caseType, :outcome, :rank, :claims,
                    :citations, :latency, :tokens, :cost, :errorCode, :completedAt
                FROM evaluation_run
                WHERE run_id = :runId AND status = 'RUNNING' AND lease_id = :leaseId
                ON DUPLICATE KEY UPDATE run_id = evaluation_case_result.run_id
                """).param("caseId", result.caseId()).param("caseType", result.caseType().name())
                .param("outcome", result.outcome().name()).param("rank", result.rank())
                .param("claims", result.claimCount()).param("citations", result.citationCount())
                .param("latency", result.latencyMs()).param("tokens", result.tokenCount())
                .param("cost", result.estimatedCost()).param("errorCode", result.errorCode())
                .param("completedAt", result.completedAt()).param("runId", result.runId().toString())
                .param("leaseId", leaseId).update();
        int updated = jdbc.sql("""
                UPDATE evaluation_run SET completed_case_count = (
                    SELECT COUNT(*) FROM evaluation_case_result WHERE run_id = :runId
                ) WHERE run_id = :runId AND status = 'RUNNING' AND lease_id = :leaseId
                """).param("runId", result.runId().toString()).param("leaseId", leaseId).update();
        if (updated != 1) throw new IllegalStateException("evaluation run lease was lost");
    }

    @Override
    public void completeRun(String leaseId, UUID runId, Instant completedAt) {
        int updated = jdbc.sql("""
                UPDATE evaluation_run SET status = 'COMPLETED', completed_at = :completedAt,
                    lease_id = NULL, lease_until = NULL
                WHERE run_id = :runId AND status = 'RUNNING' AND lease_id = :leaseId
                  AND completed_case_count = total_case_count
                """).param("completedAt", completedAt).param("runId", runId.toString())
                .param("leaseId", leaseId).update();
        if (updated != 1) throw new IllegalStateException("evaluation run is incomplete or lease was lost");
    }

    @Override
    public void failRun(String leaseId, UUID runId, String errorCode, Instant completedAt) {
        jdbc.sql("""
                UPDATE evaluation_run SET status = 'FAILED', error_code = :errorCode,
                    completed_at = :completedAt, lease_id = NULL, lease_until = NULL
                WHERE run_id = :runId AND status = 'RUNNING' AND lease_id = :leaseId
                """).param("errorCode", errorCode).param("completedAt", completedAt)
                .param("runId", runId.toString()).param("leaseId", leaseId).update();
    }

    private void insertCase(UUID datasetId, EvaluationCase value) {
        jdbc.sql("""
                INSERT INTO evaluation_case (
                    dataset_id, case_id, ordinal, case_type, job_id, question, expected_segment_ids,
                    review_status, reviewer_count, case_fingerprint
                ) VALUES (:datasetId,:caseId,:ordinal,:caseType,:jobId,:question,
                    CAST(:expected AS JSON),:reviewStatus,:reviewers,:fingerprint)
                """).param("datasetId", datasetId.toString()).param("caseId", value.caseId())
                .param("ordinal", value.ordinal()).param("caseType", value.type().name())
                .param("jobId", value.jobId().toString()).param("question", value.question())
                .param("expected", write(value.expectedSegmentIds())).param("reviewStatus", value.reviewStatus())
                .param("reviewers", value.reviewerCount()).param("fingerprint", value.fingerprint()).update();
    }

    private Optional<EvaluationDataset> findByVersion(
            OwnerId ownerId, String dataVersion, DatasetSplit split) {
        return jdbc.sql("""
                SELECT * FROM evaluation_dataset
                WHERE owner_id = :owner AND data_version = :version AND split = :split
                """).param("owner", ownerId.value().toString()).param("version", dataVersion)
                .param("split", split.name()).query(this::datasetRow).optional().map(this::withCases);
    }

    private List<EvaluationRun> findByIntent(OwnerId ownerId, String idempotencyKey) {
        return jdbc.sql("""
                SELECT * FROM evaluation_run WHERE owner_id = :owner AND idempotency_key = :key
                ORDER BY repetition_index
                """).param("owner", ownerId.value().toString()).param("key", idempotencyKey)
                .query(this::runRow).list();
    }

    private List<EvaluationRun> matchingRequest(List<EvaluationRun> existing, String requestFingerprint) {
        if (existing.stream().anyMatch(run -> !run.requestFingerprint().equals(requestFingerprint))) {
            throw new EvaluationException(EvaluationException.Reason.IDEMPOTENCY_KEY_REUSED);
        }
        return existing;
    }

    private EvaluationDataset withCases(EvaluationDataset dataset) {
        List<EvaluationCase> cases = jdbc.sql("""
                SELECT * FROM evaluation_case WHERE dataset_id = :datasetId ORDER BY ordinal
                """).param("datasetId", dataset.datasetId().toString()).query(this::caseRow).list();
        return new EvaluationDataset(
                dataset.datasetId(), dataset.dataVersion(), dataset.fingerprint(), dataset.split(),
                dataset.status(), dataset.videoCount(), cases, dataset.frozenAt(), dataset.createdAt());
    }

    private EvaluationDataset datasetRow(ResultSet row, int ignored) throws SQLException {
        return new EvaluationDataset(
                UUID.fromString(row.getString("dataset_id")), row.getString("data_version"),
                row.getString("dataset_fingerprint"), DatasetSplit.valueOf(row.getString("split")),
                DatasetStatus.valueOf(row.getString("status")), row.getInt("video_count"), List.of(),
                instant(row, "frozen_at"), instant(row, "created_at"));
    }

    private EvaluationCase caseRow(ResultSet row, int ignored) throws SQLException {
        return new EvaluationCase(
                row.getString("case_id"), row.getInt("ordinal"),
                CaseType.valueOf(row.getString("case_type")), UUID.fromString(row.getString("job_id")),
                row.getString("question"), read(row.getString("expected_segment_ids"), UUIDS),
                row.getString("review_status"), row.getInt("reviewer_count"),
                row.getString("case_fingerprint"));
    }

    private EvaluationRun runRow(ResultSet row, int ignored) throws SQLException {
        return new EvaluationRun(
                UUID.fromString(row.getString("run_id")), UUID.fromString(row.getString("series_id")),
                UUID.fromString(row.getString("dataset_id")), row.getString("dataset_fingerprint"),
                row.getString("idempotency_key"), row.getString("request_fingerprint"),
                row.getString("execution_fingerprint"),
                read(row.getString("execution_versions"), ExecutionVersions.class),
                RunStatus.valueOf(row.getString("status")), row.getInt("repetition_index"),
                row.getInt("repetition_count"), row.getInt("completed_case_count"),
                row.getInt("total_case_count"), row.getString("error_code"),
                instant(row, "created_at"), instant(row, "completed_at"));
    }

    private CaseResult resultRow(ResultSet row, int ignored) throws SQLException {
        int rank = row.getInt("result_rank");
        return new CaseResult(
                UUID.fromString(row.getString("run_id")), row.getString("case_id"),
                CaseType.valueOf(row.getString("case_type")), CaseOutcome.valueOf(row.getString("outcome")),
                row.wasNull() ? null : rank, row.getInt("claim_count"), row.getInt("citation_count"),
                row.getLong("latency_ms"), row.getLong("token_count"), row.getBigDecimal("estimated_cost"),
                row.getString("error_code"), instant(row, "completed_at"));
    }

    private Instant instant(ResultSet row, String field) throws SQLException {
        var value = row.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("evaluation JSON could not be written", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("evaluation JSON could not be read", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("evaluation JSON could not be read", exception);
        }
    }

    private record ClaimRow(UUID runId, OwnerId ownerId) {
    }
}
