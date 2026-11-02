package com.videopilot.infrastructure.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.analysis.application.JobTransitionCommand;
import com.videopilot.analysis.application.AnalysisJobEventApplicationService;
import com.videopilot.analysis.application.AnalysisJobException;
import com.videopilot.analysis.application.JobTransitionConflictException;
import com.videopilot.analysis.application.JobTransitionService;
import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.JobEventType;
import com.videopilot.analysis.domain.JobStage;
import com.videopilot.analysis.domain.JobStageAttempt;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("integration")
class AnalysisJobPersistenceAdapterIT {

    private static final UUID OWNER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private final UUID mediaId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @Autowired
    private JobTransitionService service;

    @Autowired
    private AnalysisJobEventApplicationService jobEvents;

    @Autowired
    private JdbcClient jdbcClient;

    @AfterEach
    void cleanUp() {
        jdbcClient.sql("DELETE FROM job_event_outbox WHERE job_id = :jobId")
                .param("jobId", jobId.toString())
                .update();
        jdbcClient.sql("DELETE FROM job_stage_attempt WHERE job_id = :jobId")
                .param("jobId", jobId.toString())
                .update();
        jdbcClient.sql("DELETE FROM analysis_job WHERE job_id = :jobId")
                .param("jobId", jobId.toString())
                .update();
        jdbcClient.sql("DELETE FROM media_asset WHERE media_id = :mediaId")
                .param("mediaId", mediaId.toString())
                .update();
    }

    @Test
    void concurrentTransitionsFromTheSameVersionHaveExactlyOneWinner() throws Exception {
        insertCreatedJob();
        JobTransitionCommand command = transition(Optional.empty());
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<Object> transition = () -> {
                start.await();
                try {
                    return service.transition(command);
                } catch (JobTransitionConflictException conflict) {
                    return conflict;
                }
            };
            List<Future<Object>> results = List.of(
                    executor.submit(transition), executor.submit(transition));
            start.countDown();

            List<Object> outcomes = results.stream().map(this::resultOf).toList();
            assertThat(outcomes).filteredOn(AnalysisJob.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(JobTransitionConflictException.class::isInstance).hasSize(1);
        }

        assertThat(jobValue("status", String.class)).isEqualTo("QUEUED");
        assertThat(jobValue("version", Long.class)).isEqualTo(1L);
        assertThat(jobValue("event_sequence", Long.class)).isEqualTo(1L);
        assertThat(count("job_event_outbox")).isEqualTo(1);
    }

    @Test
    void writesJobStageAndEventAtomically() {
        insertCreatedJob();
        JobStageAttempt stage = JobStageAttempt.started(
                jobId, JobStage.SUBMISSION, "c".repeat(64), 1, Instant.now());

        service.transition(transition(Optional.of(stage)));

        assertThat(jobValue("status", String.class)).isEqualTo("QUEUED");
        assertThat(count("job_stage_attempt")).isEqualTo(1);
        assertThat(count("job_event_outbox")).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        SELECT occurred_at IS NOT NULL
                        FROM job_event_outbox
                        WHERE job_id = :jobId
                        """)
                .param("jobId", jobId.toString())
                .query(Boolean.class)
                .single()).isTrue();
    }

    @Test
    void rollsBackCasWhenStageFingerprintConflicts() {
        insertCreatedJob();
        String fingerprint = "d".repeat(64);
        insertStage(fingerprint);
        JobStageAttempt duplicate = JobStageAttempt.started(
                jobId, JobStage.SUBMISSION, fingerprint, 1, Instant.now());

        assertThatThrownBy(() -> service.transition(transition(Optional.of(duplicate))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jobValue("status", String.class)).isEqualTo("CREATED");
        assertThat(jobValue("version", Long.class)).isZero();
        assertThat(count("job_event_outbox")).isZero();
    }

    @Test
    void resumesPersistedEventsBySequenceAndHidesForeignOwnership() {
        insertCreatedJob();
        service.transition(transition(Optional.empty()));
        OwnerContext owner = new OwnerContext(new OwnerId(OWNER_ID));

        var batch = jobEvents.readAfter(owner, jobId, 0, 100);

        assertThat(batch.currentSequence()).isEqualTo(1);
        assertThat(batch.events()).singleElement().satisfies(event -> {
            assertThat(event.sequence()).isEqualTo(1);
            assertThat(event.status()).isEqualTo(JobStatus.QUEUED);
            assertThat(event.eventType()).isEqualTo(JobEventType.JOB_QUEUED);
        });
        OwnerContext foreign = new OwnerContext(new OwnerId(UUID.randomUUID()));
        assertThatThrownBy(() -> jobEvents.readAfter(foreign, jobId, 0, 100))
                .isInstanceOf(AnalysisJobException.class)
                .extracting(exception -> ((AnalysisJobException) exception).reason())
                .isEqualTo(AnalysisJobException.Reason.RESOURCE_NOT_FOUND);
    }

    @Test
    void cancellationAndConsumerRaceEndsOnALegalCasBranch() throws Exception {
        insertCreatedJob();
        service.transition(transition(Optional.empty()));
        AnalysisJob queued = serviceSnapshot();
        JobTransitionCommand consume = new JobTransitionCommand(
                jobId,
                JobStatus.QUEUED,
                queued.version(),
                JobStatus.CONSUMING,
                Optional.empty(),
                Optional.empty(),
                JobEventType.STAGE_STARTED,
                "request-consumer",
                "{\"stage\":\"CONSUMPTION\",\"status\":\"STARTED\"}");
        OwnerContext owner = new OwnerContext(new OwnerId(OWNER_ID));
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> consumer = executor.submit(() -> {
                start.await();
                try {
                    return service.transition(consume);
                } catch (JobTransitionConflictException conflict) {
                    return conflict;
                }
            });
            Future<?> cancellation = executor.submit(() -> {
                start.await();
                return jobEvents.cancel(owner, jobId, "request-cancel");
            });
            start.countDown();
            consumer.get();
            cancellation.get();
        }

        assertThat(serviceSnapshot().status())
                .isIn(JobStatus.CANCELED, JobStatus.CANCEL_REQUESTED);
    }

    private JobTransitionCommand transition(Optional<JobStageAttempt> stage) {
        return new JobTransitionCommand(
                jobId,
                JobStatus.CREATED,
                0,
                JobStatus.QUEUED,
                stage,
                Optional.empty(),
                JobEventType.JOB_QUEUED,
                "request-" + jobId,
                "{\"status\":\"QUEUED\"}");
    }

    private void insertCreatedJob() {
        jdbcClient.sql("""
                INSERT INTO media_asset (
                    media_id, owner_id, media_fingerprint, object_key,
                    original_filename, media_status
                ) VALUES (:mediaId, :ownerId, REPEAT('a', 64), :objectKey, 'test.mp4', 'UPLOADED')
                """)
                .param("mediaId", mediaId.toString())
                .param("ownerId", OWNER_ID.toString())
                .param("objectKey", "media/" + mediaId + ".mp4")
                .update();
        jdbcClient.sql("""
                INSERT INTO analysis_job (
                    job_id, owner_id, media_id, analysis_key, idempotency_key,
                    request_fingerprint, goal, mode, pipeline_version, status
                ) VALUES (
                    :jobId, :ownerId, :mediaId, :analysisKey, :idempotencyKey,
                    REPEAT('b', 64), 'Summarize', 'STANDARD', 'pipeline-v1', 'CREATED'
                )
                """)
                .param("jobId", jobId.toString())
                .param("ownerId", OWNER_ID.toString())
                .param("mediaId", mediaId.toString())
                .param("analysisKey", "analysis-" + jobId)
                .param("idempotencyKey", "idempotency-" + jobId)
                .update();
    }

    private AnalysisJob serviceSnapshot() {
        return jdbcClient.sql("SELECT status, version, event_sequence FROM analysis_job WHERE job_id = :jobId")
                .param("jobId", jobId.toString())
                .query((resultSet, rowNum) -> new AnalysisJob(
                        jobId,
                        OWNER_ID.toString(),
                        mediaId,
                        JobStatus.valueOf(resultSet.getString("status")),
                        Optional.empty(),
                        0,
                        resultSet.getLong("version"),
                        resultSet.getLong("event_sequence"),
                        Optional.empty(),
                        Instant.now(),
                        Instant.now(),
                        Optional.empty()))
                .single();
    }

    private void insertStage(String fingerprint) {
        jdbcClient.sql("""
                INSERT INTO job_stage_attempt (
                    job_id, stage, input_fingerprint, checkpoint_schema_version, status
                ) VALUES (:jobId, 'SUBMISSION', :fingerprint, 1, 'STARTED')
                """)
                .param("jobId", jobId.toString())
                .param("fingerprint", fingerprint)
                .update();
    }

    private int count(String table) {
        String sql = switch (table) {
            case "job_stage_attempt" -> "SELECT COUNT(*) FROM job_stage_attempt WHERE job_id = :jobId";
            case "job_event_outbox" -> "SELECT COUNT(*) FROM job_event_outbox WHERE job_id = :jobId";
            default -> throw new IllegalArgumentException("Unsupported test table: " + table);
        };
        return jdbcClient.sql(sql)
                .param("jobId", jobId.toString())
                .query(Integer.class)
                .single();
    }

    private <T> T jobValue(String column, Class<T> type) {
        String sql = switch (column) {
            case "status" -> "SELECT status FROM analysis_job WHERE job_id = :jobId";
            case "version" -> "SELECT version FROM analysis_job WHERE job_id = :jobId";
            case "event_sequence" -> "SELECT event_sequence FROM analysis_job WHERE job_id = :jobId";
            default -> throw new IllegalArgumentException("Unsupported test column: " + column);
        };
        return jdbcClient.sql(sql)
                .param("jobId", jobId.toString())
                .query(type)
                .single();
    }

    private Object resultOf(Future<Object> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
