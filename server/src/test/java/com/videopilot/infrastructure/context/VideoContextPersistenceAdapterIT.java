package com.videopilot.infrastructure.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.context.application.VideoContextApplicationService;
import com.videopilot.context.application.VideoContextApplicationService.CompleteCommand;
import com.videopilot.context.application.VideoContextApplicationService.PrepareCommand;
import com.videopilot.context.application.VideoContextApplicationService.PublishCommand;
import com.videopilot.context.application.VideoContextApplicationService.RecoveryOutcome;
import com.videopilot.context.application.VideoContextApplicationService.WorkWindow;
import com.videopilot.context.domain.ContextWorkItemKind;
import com.videopilot.context.domain.ContextChannelState;
import com.videopilot.context.domain.VideoSegment;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("integration")
class VideoContextPersistenceAdapterIT {

    private final UUID ownerId = UUID.randomUUID();
    private final UUID mediaId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @Autowired
    private VideoContextApplicationService service;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("""
                INSERT INTO media_asset (
                    media_id, owner_id, media_fingerprint, object_key,
                    original_filename, media_status, duration_ms
                ) VALUES (:mediaId, :ownerId, REPEAT('a', 64), :objectKey, 'lesson.mp4', 'READY', 60000)
                """)
                .param("mediaId", mediaId.toString())
                .param("ownerId", ownerId.toString())
                .param("objectKey", "media/" + mediaId + ".mp4")
                .update();
        jdbcClient.sql("""
                INSERT INTO analysis_job (
                    job_id, owner_id, media_id, analysis_key, idempotency_key,
                    request_fingerprint, goal, mode, pipeline_version, status
                ) VALUES (
                    :jobId, :ownerId, :mediaId, :analysisKey, :idempotencyKey,
                    REPEAT('b', 64), 'Build context', 'STANDARD', 'm2-v1', 'CONTEXT_BUILDING'
                )
                """)
                .param("jobId", jobId.toString())
                .param("ownerId", ownerId.toString())
                .param("mediaId", mediaId.toString())
                .param("analysisKey", "analysis-" + jobId)
                .param("idempotencyKey", "idempotency-" + jobId)
                .update();
    }

    @AfterEach
    void cleanUp() {
        jdbcClient.sql("DELETE FROM video_segment WHERE context_id IN (SELECT context_id FROM video_context WHERE media_id = :mediaId)")
                .param("mediaId", mediaId.toString()).update();
        jdbcClient.sql("DELETE FROM context_work_item WHERE media_id = :mediaId")
                .param("mediaId", mediaId.toString()).update();
        jdbcClient.sql("DELETE FROM video_context WHERE media_id = :mediaId")
                .param("mediaId", mediaId.toString()).update();
        jdbcClient.sql("DELETE FROM job_stage_attempt WHERE job_id = :jobId")
                .param("jobId", jobId.toString()).update();
        jdbcClient.sql("DELETE FROM analysis_job WHERE job_id = :jobId")
                .param("jobId", jobId.toString()).update();
        jdbcClient.sql("DELETE FROM media_asset WHERE media_id = :mediaId")
                .param("mediaId", mediaId.toString()).update();
    }

    @Test
    void concurrentClaimHasExactlyOneWinner() throws Exception {
        UUID workItemId = prepare().workItems().getFirst().workItemId();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<RecoveryOutcome> first = executor.submit(() -> recoverAfter(start, workItemId));
            Future<RecoveryOutcome> second = executor.submit(() -> recoverAfter(start, workItemId));
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .filteredOn(RecoveryOutcome.CLAIMED::equals)
                    .hasSize(1);
        }

        assertThat(workItemValue(workItemId, "status", String.class)).isEqualTo("STARTED");
        assertThat(workItemValue(workItemId, "attempt", Integer.class)).isEqualTo(1);
        assertThat(workItemValue(workItemId, "version", Long.class)).isEqualTo(1L);
    }

    @Test
    void resultAndCheckpointMustBeSubmittedTogetherBeforeCompletion() {
        UUID workItemId = prepare().workItems().getFirst().workItemId();
        var claim = service.recover(owner(), workItemId);
        UUID claimId = claim.claimId().orElseThrow();

        assertThatThrownBy(() -> service.complete(owner(), new CompleteCommand(
                workItemId, claimId, 1, 1, "{}", "", "c".repeat(64))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(workItemValue(workItemId, "status", String.class)).isEqualTo("STARTED");

        assertThat(service.complete(owner(), new CompleteCommand(
                workItemId, claimId, 1, 1, "{\"samples\":1}",
                "context/audio/part-0.wav", "c".repeat(64))))
                .isTrue();
        assertThat(workItemValue(workItemId, "status", String.class)).isEqualTo("COMPLETED");
        assertThat(workItemValue(workItemId, "artifact_ref", String.class))
                .isEqualTo("context/audio/part-0.wav");
    }

    @Test
    void completedSameFingerprintIsReusedAndDifferentVersionIsNot() {
        var firstPlan = prepare();
        UUID workItemId = firstPlan.workItems().getFirst().workItemId();
        var claim = service.recover(owner(), workItemId);
        service.complete(owner(), new CompleteCommand(
                workItemId, claim.claimId().orElseThrow(), 1, 1, "{}",
                "context/audio/part-0.wav", "d".repeat(64)));

        var repeated = prepare();
        assertThat(repeated.context().contextId()).isEqualTo(firstPlan.context().contextId());
        assertThat(repeated.workItems().getFirst().workItemId()).isEqualTo(workItemId);
        assertThat(service.recover(owner(), workItemId).outcome()).isEqualTo(RecoveryOutcome.REUSED);

        var changed = service.prepare(owner(), command("extract-v2"));
        assertThat(changed.context().contextId()).isNotEqualTo(firstPlan.context().contextId());
        assertThat(changed.workItems().getFirst().workItemId()).isNotEqualTo(workItemId);
    }

    @Test
    void foreignOwnerCannotReadOrRecoverWork() {
        var plan = prepare();
        OwnerContext foreign = new OwnerContext(new OwnerId(UUID.randomUUID()));

        assertThat(service.findContext(foreign, plan.context().contextId())).isEmpty();
        assertThat(service.recover(foreign, plan.workItems().getFirst().workItemId()).outcome())
                .isEqualTo(RecoveryOutcome.NOT_FOUND);
        assertThat(workItemValue(plan.workItems().getFirst().workItemId(), "status", String.class))
                .isEqualTo("PENDING");
    }

    @Test
    void publicationAtomicallyCompletesCheckpointAndSegments() {
        var plan = prepare();
        insertContextStage(plan.context().inputFingerprint());

        service.publish(owner(), publication(plan.context().contextId(), plan.context().inputFingerprint(),
                List.of(segment(UUID.randomUUID(), 0))));

        assertThat(contextValue(plan.context().contextId(), "status", String.class)).isEqualTo("READY");
        assertThat(contextValue(plan.context().contextId(), "segment_count", Integer.class)).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT status FROM job_stage_attempt WHERE job_id = :jobId")
                .param("jobId", jobId.toString()).query(String.class).single()).isEqualTo("COMPLETED");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM video_segment WHERE context_id = :contextId")
                .param("contextId", plan.context().contextId().toString()).query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    void segmentConflictRollsBackContextAndCheckpointPublication() {
        var plan = prepare();
        insertContextStage(plan.context().inputFingerprint());
        VideoSegment first = segment(UUID.randomUUID(), 0);
        VideoSegment duplicateOrdinal = segment(UUID.randomUUID(), 0);

        assertThatThrownBy(() -> service.publish(owner(), publication(
                plan.context().contextId(), plan.context().inputFingerprint(),
                List.of(first, duplicateOrdinal))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(contextValue(plan.context().contextId(), "status", String.class)).isEqualTo("BUILDING");
        assertThat(jdbcClient.sql("SELECT status FROM job_stage_attempt WHERE job_id = :jobId")
                .param("jobId", jobId.toString()).query(String.class).single()).isEqualTo("STARTED");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM video_segment WHERE context_id = :contextId")
                .param("contextId", plan.context().contextId().toString()).query(Integer.class).single())
                .isZero();
    }

    private com.videopilot.context.port.VideoContextStore.PlanResult prepare() {
        return service.prepare(owner(), command("extract-v1"));
    }

    private PrepareCommand command(String extractionVersion) {
        return new PrepareCommand(
                jobId, mediaId, "a".repeat(64), "m2-v1", extractionVersion,
                "align-v1", "ffmpeg-v1", 60_000,
                List.of(new WorkWindow(
                        ContextWorkItemKind.AUDIO_SLICE, 0, 0, 30_000,
                        Map.of("codec", "pcm_s16le"))));
    }

    private RecoveryOutcome recoverAfter(CountDownLatch start, UUID workItemId) throws Exception {
        start.await();
        return service.recover(owner(), workItemId).outcome();
    }

    private OwnerContext owner() {
        return new OwnerContext(new OwnerId(ownerId));
    }

    private PublishCommand publication(UUID contextId, String fingerprint, List<VideoSegment> segments) {
        return new PublishCommand(
                contextId, jobId, 0, fingerprint, 1, "{\"segments\":1}",
                "context/manifest.json", "e".repeat(64), ContextChannelState.AVAILABLE,
                ContextChannelState.DEGRADED, segments);
    }

    private VideoSegment segment(UUID segmentId, int ordinal) {
        return new VideoSegment(
                segmentId, ordinal, 0, 30_000, Optional.of("lesson text"), "[]", "[]", "f".repeat(64));
    }

    private void insertContextStage(String fingerprint) {
        jdbcClient.sql("""
                INSERT INTO job_stage_attempt (
                    job_id, stage, input_fingerprint, checkpoint_schema_version, status
                ) VALUES (:jobId, 'CONTEXT_BUILDING', :fingerprint, 1, 'STARTED')
                """)
                .param("jobId", jobId.toString())
                .param("fingerprint", fingerprint)
                .update();
    }

    private <T> T contextValue(UUID contextId, String column, Class<T> type) {
        String sql = switch (column) {
            case "status" -> "SELECT status FROM video_context WHERE context_id = :contextId";
            case "segment_count" -> "SELECT segment_count FROM video_context WHERE context_id = :contextId";
            default -> throw new IllegalArgumentException("Unsupported test column: " + column);
        };
        return jdbcClient.sql(sql).param("contextId", contextId.toString()).query(type).single();
    }

    private <T> T workItemValue(UUID workItemId, String column, Class<T> type) {
        String sql = switch (column) {
            case "status" -> "SELECT status FROM context_work_item WHERE work_item_id = :workItemId";
            case "attempt" -> "SELECT attempt FROM context_work_item WHERE work_item_id = :workItemId";
            case "version" -> "SELECT version FROM context_work_item WHERE work_item_id = :workItemId";
            case "artifact_ref" -> "SELECT artifact_ref FROM context_work_item WHERE work_item_id = :workItemId";
            default -> throw new IllegalArgumentException("Unsupported test column: " + column);
        };
        return jdbcClient.sql(sql).param("workItemId", workItemId.toString()).query(type).single();
    }
}
