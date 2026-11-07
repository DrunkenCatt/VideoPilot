package com.videopilot.infrastructure.evaluation;

import static com.videopilot.evaluation.domain.EvaluationModels.CaseOutcome.COMPLETED;
import static com.videopilot.evaluation.domain.EvaluationModels.CaseType.RETRIEVAL;
import static com.videopilot.evaluation.domain.EvaluationModels.DatasetSplit.DEV;
import static com.videopilot.evaluation.domain.EvaluationModels.DatasetStatus.FROZEN;
import static com.videopilot.evaluation.domain.EvaluationModels.RunStatus.REQUESTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.evaluation.domain.EvaluationModels.CaseResult;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationCase;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationDataset;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationRun;
import com.videopilot.evaluation.domain.EvaluationModels.ExecutionVersions;
import com.videopilot.identity.domain.OwnerId;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcEvaluationStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-08T03:00:00Z");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000601");
    private static final UUID DATASET = UUID.fromString("00000000-0000-0000-0000-000000000602");
    private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-000000000603");
    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000000604");
    private static final UUID SERIES = UUID.fromString("00000000-0000-0000-0000-000000000605");

    @Test
    void coversDatasetRunClaimResumeAndTerminalPersistencePaths() throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<Long> longs = mock(JdbcClient.MappedQuerySpec.class);
        AtomicReference<String> sql = new AtomicReference<>();
        when(jdbc.sql(anyString())).thenAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            return statement;
        });
        when(statement.param(anyString(), any(Object.class))).thenReturn(statement);
        when(statement.param(anyString(), isNull())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        when(statement.query(Long.class)).thenReturn(longs);
        when(longs.single()).thenReturn(1L);

        ResultSet row = row();
        when(statement.query(any(RowMapper.class))).thenAnswer(invocation -> {
            if (sql.get().contains("FROM evaluation_dataset")) {
                when(row.getString("status")).thenReturn("FROZEN");
            } else if (sql.get().contains("FROM evaluation_run")) {
                when(row.getString("status")).thenReturn("REQUESTED");
            }
            RowMapper<?> mapper = invocation.getArgument(0);
            Object mapped = mapper.mapRow(row, 0);
            JdbcClient.MappedQuerySpec<Object> query = mock(JdbcClient.MappedQuerySpec.class);
            if (sql.get().contains("idempotency_key = :key")) {
                doReturn(List.of()).when(query).list();
            } else {
                doReturn(List.of(mapped)).when(query).list();
            }
            doReturn(Optional.of(mapped)).when(query).optional();
            return query;
        });

        JdbcEvaluationStore store = new JdbcEvaluationStore(jdbc, new ObjectMapper());
        OwnerId ownerId = new OwnerId(OWNER);
        EvaluationDataset dataset = dataset();
        EvaluationRun run = run();

        assertThat(store.saveDataset(ownerId, dataset)).isEqualTo(dataset);
        assertThat(store.listDatasets(ownerId)).singleElement().satisfies(value ->
                assertThat(value.cases()).singleElement());
        assertThat(store.findDataset(ownerId, DATASET)).isPresent();
        assertThat(store.createRuns(
                ownerId, dataset, "intent-1", "c".repeat(64), SERIES, List.of(run)))
                .containsExactly(run);
        assertThat(store.findRun(ownerId, RUN)).isPresent();
        assertThat(store.findResults(ownerId, RUN)).singleElement();

        var claim = store.claimNext("00000000-0000-0000-0000-000000000606",
                NOW.plusSeconds(60), NOW).orElseThrow();
        assertThat(claim.ownerId()).isEqualTo(ownerId);
        assertThat(claim.cases()).singleElement();
        CaseResult result = result();
        store.saveResult("00000000-0000-0000-0000-000000000606", result);
        store.completeRun("00000000-0000-0000-0000-000000000606", RUN, NOW);
        store.failRun("00000000-0000-0000-0000-000000000606", RUN, "FAILED", NOW);
    }

    private ResultSet row() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("owner_id")).thenReturn(OWNER.toString());
        when(row.getString("dataset_id")).thenReturn(DATASET.toString());
        when(row.getString("data_version")).thenReturn("m5-dev-v1");
        when(row.getString("dataset_fingerprint")).thenReturn("a".repeat(64));
        when(row.getString("split")).thenReturn("DEV");
        when(row.getInt("video_count")).thenReturn(1);
        when(row.getTimestamp("frozen_at")).thenReturn(Timestamp.from(NOW));
        when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
        when(row.getTimestamp("completed_at")).thenReturn(Timestamp.from(NOW));
        when(row.getString("case_id")).thenReturn("case-1");
        when(row.getInt("ordinal")).thenReturn(1);
        when(row.getString("case_type")).thenReturn("RETRIEVAL");
        when(row.getString("job_id")).thenReturn(JOB.toString());
        when(row.getString("question")).thenReturn("question");
        when(row.getString("expected_segment_ids"))
                .thenReturn("[\"00000000-0000-0000-0000-000000000607\"]");
        when(row.getString("review_status")).thenReturn("APPROVED");
        when(row.getInt("reviewer_count")).thenReturn(1);
        when(row.getString("case_fingerprint")).thenReturn("b".repeat(64));
        when(row.getString("run_id")).thenReturn(RUN.toString());
        when(row.getString("series_id")).thenReturn(SERIES.toString());
        when(row.getString("idempotency_key")).thenReturn("intent-1");
        when(row.getString("request_fingerprint")).thenReturn("c".repeat(64));
        when(row.getString("execution_fingerprint")).thenReturn("d".repeat(64));
        when(row.getString("execution_versions")).thenReturn(new ObjectMapper().writeValueAsString(versions()));
        when(row.getInt("repetition_index")).thenReturn(1);
        when(row.getInt("repetition_count")).thenReturn(1);
        when(row.getInt("completed_case_count")).thenReturn(1);
        when(row.getInt("total_case_count")).thenReturn(1);
        when(row.getString("outcome")).thenReturn("COMPLETED");
        when(row.getInt("result_rank")).thenReturn(1);
        when(row.wasNull()).thenReturn(false);
        when(row.getInt("claim_count")).thenReturn(0);
        when(row.getInt("citation_count")).thenReturn(0);
        when(row.getLong("latency_ms")).thenReturn(10L);
        when(row.getLong("token_count")).thenReturn(0L);
        when(row.getBigDecimal("estimated_cost")).thenReturn((BigDecimal) null);
        return row;
    }

    private EvaluationDataset dataset() {
        return new EvaluationDataset(
                DATASET, "m5-dev-v1", "a".repeat(64), DEV, FROZEN, 1,
                List.of(new EvaluationCase(
                        "case-1", 1, RETRIEVAL, JOB, "question",
                        List.of(UUID.fromString("00000000-0000-0000-0000-000000000607")),
                        "APPROVED", 1, "b".repeat(64))), NOW, NOW);
    }

    private EvaluationRun run() {
        return new EvaluationRun(
                RUN, SERIES, DATASET, "a".repeat(64), "intent-1", "c".repeat(64), "d".repeat(64),
                versions(), REQUESTED, 1, 1, 0, 1, null, NOW, null);
    }

    private CaseResult result() {
        return new CaseResult(
                RUN, "case-1", RETRIEVAL, COMPLETED, 1, 0, 0, 10, 0, null, null, NOW);
    }

    private ExecutionVersions versions() {
        return new ExecutionVersions(
                "commit", "pipeline", "prompt", "model", "embedding", "chunk",
                "formula", "gate", "limits", "config");
    }
}
