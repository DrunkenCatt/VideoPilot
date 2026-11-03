package com.videopilot.infrastructure.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.analysis.application.AnalysisJobApplicationService;
import com.videopilot.analysis.application.AnalysisJobEventApplicationService;
import com.videopilot.analysis.application.AnalysisJobException;
import com.videopilot.analysis.application.AnalysisJobView;
import com.videopilot.analysis.application.SubmitAnalysisJobCommand;
import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.AnalysisMode;
import com.videopilot.analysis.domain.JobEvent;
import com.videopilot.analysis.domain.JobEventType;
import com.videopilot.analysis.domain.JobStage;
import com.videopilot.analysis.domain.JobStageAttempt;
import com.videopilot.analysis.domain.JobStageStatus;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.analysis.domain.StoredAnalysisJob;
import com.videopilot.analysis.port.AnalysisJobStore;
import com.videopilot.analysis.port.AnalysisJobStore.JobCreation;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("integration")
class AnalysisJobCreationIT {

    private final OwnerId ownerId = new OwnerId(UUID.randomUUID());
    private final OwnerContext owner = new OwnerContext(ownerId);
    private final UUID mediaId = UUID.randomUUID();

    @Autowired
    private AnalysisJobApplicationService service;

    @Autowired
    private AnalysisJobEventApplicationService events;

    @Autowired
    private AnalysisJobStore store;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void seedReadyMedia() {
        jdbcClient.sql("""
                INSERT INTO media_asset (
                    media_id, owner_id, media_fingerprint, object_key,
                    original_filename, media_status
                ) VALUES (:mediaId, :ownerId, REPEAT('a', 64), :objectKey, 'ready.mp4', 'READY')
                """)
                .param("mediaId", mediaId.toString())
                .param("ownerId", ownerId.value().toString())
                .param("objectKey", "media/" + mediaId + ".mp4")
                .update();
    }

    @AfterEach
    void cleanUp() {
        jdbcClient.sql("""
                DELETE FROM job_event_outbox
                WHERE job_id IN (SELECT job_id FROM analysis_job WHERE owner_id = :ownerId)
                """).param("ownerId", ownerId.value().toString()).update();
        jdbcClient.sql("""
                DELETE FROM job_stage_attempt
                WHERE job_id IN (SELECT job_id FROM analysis_job WHERE owner_id = :ownerId)
                """).param("ownerId", ownerId.value().toString()).update();
        jdbcClient.sql("DELETE FROM analysis_job WHERE owner_id = :ownerId")
                .param("ownerId", ownerId.value().toString()).update();
        jdbcClient.sql("DELETE FROM media_asset WHERE owner_id = :ownerId")
                .param("ownerId", ownerId.value().toString()).update();
    }

    @Test
    void concurrentIdenticalSubmissionsCreateExactlyOneJobStageAndPendingEvent() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(6)) {
            Callable<UUID> submit = () -> {
                start.await();
                return service.submit(owner, command("Explain chapter one", "same-intent")).jobId();
            };
            List<Future<UUID>> futures = List.of(
                    executor.submit(submit), executor.submit(submit), executor.submit(submit),
                    executor.submit(submit), executor.submit(submit), executor.submit(submit));
            start.countDown();

            assertThat(futures.stream().map(this::resultOf).distinct()).hasSize(1);
        }

        assertThat(count("analysis_job")).isEqualTo(1);
        assertThat(count("job_stage_attempt")).isEqualTo(1);
        assertThat(count("job_event_outbox")).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        SELECT publish_status FROM job_event_outbox
                        WHERE job_id IN (SELECT job_id FROM analysis_job WHERE owner_id = :ownerId)
                        """)
                .param("ownerId", ownerId.value().toString())
                .query(String.class).single()).isEqualTo("PENDING");
    }

    @Test
    void sameKeyWithDifferentRequestHasAStableConflict() {
        AnalysisJobView first = service.submit(owner, command("Explain chapter one", "reused-intent"));

        assertThatThrownBy(() -> service.submit(owner, command("Explain chapter two", "reused-intent")))
                .isInstanceOf(AnalysisJobException.class)
                .extracting(exception -> ((AnalysisJobException) exception).reason())
                .isEqualTo(AnalysisJobException.Reason.IDEMPOTENCY_KEY_REUSED);
        assertThat(service.get(owner, first.jobId()).goal()).isEqualTo("Explain chapter one");
        assertThat(count("analysis_job")).isEqualTo(1);
    }

    @Test
    void aDifferentKeyForTheSameAnalysisReturnsTheExistingLogicalExecution() {
        AnalysisJobView first = service.submit(owner, command("Explain chapter one", "intent-one"));

        AnalysisJobView second = service.submit(owner, command("Explain chapter one", "intent-two"));

        assertThat(second.jobId()).isEqualTo(first.jobId());
        assertThat(count("analysis_job")).isEqualTo(1);
        assertThat(count("job_event_outbox")).isEqualTo(1);
    }

    @Test
    void clearingRedisRebuildsTheActiveKeyFromMysqlWithoutCreatingAnotherJob() {
        AnalysisJobView first = service.submit(owner, command("Explain chapter one", "intent-before-flush"));
        String analysisKey = analysisKey(first.jobId());
        String redisKey = "videopilot:analysis:active:" + ownerId.value() + ':' + analysisKey;
        assertThat(redis.opsForValue().get(redisKey)).contains(first.jobId().toString());

        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }

        AnalysisJobView retry = service.submit(owner, command("Explain chapter one", "intent-after-flush"));

        assertThat(retry.jobId()).isEqualTo(first.jobId());
        assertThat(count("analysis_job")).isEqualTo(1);
        assertThat(redis.opsForValue().get(redisKey)).contains(first.jobId().toString());
        assertThat(service.get(owner, first.jobId()).jobId()).isEqualTo(first.jobId());
        assertThat(events.readAfter(owner, first.jobId(), 0, 20).events()).hasSize(1);
    }

    @Test
    void redisUnavailableDoesNotChangeMysqlSubmissionQueryOrEventFacts() {
        AnalysisJobView first = service.submit(owner, command("Redis outage", "outage-intent-one"));

        AnalysisJobView retry = service.submit(owner, command("Redis outage", "outage-intent-two"));

        assertThat(retry.jobId()).isEqualTo(first.jobId());
        assertThat(count("analysis_job")).isEqualTo(1);
        assertThat(service.get(owner, first.jobId()).jobId()).isEqualTo(first.jobId());
        assertThat(events.readAfter(owner, first.jobId(), 0, 20).events()).hasSize(1);
    }

    @Test
    void queriesNeverRevealAnotherOwnersJob() {
        AnalysisJobView created = service.submit(owner, command("Private analysis", "private-intent"));
        OwnerContext otherOwner = new OwnerContext(new OwnerId(UUID.randomUUID()));

        assertThatThrownBy(() -> service.get(otherOwner, created.jobId()))
                .isInstanceOf(AnalysisJobException.class)
                .extracting(exception -> ((AnalysisJobException) exception).reason())
                .isEqualTo(AnalysisJobException.Reason.RESOURCE_NOT_FOUND);
        assertThat(service.list(otherOwner, 1, 20, Optional.empty()).data()).isEmpty();
        assertThat(service.list(owner, 1, 20, Optional.of(JobStatus.CREATED)).data())
                .extracting(AnalysisJobView::jobId)
                .containsExactly(created.jobId());
    }

    @Test
    void eventInsertFailureRollsBackTheJobAndInitialStage() {
        AnalysisJobView existing = service.submit(owner, command("Existing", "existing-intent"));
        UUID duplicateMessageId = jdbcClient.sql("""
                        SELECT message_id FROM job_event_outbox WHERE job_id = :jobId
                        """)
                .param("jobId", existing.jobId().toString())
                .query(String.class).single().transform(UUID::fromString);
        JobCreation brokenCreation = creationWithMessageId(duplicateMessageId);

        assertThatThrownBy(() -> store.create(brokenCreation))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM analysis_job WHERE job_id = :jobId")
                .param("jobId", brokenCreation.job().jobId().toString())
                .query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM job_stage_attempt WHERE job_id = :jobId")
                .param("jobId", brokenCreation.job().jobId().toString())
                .query(Integer.class).single()).isZero();
    }

    private SubmitAnalysisJobCommand command(String goal, String key) {
        return new SubmitAnalysisJobCommand(mediaId, goal, AnalysisMode.STANDARD, key, "request-it");
    }

    private JobCreation creationWithMessageId(UUID messageId) {
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-30T08:00:00Z");
        AnalysisJob job = new AnalysisJob(
                jobId, ownerId.value().toString(), mediaId, JobStatus.CREATED, Optional.empty(),
                0, 0, 1, Optional.empty(), now, now, Optional.empty());
        StoredAnalysisJob stored = new StoredAnalysisJob(
                job, "b".repeat(64), "rollback-intent", "c".repeat(64),
                "Rollback", AnalysisMode.STANDARD, "m1-fake-v1");
        JobStageAttempt stage = new JobStageAttempt(
                jobId, JobStage.SUBMISSION, "c".repeat(64), 1, JobStageStatus.COMPLETED, 1,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), now, Optional.of(now));
        JobEvent event = new JobEvent(
                messageId, jobId, 1, JobEventType.JOB_CREATED, 1,
                "request-it", "{\"status\":\"CREATED\"}", now);
        return new JobCreation(stored, stage, event);
    }

    private int count(String table) {
        String sql = switch (table) {
            case "analysis_job" -> "SELECT COUNT(*) FROM analysis_job WHERE owner_id = :ownerId";
            case "job_stage_attempt" -> """
                    SELECT COUNT(*) FROM job_stage_attempt
                    WHERE job_id IN (SELECT job_id FROM analysis_job WHERE owner_id = :ownerId)
                    """;
            case "job_event_outbox" -> """
                    SELECT COUNT(*) FROM job_event_outbox
                    WHERE job_id IN (SELECT job_id FROM analysis_job WHERE owner_id = :ownerId)
                    """;
            default -> throw new IllegalArgumentException("Unsupported table: " + table);
        };
        return jdbcClient.sql(sql).param("ownerId", ownerId.value().toString()).query(Integer.class).single();
    }

    private String analysisKey(UUID jobId) {
        return jdbcClient.sql("SELECT analysis_key FROM analysis_job WHERE job_id = :jobId")
                .param("jobId", jobId.toString())
                .query(String.class)
                .single();
    }

    private UUID resultOf(Future<UUID> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
