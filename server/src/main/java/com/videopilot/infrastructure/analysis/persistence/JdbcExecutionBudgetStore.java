package com.videopilot.infrastructure.analysis.persistence;

import com.videopilot.analysis.port.ExecutionBudgetStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

final class JdbcExecutionBudgetStore implements ExecutionBudgetStore {

    private final JdbcClient jdbc;

    JdbcExecutionBudgetStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Usage load(UUID jobId) {
        Aggregate aggregate = jdbc.sql("""
                SELECT job.created_at,
                       COALESCE(SUM(CASE WHEN call_record.price_status = 'AVAILABLE'
                                         THEN call_record.estimated_cost ELSE 0 END), 0) AS known_cost,
                       COALESCE(MAX(CASE WHEN call_record.model_call_id IS NOT NULL
                                             AND (call_record.price_status = 'UNAVAILABLE'
                                                  OR call_record.estimated_cost IS NULL)
                                         THEN 1 ELSE 0 END), 0)
                           AS unknown_price
                FROM analysis_job job
                LEFT JOIN model_call call_record ON call_record.job_id = job.job_id
                WHERE job.job_id = :jobId
                GROUP BY job.job_id, job.created_at
                """).param("jobId", jobId.toString()).query((row, ignored) -> new Aggregate(
                        row.getTimestamp("created_at").toInstant(),
                        row.getBigDecimal("known_cost"), row.getInt("unknown_price") == 1))
                .optional().orElseThrow(() -> new IllegalArgumentException("analysis job does not exist"));
        Map<String, OperationUsage> operations = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT CASE WHEN operation_type = 'RETRIEVAL_EMBEDDING' THEN 'EMBEDDING'
                            ELSE operation_type END AS budget_operation,
                       COUNT(*) AS call_count,
                       COALESCE(SUM(input_units), 0) AS input_units,
                       COALESCE(SUM(output_units), 0) AS output_units
                FROM model_call WHERE job_id = :jobId GROUP BY budget_operation
                """).param("jobId", jobId.toString()).query((row, ignored) -> Map.entry(
                        row.getString("budget_operation"), new OperationUsage(
                                row.getLong("call_count"), row.getLong("input_units"),
                                row.getLong("output_units"))))
                .list().forEach(entry -> operations.put(entry.getKey(), entry.getValue()));
        return new Usage(aggregate.createdAt(), operations, aggregate.knownCost(), aggregate.unknownPrice());
    }

    @Override
    public Usage loadEvaluationSeries(UUID jobId, UUID evaluationRunId) {
        Aggregate aggregate = jdbc.sql("""
                SELECT job.created_at,
                       COALESCE(SUM(CASE WHEN call_record.price_status = 'AVAILABLE'
                                         THEN call_record.estimated_cost ELSE 0 END), 0) AS known_cost,
                       COALESCE(MAX(CASE WHEN call_record.model_call_id IS NOT NULL
                                             AND (call_record.price_status = 'UNAVAILABLE'
                                                  OR call_record.estimated_cost IS NULL)
                                         THEN 1 ELSE 0 END), 0) AS unknown_price
                FROM analysis_job job
                CROSS JOIN evaluation_run selected_run
                LEFT JOIN evaluation_run series_run ON series_run.series_id = selected_run.series_id
                LEFT JOIN model_call call_record
                       ON call_record.job_id = job.job_id
                      AND call_record.evaluation_run_id = series_run.run_id
                WHERE job.job_id = :jobId AND selected_run.run_id = :evaluationRunId
                GROUP BY job.job_id, job.created_at, selected_run.series_id
                """).param("jobId", jobId.toString())
                .param("evaluationRunId", evaluationRunId.toString())
                .query((row, ignored) -> new Aggregate(
                        row.getTimestamp("created_at").toInstant(),
                        row.getBigDecimal("known_cost"), row.getInt("unknown_price") == 1))
                .optional().orElseThrow(() -> new IllegalArgumentException(
                        "analysis job or evaluation run does not exist"));
        Map<String, OperationUsage> operations = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT CASE WHEN call_record.operation_type = 'RETRIEVAL_EMBEDDING' THEN 'EMBEDDING'
                            ELSE call_record.operation_type END AS budget_operation,
                       COUNT(*) AS call_count,
                       COALESCE(SUM(call_record.input_units), 0) AS input_units,
                       COALESCE(SUM(call_record.output_units), 0) AS output_units
                FROM evaluation_run selected_run
                INNER JOIN evaluation_run series_run ON series_run.series_id = selected_run.series_id
                INNER JOIN model_call call_record ON call_record.evaluation_run_id = series_run.run_id
                WHERE selected_run.run_id = :evaluationRunId AND call_record.job_id = :jobId
                GROUP BY budget_operation
                """).param("jobId", jobId.toString())
                .param("evaluationRunId", evaluationRunId.toString())
                .query((row, ignored) -> Map.entry(
                        row.getString("budget_operation"), new OperationUsage(
                                row.getLong("call_count"), row.getLong("input_units"),
                                row.getLong("output_units"))))
                .list().forEach(entry -> operations.put(entry.getKey(), entry.getValue()));
        return new Usage(aggregate.createdAt(), operations, aggregate.knownCost(), aggregate.unknownPrice());
    }

    private record Aggregate(
            Instant createdAt, BigDecimal knownCost, boolean unknownPrice) { }
}
