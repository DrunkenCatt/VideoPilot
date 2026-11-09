package com.videopilot.infrastructure.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.analysis.port.SpeechRecognitionPort;
import com.videopilot.analysis.port.SpeechRecognitionException;
import com.videopilot.context.application.AsrExecutionApplicationService;
import com.videopilot.context.domain.ContextInputFingerprint;
import com.videopilot.context.domain.ContextWorkItemKind;
import com.videopilot.context.domain.ContextWorkSpec;
import com.videopilot.context.port.DerivedMediaStore;
import com.videopilot.context.port.VideoContextStore;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("integration")
class AsrRecoveryIT {

    private static final Instant NOW = Instant.parse("2026-11-04T09:00:00Z");
    private final UUID ownerId = UUID.randomUUID();
    private final UUID mediaId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @Autowired
    private VideoContextStore store;

    @Autowired
    private JdbcClient jdbcClient;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("""
                INSERT INTO media_asset (
                    media_id, owner_id, media_fingerprint, object_key,
                    original_filename, media_status, duration_ms
                ) VALUES (:mediaId, :ownerId, REPEAT('a', 64), :objectKey, 'lesson.mp4', 'READY', 62500)
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
                    REPEAT('b', 64), 'Build context', 'STANDARD', 'm2-context-v1', 'CONTEXT_BUILDING'
                )
                """)
                .param("jobId", jobId.toString())
                .param("ownerId", ownerId.toString())
                .param("mediaId", mediaId.toString())
                .param("analysisKey", "analysis-" + jobId)
                .param("idempotencyKey", "idempotency-" + jobId)
                .update();
        jdbcClient.sql("""
                INSERT INTO context_work_item (
                    work_item_id, media_id, created_by_job_id, kind, ordinal,
                    start_ms, end_ms, input_fingerprint, status, attempt,
                    checkpoint_payload, artifact_ref, artifact_digest, completed_at
                ) VALUES (
                    :workItemId, :mediaId, :jobId, 'AUDIO_SLICE', 0,
                    60000, 62500, REPEAT('c', 64), 'COMPLETED', 1,
                    JSON_OBJECT('startMs', 60000, 'endMs', 62500), :artifactRef, REPEAT('d', 64), :completedAt
                )
                """)
                .param("workItemId", UUID.randomUUID().toString())
                .param("mediaId", mediaId.toString())
                .param("jobId", jobId.toString())
                .param("artifactRef", "derived/" + mediaId + "/audio-0.wav")
                .param("completedAt", NOW)
                .update();
    }

    @AfterEach
    void cleanUp() {
        jdbcClient.sql("DELETE FROM model_call WHERE job_id = :jobId")
                .param("jobId", jobId.toString()).update();
        jdbcClient.sql("DELETE FROM context_work_item WHERE media_id = :mediaId")
                .param("mediaId", mediaId.toString()).update();
        jdbcClient.sql("DELETE FROM analysis_job WHERE job_id = :jobId")
                .param("jobId", jobId.toString()).update();
        jdbcClient.sql("DELETE FROM media_asset WHERE media_id = :mediaId")
                .param("mediaId", mediaId.toString()).update();
    }

    @Test
    void completedCheckpointMakesRestartAndRedeliveryAZeroCallNoOp() {
        AtomicInteger providerCalls = new AtomicInteger();
        SpeechRecognitionPort speech = request -> {
            providerCalls.incrementAndGet();
            return new SpeechRecognitionPort.Result(
                    List.of(new SpeechRecognitionPort.TranscriptSegment(
                            request.sliceStartMs(), request.sliceEndMs(), "integration lesson")),
                    2_000, Optional.of("provider-trace-1"));
        };
        DerivedMediaStore objects = new LocalDerivedMediaStore();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var service = new AsrExecutionApplicationService(
                    store, objects, speech, new ObjectMapper(), executor, temporaryDirectory,
                    "SiliconFlow", "TeleAI/TeleSpeechASR", 3,
                    Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);

            assertThat(service.transcribe(owner(), jobId, mediaId))
                    .isEqualTo(AsrExecutionApplicationService.AsrOutcome.COMPLETED);
            assertThat(service.transcribe(owner(), jobId, mediaId))
                    .isEqualTo(AsrExecutionApplicationService.AsrOutcome.REUSED);
        }

        assertThat(providerCalls).hasValue(1);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM model_call WHERE job_id = :jobId")
                .param("jobId", jobId.toString()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                SELECT CONCAT(status, '|', input_units, '|', output_units, '|', price_status, '|', provider_trace_id)
                FROM model_call WHERE job_id = :jobId
                """).param("jobId", jobId.toString()).query(String.class).single())
                .isEqualTo("SUCCEEDED|2500|18|UNAVAILABLE|provider-trace-1");
        assertThat(jdbcClient.sql("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(checkpoint_payload, '$.text'))
                FROM context_work_item
                WHERE media_id = :mediaId AND kind = 'ASR_SLICE'
                """).param("mediaId", mediaId.toString()).query(String.class).single())
                .isEqualTo("integration lesson");
    }

    @Test
    void unknownOutcomeIsAuditedAndNotAutomaticallyCalledAgain() {
        AtomicInteger providerCalls = new AtomicInteger();
        SpeechRecognitionPort speech = request -> {
            providerCalls.incrementAndGet();
            throw new SpeechRecognitionException(
                    SpeechRecognitionException.Reason.UNKNOWN_OUTCOME, "TIMEOUT");
        };

        try (var executor = Executors.newSingleThreadExecutor()) {
            var service = new AsrExecutionApplicationService(
                    store, new LocalDerivedMediaStore(), speech, new ObjectMapper(), executor, temporaryDirectory,
                    "SiliconFlow", "TeleAI/TeleSpeechASR", 3,
                    Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);

            assertThat(service.transcribe(owner(), jobId, mediaId))
                    .isEqualTo(AsrExecutionApplicationService.AsrOutcome.REVIEW_REQUIRED);
            assertThat(service.transcribe(owner(), jobId, mediaId))
                    .isEqualTo(AsrExecutionApplicationService.AsrOutcome.REVIEW_REQUIRED);
        }

        assertThat(providerCalls).hasValue(1);
        assertThat(jdbcClient.sql("SELECT status FROM model_call WHERE job_id = :jobId")
                .param("jobId", jobId.toString()).query(String.class).single())
                .isEqualTo("UNKNOWN_OUTCOME");
        assertThat(jdbcClient.sql("""
                SELECT status FROM context_work_item
                WHERE media_id = :mediaId AND kind = 'ASR_SLICE'
                """).param("mediaId", mediaId.toString()).query(String.class).single())
                .isEqualTo("UNKNOWN");
    }

    @Test
    void staleStartedCallBecomesUnknownInsteadOfCallingProviderAgain() {
        String fingerprint = ContextInputFingerprint.sha256(new ContextWorkSpec(
                "d".repeat(64), "asr-v2", "trusted-slice-window-v1", "TeleAI/TeleSpeechASR",
                ContextWorkItemKind.ASR_SLICE, 60_000, 62_500,
                Map.of("sourceFingerprint", "c".repeat(64))));
        UUID workItemId = UUID.randomUUID();
        UUID modelCallId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO context_work_item (
                    work_item_id, media_id, created_by_job_id, kind, ordinal,
                    start_ms, end_ms, input_fingerprint, status, attempt, version, updated_at
                ) VALUES (
                    :workItemId, :mediaId, :jobId, 'ASR_SLICE', 0,
                    60000, 62500, :fingerprint, 'STARTED', 1, 1, :updatedAt
                )
                """)
                .param("workItemId", workItemId.toString())
                .param("mediaId", mediaId.toString())
                .param("jobId", jobId.toString())
                .param("fingerprint", fingerprint)
                .param("updatedAt", NOW.minusSeconds(600))
                .update();
        jdbcClient.sql("""
                INSERT INTO model_call (
                    model_call_id, job_id, request_id, operation_type, provider,
                    model_name, model_version, input_fingerprint, status, attempt,
                    input_units, price_status, started_at
                ) VALUES (
                    :modelCallId, :jobId, 'trace-stale', 'ASR', 'SiliconFlow',
                    'TeleAI/TeleSpeechASR', 'TeleAI/TeleSpeechASR', :fingerprint, 'STARTED', 1,
                    2500, 'UNAVAILABLE', :startedAt
                )
                """)
                .param("modelCallId", modelCallId.toString())
                .param("jobId", jobId.toString())
                .param("fingerprint", fingerprint)
                .param("startedAt", NOW.minusSeconds(600))
                .update();
        AtomicInteger providerCalls = new AtomicInteger();
        SpeechRecognitionPort speech = request -> {
            providerCalls.incrementAndGet();
            throw new AssertionError("stale ASR must not be called again");
        };

        try (var executor = Executors.newSingleThreadExecutor()) {
            var service = new AsrExecutionApplicationService(
                    store, new LocalDerivedMediaStore(), speech, new ObjectMapper(), executor, temporaryDirectory,
                    "SiliconFlow", "TeleAI/TeleSpeechASR", 3,
                    Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);
            assertThat(service.transcribe(owner(), jobId, mediaId))
                    .isEqualTo(AsrExecutionApplicationService.AsrOutcome.REVIEW_REQUIRED);
        }

        assertThat(providerCalls).hasValue(0);
        assertThat(jdbcClient.sql("SELECT status FROM model_call WHERE model_call_id = :modelCallId")
                .param("modelCallId", modelCallId.toString()).query(String.class).single())
                .isEqualTo("UNKNOWN_OUTCOME");
        assertThat(jdbcClient.sql("SELECT status FROM context_work_item WHERE work_item_id = :workItemId")
                .param("workItemId", workItemId.toString()).query(String.class).single())
                .isEqualTo("UNKNOWN");
    }

    private OwnerContext owner() {
        return new OwnerContext(new OwnerId(ownerId));
    }

    private final class LocalDerivedMediaStore implements DerivedMediaStore {
        @Override
        public void put(String objectKey, Path source, String contentType, String sha256) {
            assertThat(source).isRegularFile();
        }

        @Override
        public void copyTo(String objectKey, Path target) throws IOException {
            Files.write(target, new byte[] {1, 2, 3});
        }
    }
}
