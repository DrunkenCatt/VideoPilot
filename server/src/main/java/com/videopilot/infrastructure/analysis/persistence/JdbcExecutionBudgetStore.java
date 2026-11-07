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
                       COALESCE(SUM(call_record.input_units), 0) AS input_units,
                       COALESCE(SUM(call_record.output_units), 0) AS output_units,
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
                        row.getLong("input_units"), row.getLong("output_units"),
                        row.getBigDecimal("known_cost"), row.getInt("unknown_price") == 1))
                .optional().orElseThrow(() -> new IllegalArgumentException("analysis job does not exist"));
        Map<String, Long> calls = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT operation_type, COUNT(*) AS call_count
                FROM model_call WHERE job_id = :jobId GROUP BY operation_type
                """).param("jobId", jobId.toString()).query((row, ignored) -> Map.entry(
                        row.getString("operation_type"), row.getLong("call_count")))
                .list().forEach(entry -> calls.put(entry.getKey(), entry.getValue()));
        return new Usage(aggregate.createdAt(), calls, aggregate.inputUnits(), aggregate.outputUnits(),
                aggregate.knownCost(), aggregate.unknownPrice());
    }

    private record Aggregate(
            Instant createdAt, long inputUnits, long outputUnits,
            BigDecimal knownCost, boolean unknownPrice) { }
}
