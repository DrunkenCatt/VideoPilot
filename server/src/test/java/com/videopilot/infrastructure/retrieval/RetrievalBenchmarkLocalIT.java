package com.videopilot.infrastructure.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.evaluation.application.RetrievalBenchmarkRunner;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import com.videopilot.retrieval.application.VideoIndexApplicationService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("integration")
class RetrievalBenchmarkLocalIT {

    private static final UUID OWNER_ID = UUID.fromString("91000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ID = UUID.fromString("b0a85c84-d1df-4ef8-8757-129f248cd274");
    private static final UUID JOB_ID = UUID.fromString("a1fcc4a1-aa5c-4635-9c45-39688aebc10d");
    private static final UUID CONTEXT_ID = UUID.fromString("ed7da2ab-86b3-438b-b85f-dd66b89ad217");

    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired VideoIndexApplicationService indexing;
    @Autowired RetrievalApplicationService retrieval;

    @Test
    void generatesLocalReviewedSeedBaseline() throws Exception {
        seedReviewedContext();
        OwnerContext owner = new OwnerContext(new OwnerId(OWNER_ID));
        String fingerprint = indexing.inputFingerprint(owner, JOB_ID);
        jdbc.sql("""
                INSERT INTO job_stage_attempt (
                    job_id, stage, input_fingerprint, checkpoint_schema_version, status
                ) VALUES (:jobId, 'INDEXING', :fingerprint, 1, 'STARTED')
                """).param("jobId", JOB_ID.toString()).param("fingerprint", fingerprint).update();
        assertThat(indexing.index(owner, JOB_ID)).isEqualTo(VideoIndexApplicationService.IndexOutcome.CREATED);

        RetrievalBenchmarkRunner runner = new RetrievalBenchmarkRunner(retrieval, json);
        Path cases = Path.of("..", "benchmark", "local", "m3-p08", "retrieval-cases.jsonl")
                .toAbsolutePath().normalize();
        var report = runner.run(owner, JOB_ID, runner.load(cases), "46f0ae7");
        Path output = Path.of("..", "benchmark", "reports", "m3-t3-baseline.json")
                .toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        assertThat(report.totalCases()).isEqualTo(12);
    }

    private void seedReviewedContext() throws Exception {
        jdbc.sql("""
                INSERT INTO media_asset (
                    media_id, owner_id, media_fingerprint, object_key, original_filename,
                    content_length, declared_content_type, media_status, duration_ms
                ) VALUES (
                    :mediaId, :ownerId, REPEAT('a', 64), 'benchmark/local/p08.mp4', 'p08.mp4',
                    17331783, 'video/mp4', 'READY', 434747
                )
                """).param("mediaId", MEDIA_ID.toString()).param("ownerId", OWNER_ID.toString()).update();
        jdbc.sql("""
                INSERT INTO analysis_job (
                    job_id, owner_id, media_id, analysis_key, idempotency_key, request_fingerprint,
                    goal, mode, pipeline_version, status, current_stage, version, event_sequence
                ) VALUES (
                    :jobId, :ownerId, :mediaId, 'm3-p08-baseline', 'm3-p08-baseline', REPEAT('b', 64),
                    'Redis data structures', 'STANDARD', 'm3-evidence-qa-v1', 'INDEXING', 'INDEXING', 5, 5
                )
                """).param("jobId", JOB_ID.toString()).param("ownerId", OWNER_ID.toString())
                .param("mediaId", MEDIA_ID.toString()).update();
        jdbc.sql("""
                INSERT INTO video_context (
                    context_id, source_job_id, media_id, input_fingerprint, pipeline_version,
                    extraction_version, alignment_version, duration_ms, status, asr_state,
                    ocr_state, segment_count, version, completed_at
                ) VALUES (
                    :contextId, :jobId, :mediaId, REPEAT('c', 64), 'm3-evidence-qa-v1',
                    'ffmpeg-extract-v1', 'unaligned-v1', 434747, 'READY', 'AVAILABLE', 'AVAILABLE', 8, 1, NOW(6)
                )
                """).param("contextId", CONTEXT_ID.toString()).param("jobId", JOB_ID.toString())
                .param("mediaId", MEDIA_ID.toString()).update();

        JsonNode root = json.readTree(Path.of("..", "benchmark", "local",
                "bilibili-BV1cr4y1671t-p08-segments.json").toFile());
        for (JsonNode segment : root.withArray("data")) {
            String asr = segment.path("asrText").asText();
            String ocr = json.writeValueAsString(segment.path("ocr"));
            String keyframes = json.writeValueAsString(segment.path("keyframes"));
            jdbc.sql("""
                    INSERT INTO video_segment (
                        segment_id, context_id, ordinal, start_ms, end_ms, asr_text,
                        ocr_observations, keyframes, content_digest
                    ) VALUES (
                        :segmentId, :contextId, :ordinal, :startMs, :endMs, :asr,
                        CAST(:ocr AS JSON), CAST(:keyframes AS JSON), :digest
                    )
                    """).param("segmentId", segment.path("segmentId").asText())
                    .param("contextId", CONTEXT_ID.toString()).param("ordinal", segment.path("ordinal").asInt())
                    .param("startMs", segment.path("startMs").asLong()).param("endMs", segment.path("endMs").asLong())
                    .param("asr", asr).param("ocr", ocr).param("keyframes", keyframes)
                    .param("digest", sha256(asr + ocr + keyframes)).update();
        }
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
