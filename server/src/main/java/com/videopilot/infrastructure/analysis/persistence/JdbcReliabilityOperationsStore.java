package com.videopilot.infrastructure.analysis.persistence;

import com.videopilot.analysis.port.ReliabilityOperationsStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

final class JdbcReliabilityOperationsStore implements ReliabilityOperationsStore {

    private final JdbcClient jdbc;

    JdbcReliabilityOperationsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Snapshot load(String ownerId, Instant windowStart, Instant windowEnd) {
        long pending = jdbc.sql("""
                SELECT COUNT(*) FROM job_event_outbox event
                JOIN analysis_job job ON job.job_id = event.job_id
                WHERE job.owner_id = :owner
                  AND event.publish_status IN ('PENDING', 'PUBLISHING', 'RETRY')
                """).param("owner", ownerId).query(Long.class).single();
        List<Count> failures = jdbc.sql("""
                SELECT error_category, COUNT(*) AS failure_count
                FROM analysis_job
                WHERE owner_id = :owner AND updated_at >= :windowStart AND updated_at < :windowEnd
                  AND error_category IS NOT NULL
                GROUP BY error_category ORDER BY failure_count DESC, error_category
                LIMIT 20
                """).param("owner", ownerId).param("windowStart", windowStart).param("windowEnd", windowEnd)
                .query((row, ignored) -> new Count(row.getString("error_category"), row.getLong("failure_count")))
                .list();
        List<StageLatency> stages = jdbc.sql("""
                WITH durations AS (
                    SELECT attempt.stage,
                           TIMESTAMPDIFF(MICROSECOND, attempt.started_at, attempt.completed_at) DIV 1000 AS duration_ms
                    FROM job_stage_attempt attempt
                    JOIN analysis_job job ON job.job_id = attempt.job_id
                    WHERE job.owner_id = :owner AND attempt.status = 'COMPLETED'
                      AND attempt.completed_at >= :windowStart AND attempt.completed_at < :windowEnd
                ), ranked AS (
                    SELECT stage, duration_ms,
                           ROW_NUMBER() OVER (PARTITION BY stage ORDER BY duration_ms) AS rank_position,
                           COUNT(*) OVER (PARTITION BY stage) AS sample_count
                    FROM durations
                )
                SELECT stage,
                       MIN(CASE WHEN rank_position >= CEIL(sample_count * 0.50) THEN duration_ms END) AS p50_ms,
                       MIN(CASE WHEN rank_position >= CEIL(sample_count * 0.95) THEN duration_ms END) AS p95_ms
                FROM ranked GROUP BY stage ORDER BY stage LIMIT 20
                """).param("owner", ownerId).param("windowStart", windowStart).param("windowEnd", windowEnd)
                .query((row, ignored) -> new StageLatency(
                        row.getString("stage"), row.getLong("p50_ms"), row.getLong("p95_ms")))
                .list();
        List<ProviderUsage> providers = jdbc.sql("""
                SELECT call_record.provider,
                       COUNT(*) AS total_calls,
                       SUM(CASE WHEN call_record.status IN ('FAILED', 'UNKNOWN_OUTCOME') THEN 1 ELSE 0 END)
                           AS failed_calls,
                       COALESCE(SUM(call_record.input_units), 0) AS input_units,
                       COALESCE(SUM(call_record.output_units), 0) AS output_units,
                       CASE WHEN SUM(CASE WHEN call_record.price_status <> 'AVAILABLE'
                                              OR call_record.estimated_cost IS NULL THEN 1 ELSE 0 END) > 0
                            THEN NULL ELSE COALESCE(SUM(call_record.estimated_cost), 0) END AS estimated_cost,
                       CASE WHEN COUNT(DISTINCT call_record.currency) = 1 THEN MAX(call_record.currency)
                            ELSE NULL END AS currency
                FROM model_call call_record
                JOIN analysis_job job ON job.job_id = call_record.job_id
                WHERE job.owner_id = :owner
                  AND call_record.started_at >= :windowStart AND call_record.started_at < :windowEnd
                GROUP BY call_record.provider ORDER BY total_calls DESC, call_record.provider LIMIT 20
                """).param("owner", ownerId).param("windowStart", windowStart).param("windowEnd", windowEnd)
                .query((row, ignored) -> new ProviderUsage(
                        row.getString("provider"), row.getLong("total_calls"), row.getLong("failed_calls"),
                        row.getLong("input_units"), row.getLong("output_units"),
                        row.getBigDecimal("estimated_cost"), row.getString("currency")))
                .list();
        return new Snapshot(pending, failures, stages, providers);
    }
}
