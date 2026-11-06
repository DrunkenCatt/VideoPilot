package com.videopilot.evaluation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import com.videopilot.retrieval.domain.EvidenceHit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetrievalBenchmarkRunnerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsFrozenJsonlAndReportsProductionRankingsByGroup() throws Exception {
        UUID exactSegment = UUID.randomUUID();
        UUID fuzzySegment = UUID.randomUUID();
        UUID ocrSegment = UUID.randomUUID();
        Path input = temporaryDirectory.resolve("retrieval-cases.jsonl");
        Files.writeString(input, String.join("\n",
                json("exact", "where is redis", 0, 60_000, List.of("asr"), exactSegment),
                json("fuzzy", "explain redis", 0, 120_000, List.of("asr"), fuzzySegment),
                json("ocr", "what is on screen", 0, 60_000, List.of("ocr", "keyframe"), ocrSegment)));
        RetrievalApplicationService retrieval = mock(RetrievalApplicationService.class);
        when(retrieval.retrieve(any(), any(), eq("where is redis"), eq(3)))
                .thenReturn(result(hit(UUID.randomUUID()), hit(exactSegment)));
        when(retrieval.retrieve(any(), any(), eq("explain redis"), eq(3)))
                .thenReturn(result(hit(fuzzySegment)));
        when(retrieval.retrieve(any(), any(), eq("what is on screen"), eq(3)))
                .thenReturn(result(hit(UUID.randomUUID())));
        RetrievalBenchmarkRunner runner = new RetrievalBenchmarkRunner(retrieval, new ObjectMapper());

        var report = runner.run(
                new OwnerContext(new OwnerId(UUID.randomUUID())), UUID.randomUUID(), runner.load(input), "abc1234");

        assertThat(report.totalCases()).isEqualTo(3);
        assertThat(report.scoredCases()).isEqualTo(3);
        assertThat(report.recallAt3()).isEqualTo(2.0 / 3.0);
        assertThat(report.hitAt1()).isEqualTo(1.0 / 3.0);
        assertThat(report.mrrAt3()).isEqualTo(0.5);
        assertThat(report.groups()).containsKeys("exact", "fuzzy", "ocr");
        assertThat(report.failures()).singleElement().extracting("caseId").isEqualTo("ocr");
        assertThat(report.formulaVersion()).isEqualTo(RetrievalApplicationService.FORMULA_VERSION);
        assertThat(report.commit()).isEqualTo("abc1234");
    }

    @Test
    void excludesApprovedUnanswerableCasesFromRecallDenominator() throws Exception {
        UUID segment = UUID.randomUUID();
        Path input = temporaryDirectory.resolve("unanswerable.jsonl");
        String value = json("unanswerable", "not covered", 0, 60_000, List.of("asr"), segment)
                .replace("\"answerability\":\"answerable\"", "\"answerability\":\"unanswerable\"");
        Files.writeString(input, value);
        RetrievalApplicationService retrieval = mock(RetrievalApplicationService.class);
        when(retrieval.retrieve(any(), any(), eq("not covered"), eq(3))).thenReturn(result());
        RetrievalBenchmarkRunner runner = new RetrievalBenchmarkRunner(retrieval, new ObjectMapper());

        var report = runner.run(
                new OwnerContext(new OwnerId(UUID.randomUUID())), UUID.randomUUID(), runner.load(input), "abc1234");

        assertThat(report.totalCases()).isOne();
        assertThat(report.scoredCases()).isZero();
        assertThat(report.recallAt3()).isZero();
        assertThat(report.failures()).isEmpty();
    }

    @Test
    void rejectsEmptyRunsAndInvalidJsonl() throws Exception {
        RetrievalBenchmarkRunner runner = new RetrievalBenchmarkRunner(
                mock(RetrievalApplicationService.class), new ObjectMapper());
        assertThatThrownBy(() -> runner.run(
                new OwnerContext(new OwnerId(UUID.randomUUID())), UUID.randomUUID(), List.of(), "abc"))
                .isInstanceOf(IllegalArgumentException.class);
        Path invalid = temporaryDirectory.resolve("invalid.jsonl");
        Files.writeString(invalid, "{\"caseId\":\"missing-fields\"}");
        assertThatThrownBy(() -> runner.load(invalid)).isInstanceOf(IllegalArgumentException.class);
    }

    private String json(
            String id, String question, long start, long end, List<String> channels, UUID segmentId)
            throws Exception {
        return new ObjectMapper().writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("caseId", id), java.util.Map.entry("dataVersion", "0.1.0"),
                java.util.Map.entry("split", "dev"), java.util.Map.entry("sourceRights", "licensed"),
                java.util.Map.entry("question", question),
                java.util.Map.entry("timeWindow", java.util.Map.of("startMs", start, "endMs", end)),
                java.util.Map.entry("evidenceChannels", channels), java.util.Map.entry("requiredFacts", List.of("fact")),
                java.util.Map.entry("forbiddenClaims", List.of()), java.util.Map.entry("answerability", "answerable"),
                java.util.Map.entry("expectedHits", List.of(java.util.Map.of(
                        "segmentId", segmentId.toString(), "startMs", start, "endMs", end, "channel", channels.getFirst()))),
                java.util.Map.entry("review", java.util.Map.of("status", "approved", "reviewerCount", 1))));
    }

    private RetrievalApplicationService.RetrievalResult result(EvidenceHit... hits) {
        return new RetrievalApplicationService.RetrievalResult(
                "query", RetrievalApplicationService.FORMULA_VERSION, "embedding-v1", "chunk-v1",
                List.of(), List.of(hits));
    }

    private EvidenceHit hit(UUID segmentId) {
        return new EvidenceHit(UUID.randomUUID(), List.of(segmentId), 0, 60_000, "evidence",
                List.of(EvidenceHit.Channel.ASR), new EvidenceHit.Scores(1, 1, .6, 0, 0, 0, 0, 0, 0, .6));
    }
}
