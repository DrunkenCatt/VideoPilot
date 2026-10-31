package com.videopilot.infrastructure.messaging;

import com.videopilot.analysis.domain.AnalysisDispatchMessage;
import com.videopilot.analysis.port.OutboxRelayStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

public class JdbcOutboxRelayStore implements OutboxRelayStore {

    private final JdbcClient jdbc;

    public JdbcOutboxRelayStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public List<ClaimedOutboxMessage> claimBatch(int limit, Duration leaseDuration, Instant now) {
        UUID leaseId = UUID.randomUUID();
        Instant leaseUntil = now.plus(leaseDuration);
        List<OutboxRow> rows = jdbc.sql("""
                SELECT outbox_id, message_id, job_id, schema_version,
                       request_id, occurred_at, publish_attempts
                FROM job_event_outbox
                WHERE ((publish_status IN ('PENDING', 'RETRY') AND available_at <= :now)
                   OR (publish_status = 'PUBLISHING' AND lease_until <= :now))
                ORDER BY outbox_id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
                .param("now", now)
                .param("limit", limit)
                .query(this::mapRow)
                .list();
        List<ClaimedOutboxMessage> claimed = new ArrayList<>(rows.size());
        for (OutboxRow row : rows) {
            int updated = jdbc.sql("""
                    UPDATE job_event_outbox
                    SET publish_status = 'PUBLISHING',
                        publish_attempts = publish_attempts + 1,
                        lease_id = :leaseId,
                        lease_until = :leaseUntil,
                        last_error_summary = NULL,
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE outbox_id = :outboxId
                    """)
                    .param("leaseId", leaseId.toString())
                    .param("leaseUntil", leaseUntil)
                    .param("outboxId", row.outboxId())
                    .update();
            if (updated != 1) {
                throw new IllegalStateException("Outbox row was lost during claim");
            }
            claimed.add(new ClaimedOutboxMessage(
                    row.outboxId(),
                    leaseId,
                    new AnalysisDispatchMessage(
                            row.messageId(),
                            row.jobId(),
                            row.schemaVersion(),
                            row.requestId(),
                            row.occurredAt()),
                    row.publishAttempts() + 1));
        }
        return claimed;
    }

    @Override
    public boolean markPublished(long outboxId, UUID leaseId, Instant publishedAt) {
        return jdbc.sql("""
                UPDATE job_event_outbox
                SET publish_status = 'PUBLISHED', published_at = :publishedAt,
                    lease_id = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP(6)
                WHERE outbox_id = :outboxId AND publish_status = 'PUBLISHING' AND lease_id = :leaseId
                """)
                .param("publishedAt", publishedAt)
                .param("outboxId", outboxId)
                .param("leaseId", leaseId.toString())
                .update() == 1;
    }

    @Override
    public boolean markRetry(
            long outboxId,
            UUID leaseId,
            Instant availableAt,
            String errorSummary,
            boolean deadLettered) {
        String status = deadLettered ? "DEAD_LETTERED" : "RETRY";
        return jdbc.sql("""
                UPDATE job_event_outbox
                SET publish_status = :status, available_at = :availableAt,
                    lease_id = NULL, lease_until = NULL, last_error_summary = :errorSummary,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE outbox_id = :outboxId AND publish_status = 'PUBLISHING' AND lease_id = :leaseId
                """)
                .param("status", status)
                .param("availableAt", availableAt)
                .param("errorSummary", errorSummary)
                .param("outboxId", outboxId)
                .param("leaseId", leaseId.toString())
                .update() == 1;
    }

    private OutboxRow mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        return new OutboxRow(
                resultSet.getLong("outbox_id"),
                UUID.fromString(resultSet.getString("message_id")),
                UUID.fromString(resultSet.getString("job_id")),
                resultSet.getInt("schema_version"),
                resultSet.getString("request_id"),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getInt("publish_attempts"));
    }
}
