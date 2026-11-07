package com.videopilot.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("integration")
class DatabaseMigrationIT {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void validatesAndDoesNotRepeatAppliedMigrations() {
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        List<String> tables = jdbcClient.sql("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN (
                    'media_asset',
                    'media_upload_session',
                    'analysis_job',
                    'job_stage_attempt',
                    'job_event_outbox',
                    'analysis_message_failure',
                    'context_work_item',
                    'model_call',
                    'video_context',
                    'video_index',
                    'video_chunk',
                    'video_segment',
                    'identity_user',
                    'user_session'
                  )
                ORDER BY table_name
                """)
                .query(String.class)
                .list();

        assertThat(tables).containsExactly(
                "analysis_job",
                "analysis_message_failure",
                "context_work_item",
                "identity_user",
                "job_event_outbox",
                "job_stage_attempt",
                "media_asset",
                "media_upload_session",
                "model_call",
                "user_session",
                "video_chunk",
                "video_context",
                "video_index",
                "video_segment");
    }

    @Test
    void addsDurableMessageFailureAudit() {
        List<String> checks = jdbcClient.sql("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = DATABASE()
                  AND table_name = 'analysis_message_failure'
                  AND constraint_type = 'CHECK'
                """).query(String.class).list();

        assertThat(checks).containsExactly("chk_message_failure_attempts");
    }

    @Test
    void addsForwardOnlyJobTransitionConstraintsAndEventTime() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'job_event_outbox'
                  AND column_name = 'occurred_at'
                """)
                .query(String.class)
                .list();
        List<String> checks = jdbcClient.sql("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = DATABASE()
                  AND table_name IN ('analysis_job', 'job_stage_attempt', 'job_event_outbox')
                  AND constraint_type = 'CHECK'
                ORDER BY constraint_name
                """)
                .query(String.class)
                .list();

        assertThat(columns).containsExactly("occurred_at");
        assertThat(checks).contains(
                "chk_analysis_job_status",
                "chk_job_stage_status",
                "chk_job_event_sequence",
                "chk_job_event_schema_version");
    }

    @Test
    void addsDurableFieldsForTheOwnerScopedEventFeed() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'job_event_outbox'
                  AND column_name IN ('job_status', 'job_stage', 'public_message', 'terminal')
                ORDER BY ordinal_position
                """).query(String.class).list();

        assertThat(columns).containsExactly("job_status", "job_stage", "public_message", "terminal");
    }

    @Test
    void addsRecoverableOutboxRelayLeaseAndStatusConstraint() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'job_event_outbox'
                  AND column_name IN ('lease_id', 'lease_until')
                ORDER BY column_name
                """).query(String.class).list();
        List<String> checks = jdbcClient.sql("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = DATABASE()
                  AND table_name = 'job_event_outbox'
                  AND constraint_type = 'CHECK'
                """).query(String.class).list();

        assertThat(columns).containsExactly("lease_id", "lease_until");
        assertThat(checks).contains("chk_outbox_publish_status");
    }

    @Test
    void addsDurableManualReplayIdempotencyAudit() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'job_event_outbox'
                  AND column_name IN ('operation_key', 'operation_fingerprint', 'operation_attempt')
                ORDER BY ordinal_position
                """).query(String.class).list();
        List<String> indexes = jdbcClient.sql("""
                SELECT DISTINCT index_name FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'job_event_outbox'
                  AND index_name = 'uk_job_event_replay_operation'
                """).query(String.class).list();

        assertThat(columns).containsExactly("operation_key", "operation_fingerprint", "operation_attempt");
        assertThat(indexes).containsExactly("uk_job_event_replay_operation");
    }

    @Test
    @Transactional
    void enforcesJobIdentityAndStageFingerprintUniqueness() {
        insertMedia("00000000-0000-0000-0000-000000000001");
        insertJob(
                "10000000-0000-0000-0000-000000000001",
                "analysis-key-1",
                "idempotency-key-1");

        assertThatThrownBy(() -> insertJob(
                "10000000-0000-0000-0000-000000000002",
                "analysis-key-1",
                "idempotency-key-2"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertJob(
                "10000000-0000-0000-0000-000000000003",
                "analysis-key-3",
                "idempotency-key-1"))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertStageAttempt(
                "10000000-0000-0000-0000-000000000001",
                "input-fingerprint-1");
        assertThatThrownBy(() -> insertStageAttempt(
                "10000000-0000-0000-0000-000000000001",
                "input-fingerprint-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertMedia(String mediaId) {
        jdbcClient.sql("""
                INSERT INTO media_asset (
                    media_id, owner_id, media_fingerprint, object_key,
                    original_filename, media_status
                ) VALUES (
                    :mediaId, 'learner-1', REPEAT('a', 64),
                    'media/test.mp4', 'test.mp4', 'UPLOADED'
                )
                """)
                .param("mediaId", mediaId)
                .update();
    }

    private void insertJob(String jobId, String analysisKey, String idempotencyKey) {
        jdbcClient.sql("""
                INSERT INTO analysis_job (
                    job_id, owner_id, media_id, analysis_key, idempotency_key,
                    request_fingerprint, goal, mode, pipeline_version, status
                ) VALUES (
                    :jobId, 'learner-1', '00000000-0000-0000-0000-000000000001',
                    :analysisKey, :idempotencyKey, REPEAT('b', 64),
                    'Summarize the lesson', 'STANDARD', 'pipeline-v1', 'CREATED'
                )
                """)
                .param("jobId", jobId)
                .param("analysisKey", analysisKey)
                .param("idempotencyKey", idempotencyKey)
                .update();
    }

    private void insertStageAttempt(String jobId, String inputFingerprint) {
        jdbcClient.sql("""
                INSERT INTO job_stage_attempt (
                    job_id, stage, input_fingerprint, checkpoint_schema_version,
                    status
                ) VALUES (
                    :jobId, 'PROBING', :inputFingerprint, 1, 'STARTED'
                )
                """)
                .param("jobId", jobId)
                .param("inputFingerprint", inputFingerprint)
                .update();
    }
}
