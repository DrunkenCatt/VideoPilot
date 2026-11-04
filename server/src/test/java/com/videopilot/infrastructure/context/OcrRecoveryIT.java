package com.videopilot.infrastructure.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.analysis.port.OcrException;
import com.videopilot.analysis.port.OcrPort;
import com.videopilot.context.application.OcrExecutionApplicationService;
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
class OcrRecoveryIT {

    private static final Instant NOW = Instant.parse("2026-11-04T11:00:00Z");
    private final UUID ownerId = UUID.randomUUID();
    private final UUID mediaId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @Autowired
    VideoContextStore store;

    @Autowired
    JdbcClient jdbcClient;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("""
                INSERT INTO media_asset (
                    media_id, owner_id, media_fingerprint, object_key,
                    original_filename, media_status, duration_ms
                ) VALUES (:mediaId, :ownerId, REPEAT('a', 64), :objectKey, 'lesson.mp4', 'READY', 3000)
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
                    :workItemId, :mediaId, :jobId, 'KEYFRAME', 0,
                    1500, 1501, REPEAT('c', 64), 'COMPLETED', 1,
                    JSON_OBJECT('timestampMs', 1500), :artifactRef, REPEAT('d', 64), :completedAt
                )
                """)
                .param("workItemId", UUID.randomUUID().toString())
                .param("mediaId", mediaId.toString())
                .param("jobId", jobId.toString())
                .param("artifactRef", "derived/" + mediaId + "/frame.jpg")
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
    void completedFrameIsNotCalledAgainAndRecordsLocalZeroCost() {
        AtomicInteger calls = new AtomicInteger();
        OcrPort ocr = ocr(request -> {
            calls.incrementAndGet();
            return new OcrPort.Result(
                    List.of(new OcrPort.TextRegion("slide", 97.0, 1, 2, 30, 10)), 307_200);
        });

        try (var executor = Executors.newSingleThreadExecutor()) {
            var service = service(ocr, executor);
            assertThat(service.recognize(owner(), jobId, mediaId))
                    .isEqualTo(OcrExecutionApplicationService.OcrOutcome.COMPLETED);
            assertThat(service.recognize(owner(), jobId, mediaId))
                    .isEqualTo(OcrExecutionApplicationService.OcrOutcome.REUSED);
        }

        assertThat(calls).hasValue(1);
        assertThat(jdbcClient.sql("""
                SELECT CONCAT(status, '|', input_units, '|', output_units, '|',
                              estimated_cost, '|', currency, '|', price_status)
                FROM model_call WHERE job_id = :jobId AND operation_type = 'OCR'
                """).param("jobId", jobId.toString()).query(String.class).single())
                .isEqualTo("SUCCEEDED|307200|5|0.00000000|USD|AVAILABLE");
    }

    @Test
    void permanentFrameFailurePersistsDegradationAndDoesNotRunAgain() {
        AtomicInteger calls = new AtomicInteger();
        OcrPort ocr = ocr(request -> {
            calls.incrementAndGet();
            throw new OcrException(OcrException.Reason.NON_RETRYABLE, "INVALID_IMAGE");
        });

        try (var executor = Executors.newSingleThreadExecutor()) {
            var service = service(ocr, executor);
            assertThat(service.recognize(owner(), jobId, mediaId))
                    .isEqualTo(OcrExecutionApplicationService.OcrOutcome.DEGRADED);
            assertThat(service.recognize(owner(), jobId, mediaId))
                    .isEqualTo(OcrExecutionApplicationService.OcrOutcome.DEGRADED);
        }

        assertThat(calls).hasValue(1);
        assertThat(jdbcClient.sql("""
                SELECT CONCAT(status, '|', retryable, '|', error_code)
                FROM context_work_item WHERE media_id = :mediaId AND kind = 'OCR_FRAME'
                """).param("mediaId", mediaId.toString()).query(String.class).single())
                .isEqualTo("FAILED|0|INVALID_IMAGE");
        assertThat(jdbcClient.sql("""
                SELECT status FROM model_call
                WHERE job_id = :jobId AND operation_type = 'OCR'
                """).param("jobId", jobId.toString()).query(String.class).single())
                .isEqualTo("FAILED");
    }

    private OcrExecutionApplicationService service(
            OcrPort ocr, java.util.concurrent.ExecutorService executor) {
        return new OcrExecutionApplicationService(
                store, new LocalObjects(), ocr, new ObjectMapper(), executor, temporaryDirectory,
                "Tesseract", "eng", 2, Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);
    }

    private OcrPort ocr(java.util.function.Function<OcrPort.Request, OcrPort.Result> function) {
        return new OcrPort() {
            @Override
            public Result recognize(Request request) {
                return function.apply(request);
            }

            @Override
            public EngineIdentity identity() {
                return new EngineIdentity("tesseract 5.5.1", "e".repeat(64));
            }
        };
    }

    private OwnerContext owner() {
        return new OwnerContext(new OwnerId(ownerId));
    }

    private final class LocalObjects implements DerivedMediaStore {
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
