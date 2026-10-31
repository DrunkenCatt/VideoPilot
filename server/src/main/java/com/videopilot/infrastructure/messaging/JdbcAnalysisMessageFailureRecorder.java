package com.videopilot.infrastructure.messaging;

import com.videopilot.analysis.port.AnalysisMessageFailureRecorder;
import org.springframework.jdbc.core.simple.JdbcClient;

final class JdbcAnalysisMessageFailureRecorder implements AnalysisMessageFailureRecorder {

    private final JdbcClient jdbc;

    JdbcAnalysisMessageFailureRecorder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(MessageFailure failure) {
        jdbc.sql("""
                INSERT INTO analysis_message_failure (
                    failure_key, message_id, job_id, error_code, error_summary,
                    delivery_attempts, first_seen_at, last_seen_at
                ) VALUES (
                    :failureKey, :messageId, :jobId, :errorCode, :errorSummary,
                    :deliveryAttempts, :occurredAt, :occurredAt
                )
                ON DUPLICATE KEY UPDATE
                    message_id = COALESCE(message_id, VALUES(message_id)),
                    job_id = COALESCE(job_id, VALUES(job_id)),
                    error_code = VALUES(error_code),
                    error_summary = VALUES(error_summary),
                    delivery_attempts = GREATEST(delivery_attempts, VALUES(delivery_attempts)),
                    last_seen_at = VALUES(last_seen_at)
                """)
                .param("failureKey", failure.failureKey())
                .param("messageId", failure.messageId().map(Object::toString).orElse(null))
                .param("jobId", failure.jobId().map(Object::toString).orElse(null))
                .param("errorCode", failure.errorCode())
                .param("errorSummary", failure.summary())
                .param("deliveryAttempts", failure.deliveryAttempts())
                .param("occurredAt", failure.occurredAt())
                .update();
    }
}
