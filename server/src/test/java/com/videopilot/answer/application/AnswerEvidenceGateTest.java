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
    void alignsAContinuousQuoteAcrossOneAdjacentSegmentBoundary() {
        var declared = evidence(
                SEGMENT, 6, 360_000, 420_000,
                "忘了没关系，我只要会查是不是就ok了。最关键的就是要学会通过查询的方式。", true);
        var neighbor = evidence(
                NEIGHBOR, 7, 420_000, 434_747,
                "自己去使用啊，那我们接下来按照顺序逐个学习每一组命令。", false);
        var candidate = new AnswerEvidenceGate.Candidate(true, List.of(
                new AnswerEvidenceGate.CandidateClaim("最关键的是学会查询并自己使用", List.of(
                        new AnswerEvidenceGate.CandidateCitation(
                                SEGMENT + "#ASR", "最关键的就是要学会通过查询的方式自己去使用啊")))));

        var result = new AnswerEvidenceGate(8, 600, 4, 300).validate(candidate, List.of(declared, neighbor));

        assertThat(result.accepted()).isTrue();
        assertThat(result.claims().getFirst().citations())
                .extracting(AnswerEvidenceGate.VerifiedCitation::segmentId)
                .containsExactly(SEGMENT, NEIGHBOR);
    }

    @Test
    void acceptsChineseClaimsSupportedByRealBenchmarkQuotes() {
        var website = evidence(
                SEGMENT, 4, 240_000, 300_000,
                "学习命令行肯定要去查看帮助文档。这些帮助文档可以在官方网站看到，"
                        + "所有命令可以根据组做分类和过滤。", true);
        var commandLine = evidence(
                NEIGHBOR, 5, 300_000, 360_000,
                "除了官方网站以外，在命令行也能看到这些帮助文档。通过一个help命令获取帮助，"
                        + "圈圈后面跟上group代表组，比如help generic代表通用的。", true);
        List<AnswerEvidenceGate.CandidateClaim> claims = List.of(
                claim("查询 Redis 命令帮助文档的方法包括在官方网站上查看。", SEGMENT,
                        "这些帮助文档可以在官方网站看到"),
                claim("查询 Redis 命令帮助文档的方法包括在命令行中查看。", NEIGHBOR,
                        "在命令行也能看到这些帮助文档"),
                claim("命令行中使用 help 命令可以获取帮助。", NEIGHBOR,
                        "通过一个help命令获取帮助"),
                claim("命令行中可以使用 help @group 格式获取某组命令的帮助。", NEIGHBOR,
                        "圈圈后面跟上group代表组"),
                claim("官方网站提供了命令的分组过滤功能。", SEGMENT,
                        "所有命令可以根据组做分类和过滤"));

        for (AnswerEvidenceGate.CandidateClaim claim : claims) {
            var result = new AnswerEvidenceGate(8, 600, 4, 300)
                    .validate(new AnswerEvidenceGate.Candidate(true, List.of(claim)), List.of(website, commandLine));
            assertThat(result.accepted()).as(claim.text()).isTrue();
        }
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

    private AnswerEvidenceGate.CandidateClaim claim(String text, UUID segmentId, String quote) {
        return new AnswerEvidenceGate.CandidateClaim(
                text, List.of(new AnswerEvidenceGate.CandidateCitation(segmentId + "#ASR", quote)));
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
