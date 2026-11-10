package com.videopilot.evaluation.application;

import static com.videopilot.evaluation.domain.EvaluationModels.CaseOutcome.COMPLETED;
import static com.videopilot.evaluation.domain.EvaluationModels.CaseOutcome.REFUSED;
import static com.videopilot.evaluation.domain.EvaluationModels.CaseType.ANSWER;
import static com.videopilot.evaluation.domain.EvaluationModels.CaseType.RETRIEVAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videopilot.answer.application.AnswerApplicationService;
import com.videopilot.answer.port.AnswerStore;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationCase;
import com.videopilot.evaluation.domain.EvaluationModels.AnswerScoring;
import com.videopilot.evaluation.domain.EvaluationModels.Answerability;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationRun;
import com.videopilot.evaluation.domain.EvaluationModels.ExecutionVersions;
import com.videopilot.evaluation.domain.EvaluationModels.RunStatus;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import com.videopilot.retrieval.domain.EvidenceHit;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationBaselineRunnerTest {

    private static final Instant NOW = Instant.parse("2026-11-08T01:00:00Z");
    private static final OwnerContext OWNER = new OwnerContext(new OwnerId(UUID.randomUUID()));

    @Test
    void usesProductionRetrievalAndRecordsExpectedSegmentRank() {
        RetrievalApplicationService retrieval = mock(RetrievalApplicationService.class);
        AnswerApplicationService answers = mock(AnswerApplicationService.class);
        UUID expected = UUID.randomUUID();
        EvaluationCase evaluationCase = evaluationCase(RETRIEVAL, List.of(expected));
        when(retrieval.retrieve(
                org.mockito.ArgumentMatchers.eq(OWNER),
                org.mockito.ArgumentMatchers.eq(evaluationCase.jobId()),
                org.mockito.ArgumentMatchers.eq(evaluationCase.question()),
                org.mockito.ArgumentMatchers.eq(3),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.eq(evaluationCase.caseId())))
                .thenReturn(new RetrievalApplicationService.RetrievalResult(
                        evaluationCase.question(), "formula-v1", "embedding-v1", "chunk-v1", List.of(),
                        List.of(hit(UUID.randomUUID()), hit(expected))));

        var result = new EvaluationBaselineRunner(retrieval, answers, () -> NOW)
                .run(OWNER, run(), evaluationCase);

        assertThat(result.outcome()).isEqualTo(COMPLETED);
        assertThat(result.rank()).isEqualTo(2);
        assertThat(result.estimatedCost()).isNull();
    }

    @Test
    void usesProductionAnswerAndKeepsRefusalAsAnExplicitOutcome() {
        RetrievalApplicationService retrieval = mock(RetrievalApplicationService.class);
        AnswerApplicationService answers = mock(AnswerApplicationService.class);
        EvaluationCase evaluationCase = evaluationCase(ANSWER, List.of());
        when(answers.ask(
                org.mockito.ArgumentMatchers.eq(OWNER),
                org.mockito.ArgumentMatchers.eq(evaluationCase.jobId()),
                org.mockito.ArgumentMatchers.eq(evaluationCase.question()),
                org.mockito.ArgumentMatchers.startsWith("eval:"),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.eq(evaluationCase.caseId())))
                .thenReturn(new AnswerStore.StoredAnswer(
                        1, UUID.randomUUID(), "REFUSED", evaluationCase.question(),
                        "NO_SUFFICIENT_EVIDENCE", List.of(), "prompt-v1", "model-v1", "embedding-v1",
                        "formula-v1", "chunk-v1", List.of(), NOW));

        var result = new EvaluationBaselineRunner(retrieval, answers, () -> NOW)
                .run(OWNER, run(), evaluationCase);

        assertThat(result.outcome()).isEqualTo(REFUSED);
        assertThat(result.claimCount()).isZero();
        assertThat(result.citationCount()).isZero();
    }

    @Test
    void turnsObservedVersionDriftIntoAnExplicitFailedCase() {
        RetrievalApplicationService retrieval = mock(RetrievalApplicationService.class);
        AnswerApplicationService answers = mock(AnswerApplicationService.class);
        EvaluationCase evaluationCase = evaluationCase(RETRIEVAL, List.of(UUID.randomUUID()));
        when(retrieval.retrieve(
                org.mockito.ArgumentMatchers.eq(OWNER),
                org.mockito.ArgumentMatchers.eq(evaluationCase.jobId()),
                org.mockito.ArgumentMatchers.eq(evaluationCase.question()),
                org.mockito.ArgumentMatchers.eq(3),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.eq(evaluationCase.caseId())))
                .thenReturn(new RetrievalApplicationService.RetrievalResult(
                        evaluationCase.question(), "formula-v2", "embedding-v1", "chunk-v1",
                        List.of(), List.of()));

        var result = new EvaluationBaselineRunner(retrieval, answers, () -> NOW)
                .run(OWNER, run(), evaluationCase);

        assertThat(result.outcome()).isEqualTo(com.videopilot.evaluation.domain.EvaluationModels.CaseOutcome.ERROR);
        assertThat(result.errorCode()).isEqualTo("VERSION_DRIFT");
    }

    @Test
    void countsOnlyClaimsWhoseFrozenFactsChannelsAndCitationSegmentsAreSupported() {
        RetrievalApplicationService retrieval = mock(RetrievalApplicationService.class);
        AnswerApplicationService answers = mock(AnswerApplicationService.class);
        UUID expected = UUID.randomUUID();
        EvaluationCase evaluationCase = new EvaluationCase(
                "answer-supported", 1, ANSWER, UUID.randomUUID(), "question", List.of(expected),
                Answerability.ANSWERABLE,
                new AnswerScoring(List.of("String 是对象"), List.of("一定线程安全"), List.of("ASR")),
                null, "APPROVED", 2, "a".repeat(64));
        var supported = new AnswerStore.StoredClaim(UUID.randomUUID(), "String 是对象", List.of(
                new AnswerStore.StoredCitation(
                        UUID.randomUUID(), expected, 0, 1_000, "ASR", "String 是对象", "EXACT")));
        var forbidden = new AnswerStore.StoredClaim(UUID.randomUUID(), "它一定线程安全", List.of(
                new AnswerStore.StoredCitation(
                        UUID.randomUUID(), expected, 0, 1_000, "ASR", "String 是对象", "EXACT")));
        when(answers.ask(
                org.mockito.ArgumentMatchers.eq(OWNER),
                org.mockito.ArgumentMatchers.eq(evaluationCase.jobId()),
                org.mockito.ArgumentMatchers.eq(evaluationCase.question()),
                org.mockito.ArgumentMatchers.startsWith("eval:"),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.eq(evaluationCase.caseId())))
                .thenReturn(new AnswerStore.StoredAnswer(
                        1, UUID.randomUUID(), "ANSWERED", evaluationCase.question(), null, List.of(),
                        "prompt-v1", "model-v1", "embedding-v1", "formula-v1", "chunk-v1",
                        List.of(supported, forbidden), NOW));

        var result = new EvaluationBaselineRunner(retrieval, answers, () -> NOW)
                .run(OWNER, run(), evaluationCase);

        assertThat(result.claimCount()).isEqualTo(2);
        assertThat(result.supportedClaimCount()).isEqualTo(1);
        assertThat(result.localizedCitationCount()).isEqualTo(2);
        assertThat(result.answerId()).isNotNull();
    }

    @Test
    void scoresRequiredFactsAcrossTheWholeAnswerInsteadOfRequiringEveryFactInEveryClaim() {
        RetrievalApplicationService retrieval = mock(RetrievalApplicationService.class);
        AnswerApplicationService answers = mock(AnswerApplicationService.class);
        UUID expected = UUID.randomUUID();
        EvaluationCase evaluationCase = new EvaluationCase(
                "answer-multiple-facts", 1, ANSWER, UUID.randomUUID(), "question", List.of(expected),
                Answerability.ANSWERABLE,
                new AnswerScoring(List.of("第一项事实", "第二项事实"), List.of(), List.of("ASR")),
                null, "APPROVED", 2, "a".repeat(64));
        var first = new AnswerStore.StoredClaim(UUID.randomUUID(), "第一项事实", List.of(
                new AnswerStore.StoredCitation(
                        UUID.randomUUID(), expected, 0, 1_000, "ASR", "第一项事实", "EXACT")));
        var second = new AnswerStore.StoredClaim(UUID.randomUUID(), "第二项事实", List.of(
                new AnswerStore.StoredCitation(
                        UUID.randomUUID(), expected, 1_000, 2_000, "ASR", "第二项事实", "EXACT")));
        when(answers.ask(
                org.mockito.ArgumentMatchers.eq(OWNER),
                org.mockito.ArgumentMatchers.eq(evaluationCase.jobId()),
                org.mockito.ArgumentMatchers.eq(evaluationCase.question()),
                org.mockito.ArgumentMatchers.startsWith("eval:"),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.eq(evaluationCase.caseId())))
                .thenReturn(new AnswerStore.StoredAnswer(
                        1, UUID.randomUUID(), "ANSWERED", evaluationCase.question(), null, List.of(),
                        "prompt-v1", "model-v1", "embedding-v1", "formula-v1", "chunk-v1",
                        List.of(first, second), NOW));

        var result = new EvaluationBaselineRunner(retrieval, answers, () -> NOW)
                .run(OWNER, run(), evaluationCase);

        assertThat(result.claimCount()).isEqualTo(2);
        assertThat(result.supportedClaimCount()).isEqualTo(2);
    }

    @Test
    void creditsDeterministicParaphrasesWhenAnchorsAndFactContentArePreserved() {
        RetrievalApplicationService retrieval = mock(RetrievalApplicationService.class);
        AnswerApplicationService answers = mock(AnswerApplicationService.class);
        UUID expected = UUID.randomUUID();
        EvaluationCase evaluationCase = new EvaluationCase(
                "answer-paraphrase", 1, ANSWER, UUID.randomUUID(), "question", List.of(expected),
                Answerability.ANSWERABLE,
                new AnswerScoring(
                        List.of("教程中的示例书名是《信仰之旅》。", "列出书的性质时，首先点击“添加陈述”。"),
                        List.of(), List.of("ASR")),
                null, "APPROVED", 2, "a".repeat(64));
        var title = new AnswerStore.StoredClaim(UUID.randomUUID(), "教程用《信仰之旅》这本书作为示例。", List.of(
                new AnswerStore.StoredCitation(UUID.randomUUID(), expected, 0, 1_000, "ASR",
                        "这个书名呢，是信仰之旅。", "EXACT")));
        var action = new AnswerStore.StoredClaim(UUID.randomUUID(), "第一步点击“添加陈述”。", List.of(
                new AnswerStore.StoredCitation(UUID.randomUUID(), expected, 1_000, 2_000, "ASR",
                        "首先，我们来点击添加陈述，这个属性栏里面就有性质。", "EXACT")));
        when(answers.ask(
                org.mockito.ArgumentMatchers.eq(OWNER),
                org.mockito.ArgumentMatchers.eq(evaluationCase.jobId()),
                org.mockito.ArgumentMatchers.eq(evaluationCase.question()),
                org.mockito.ArgumentMatchers.startsWith("eval:"),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.eq(evaluationCase.caseId())))
                .thenReturn(new AnswerStore.StoredAnswer(
                        1, UUID.randomUUID(), "ANSWERED", evaluationCase.question(), null, List.of(),
                        "prompt-v1", "model-v1", "embedding-v1", "formula-v1", "chunk-v1",
                        List.of(title, action), NOW));

        var result = new EvaluationBaselineRunner(retrieval, answers, () -> NOW)
                .run(OWNER, run(), evaluationCase);

        assertThat(result.supportedClaimCount()).isEqualTo(2);
    }

    @Test
    void doesNotCreditTopicallyRelatedTextThatOmitsTheFrozenAnchor() {
        RetrievalApplicationService retrieval = mock(RetrievalApplicationService.class);
        AnswerApplicationService answers = mock(AnswerApplicationService.class);
        UUID expected = UUID.randomUUID();
        EvaluationCase evaluationCase = new EvaluationCase(
                "answer-missing-anchor", 1, ANSWER, UUID.randomUUID(), "question", List.of(expected),
                Answerability.ANSWERABLE,
                new AnswerScoring(List.of("教程中的示例书名是《信仰之旅》。"), List.of(), List.of("ASR")),
                null, "APPROVED", 2, "a".repeat(64));
        var wrongBook = new AnswerStore.StoredClaim(UUID.randomUUID(), "教程使用一本书作为示例。", List.of(
                new AnswerStore.StoredCitation(UUID.randomUUID(), expected, 0, 1_000, "ASR",
                        "这里展示了教程中的示例书名。", "EXACT")));
        when(answers.ask(
                org.mockito.ArgumentMatchers.eq(OWNER),
                org.mockito.ArgumentMatchers.eq(evaluationCase.jobId()),
                org.mockito.ArgumentMatchers.eq(evaluationCase.question()),
                org.mockito.ArgumentMatchers.startsWith("eval:"),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.eq(evaluationCase.caseId())))
                .thenReturn(new AnswerStore.StoredAnswer(
                        1, UUID.randomUUID(), "ANSWERED", evaluationCase.question(), null, List.of(),
                        "prompt-v1", "model-v1", "embedding-v1", "formula-v1", "chunk-v1",
                        List.of(wrongBook), NOW));

        var result = new EvaluationBaselineRunner(retrieval, answers, () -> NOW)
                .run(OWNER, run(), evaluationCase);

        assertThat(result.supportedClaimCount()).isZero();
    }

    @Test
    void doesNotCreditClaimsWhenTheAnswerOmitsAnyRequiredFact() {
        RetrievalApplicationService retrieval = mock(RetrievalApplicationService.class);
        AnswerApplicationService answers = mock(AnswerApplicationService.class);
        UUID expected = UUID.randomUUID();
        EvaluationCase evaluationCase = new EvaluationCase(
                "answer-incomplete", 1, ANSWER, UUID.randomUUID(), "question", List.of(expected),
                Answerability.ANSWERABLE,
                new AnswerScoring(List.of("已有事实", "遗漏事实"), List.of(), List.of("ASR")),
                null, "APPROVED", 2, "a".repeat(64));
        var partial = new AnswerStore.StoredClaim(UUID.randomUUID(), "已有事实", List.of(
                new AnswerStore.StoredCitation(
                        UUID.randomUUID(), expected, 0, 1_000, "ASR", "已有事实", "EXACT")));
        when(answers.ask(
                org.mockito.ArgumentMatchers.eq(OWNER),
                org.mockito.ArgumentMatchers.eq(evaluationCase.jobId()),
                org.mockito.ArgumentMatchers.eq(evaluationCase.question()),
                org.mockito.ArgumentMatchers.startsWith("eval:"),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.eq(evaluationCase.caseId())))
                .thenReturn(new AnswerStore.StoredAnswer(
                        1, UUID.randomUUID(), "ANSWERED", evaluationCase.question(), null, List.of(),
                        "prompt-v1", "model-v1", "embedding-v1", "formula-v1", "chunk-v1",
                        List.of(partial), NOW));

        var result = new EvaluationBaselineRunner(retrieval, answers, () -> NOW)
                .run(OWNER, run(), evaluationCase);

        assertThat(result.claimCount()).isEqualTo(1);
        assertThat(result.supportedClaimCount()).isZero();
    }

    private EvaluationCase evaluationCase(
            com.videopilot.evaluation.domain.EvaluationModels.CaseType type,
            List<UUID> expected) {
        return new EvaluationCase(
                "case-1", 1, type, UUID.randomUUID(), "question", expected,
                "APPROVED", 1, "a".repeat(64));
    }

    private EvaluationRun run() {
        return new EvaluationRun(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "b".repeat(64), "request-1",
                "c".repeat(64), "d".repeat(64), new ExecutionVersions(
                        "commit", "pipeline", "prompt-v1", "model-v1", "embedding-v1", "chunk-v1",
                        "formula-v1", "gate", "limits", "config"),
                RunStatus.RUNNING, 1, 1, 0, 1, null, NOW, null);
    }

    private EvidenceHit hit(UUID segmentId) {
        return new EvidenceHit(
                UUID.randomUUID(), List.of(segmentId), 0, 1_000, "excerpt", List.of(EvidenceHit.Channel.ASR),
                new EvidenceHit.Scores(1, 1, 0.6, 1, 1, 0.25, 0, 0, 0, 0.85));
    }
}
