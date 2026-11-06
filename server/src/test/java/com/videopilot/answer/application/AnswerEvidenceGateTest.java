package com.videopilot.answer.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerEvidenceGateTest {

    private static final UUID CHUNK = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SEGMENT = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID NEIGHBOR = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Test
    void acceptsNormalizedQuoteFromDeclaredEvidence() {
        var evidence = evidence(SEGMENT, 0, 10_000, "Redis 的 String 可以存储文本。", true);
        var candidate = candidate(SEGMENT + "#ASR", "Redis 的 String可以存储文本");

        var result = new AnswerEvidenceGate(8, 600, 4, 300).validate(candidate, List.of(evidence));

        assertThat(result.accepted()).isTrue();
        assertThat(result.claims()).singleElement().satisfies(claim ->
                assertThat(claim.citations()).singleElement().satisfies(citation -> {
                    assertThat(citation.segmentId()).isEqualTo(SEGMENT);
                    assertThat(citation.alignmentType()).isEqualTo("NORMALIZED");
                }));
    }

    @Test
    void alignsOnlyWithinOneAdjacentSegment() {
        var declared = evidence(SEGMENT, 0, 0, 10_000, "先介绍列表。", true);
        var neighbor = evidence(NEIGHBOR, 1, 10_000, 20_000, "集合中的元素不会重复。", false);
        var candidate = new AnswerEvidenceGate.Candidate(true, List.of(
                new AnswerEvidenceGate.CandidateClaim("集合中的元素不会重复", List.of(
                        new AnswerEvidenceGate.CandidateCitation(
                                SEGMENT + "#ASR", "集合中的元素不会重复")))));

        var result = new AnswerEvidenceGate(8, 600, 4, 300).validate(candidate, List.of(declared, neighbor));

        assertThat(result.accepted()).isTrue();
        assertThat(result.claims().getFirst().citations().getFirst().segmentId()).isEqualTo(NEIGHBOR);
    }

    @Test
    void rejectsFabricatedEvidenceAndRejectsWholeAnswerWhenOneClaimFails() {
        var evidence = evidence(SEGMENT, 0, 10_000, "Redis String 可以存储文本。", true);
        var valid = new AnswerEvidenceGate.CandidateClaim("String 可保存文本", List.of(
                new AnswerEvidenceGate.CandidateCitation(SEGMENT + "#ASR", "Redis String 可以存储文本")));
        var fabricated = new AnswerEvidenceGate.CandidateClaim("Redis 永远不会丢数据", List.of(
                new AnswerEvidenceGate.CandidateCitation(UUID.randomUUID() + "#ASR", "永远不会丢数据")));

        var result = new AnswerEvidenceGate(8, 600, 4, 300).validate(
                new AnswerEvidenceGate.Candidate(true, List.of(valid, fabricated)), List.of(evidence));

        assertThat(result.accepted()).isFalse();
        assertThat(result.claims()).isEmpty();
        assertThat(result.failure()).isEqualTo("CITATION_VALIDATION_FAILED");
    }

    @Test
    void rejectsClaimsWithoutCitationsAndOversizedCandidate() {
        var noCitation = new AnswerEvidenceGate.Candidate(true, List.of(
                new AnswerEvidenceGate.CandidateClaim("unsupported", List.of())));
        var tooMany = new AnswerEvidenceGate.Candidate(true, java.util.stream.IntStream.range(0, 9)
                .mapToObj(index -> new AnswerEvidenceGate.CandidateClaim("claim " + index, List.of()))
                .toList());

        assertThat(new AnswerEvidenceGate(8, 600, 4, 300).validate(noCitation, List.of()).accepted()).isFalse();
        assertThat(new AnswerEvidenceGate(8, 600, 4, 300).validate(tooMany, List.of()).accepted()).isFalse();
    }

    @Test
    void rejectsARealQuoteBoundToAnUnrelatedClaim() {
        var evidence = evidence(SEGMENT, 0, 10_000, "Redis String 可以存储文本。", true);
        var candidate = new AnswerEvidenceGate.Candidate(true, List.of(
                new AnswerEvidenceGate.CandidateClaim("视频证明地球是圆的", List.of(
                        new AnswerEvidenceGate.CandidateCitation(
                                SEGMENT + "#ASR", "Redis String 可以存储文本")))));

        var result = new AnswerEvidenceGate(8, 600, 4, 300).validate(candidate, List.of(evidence));

        assertThat(result.accepted()).isFalse();
        assertThat(result.failure()).isEqualTo("CITATION_VALIDATION_FAILED");
    }

    private AnswerEvidenceGate.Candidate candidate(String evidenceId, String quote) {
        return new AnswerEvidenceGate.Candidate(true, List.of(new AnswerEvidenceGate.CandidateClaim(
                "Redis 数据结构说明", List.of(new AnswerEvidenceGate.CandidateCitation(evidenceId, quote)))));
    }

    private AnswerEvidenceGate.EvidenceSource evidence(
            UUID segmentId, long startMs, long endMs, String text, boolean selectable) {
        return evidence(segmentId, 0, startMs, endMs, text, selectable);
    }

    private AnswerEvidenceGate.EvidenceSource evidence(
            UUID segmentId, int ordinal, long startMs, long endMs, String text, boolean selectable) {
        return new AnswerEvidenceGate.EvidenceSource(
                segmentId + "#ASR", CHUNK, segmentId, ordinal, startMs, endMs, "ASR", text, selectable);
    }
}
