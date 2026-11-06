package com.videopilot.infrastructure.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.answer.application.AnswerApplicationService;
import com.videopilot.answer.application.AnswerException;
import com.videopilot.answer.port.AnswerStore;
import com.videopilot.evaluation.application.RetrievalBenchmarkRunner;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import com.videopilot.retrieval.application.VideoIndexApplicationService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
    @Autowired AnswerApplicationService answers;

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
        jdbc.sql("UPDATE analysis_job SET status = 'READY' WHERE job_id = :jobId")
                .param("jobId", JOB_ID.toString()).update();

        RetrievalBenchmarkRunner runner = new RetrievalBenchmarkRunner(retrieval, json);
        Path cases = Path.of("..", "benchmark", "local", "m3-p08", "retrieval-cases.jsonl")
                .toAbsolutePath().normalize();
        String commit = System.getProperty("benchmark.commit");
        assertThat(commit).as("-Dbenchmark.commit must identify the candidate").isNotBlank();
        var report = runner.run(owner, JOB_ID, runner.load(cases), commit);
        Path output = Path.of("..", "benchmark", "reports", "m3-t3-baseline.json")
                .toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        assertThat(report.totalCases()).isEqualTo(12);

        AnswerReport answerReport = runAnswers(owner, commit);
        Path answerOutput = Path.of("..", "benchmark", "reports", "m3-t6-answer-baseline.json")
                .toAbsolutePath().normalize();
        json.writerWithDefaultPrettyPrinter().writeValue(answerOutput.toFile(), answerReport);
        assertThat(answerReport.totalCases()).isEqualTo(6);
        assertThat(answerReport.localizedCitationCount()).isEqualTo(answerReport.citationCount());
    }

    private AnswerReport runAnswers(OwnerContext owner, String commit) throws Exception {
        Path cases = Path.of("..", "benchmark", "local", "m3-p08", "answer-cases.jsonl")
                .toAbsolutePath().normalize();
        List<AnswerOutcome> outcomes = new ArrayList<>();
        int citations = 0;
        int localized = 0;
        try (var lines = Files.lines(cases)) {
            for (String line : lines.filter(value -> !value.isBlank()).toList()) {
                JsonNode seed = json.readTree(line);
                String caseId = seed.path("caseId").asText();
                String expected = seed.path("answerability").asText();
                AnswerStore.StoredAnswer answer;
                try {
                    answer = answers.ask(owner, JOB_ID, seed.path("question").asText(), "benchmark-" + caseId);
                } catch (AnswerException exception) {
                    outcomes.add(new AnswerOutcome(
                            caseId, expected, "ERROR_" + exception.reason(), false,
                            0, 0, 0, exception.reason().name(), List.of()));
                    continue;
                }
                int caseCitations = answer.claims().stream().mapToInt(claim -> claim.citations().size()).sum();
                int caseLocalized = 0;
                for (AnswerStore.StoredClaim claim : answer.claims()) {
                    for (AnswerStore.StoredCitation citation : claim.citations()) {
                        if (localized(seed, citation)) caseLocalized++;
                    }
                }
                citations += caseCitations;
                localized += caseLocalized;
                String expectedStatus = "answerable".equals(expected) ? "ANSWERED" : "REFUSED";
                outcomes.add(new AnswerOutcome(
                        caseId, expected, answer.status(), expectedStatus.equals(answer.status()),
                        answer.claims().size(), caseCitations, caseLocalized,
                        answer.refusalReason(), answer.degradedChannels()));
            }
        }
        long matches = outcomes.stream().filter(AnswerOutcome::expectedStatusMatch).count();
        return new AnswerReport(
                outcomes.size(), matches, citations, localized,
                citations == 0 ? null : localized / (double) citations,
                "A citation is localized when its persisted window is valid and overlaps a reviewed requiredCitation window or its one-segment alignment neighborhood.",
                "Single 7-minute Chinese tutorial, dev split, one reviewer; no semantic fact-coverage score is inferred.",
                commit, Instant.now(), List.copyOf(outcomes));
    }

    private boolean localized(JsonNode seed, AnswerStore.StoredCitation citation) {
        if (citation.startMs() < 0 || citation.endMs() <= citation.startMs() || citation.endMs() > 434_747) {
            return false;
        }
        for (JsonNode expected : seed.withArray("requiredCitations")) {
            long expectedStart = Math.max(0, expected.path("startMs").asLong() - 60_000);
            long expectedEnd = Math.min(434_747, expected.path("endMs").asLong() + 60_000);
            if (citation.startMs() < expectedEnd && citation.endMs() > expectedStart) return true;
        }
        return false;
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

    private record AnswerOutcome(
            String caseId,
            String expectedAnswerability,
            String actualStatus,
            boolean expectedStatusMatch,
            int claimCount,
            int citationCount,
            int localizedCitationCount,
            String refusalReason,
            List<String> degradedChannels) {
        private AnswerOutcome { degradedChannels = List.copyOf(degradedChannels); }
    }

    private record AnswerReport(
            int totalCases,
            long expectedStatusMatches,
            int citationCount,
            int localizedCitationCount,
            Double citationLocalizationRate,
            String localizationRule,
            String limitations,
            String commit,
            Instant createdAt,
            List<AnswerOutcome> cases) {
        private AnswerReport { cases = List.copyOf(cases); }
    }
}
