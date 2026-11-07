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
        when(retrieval.retrieve(OWNER, evaluationCase.jobId(), evaluationCase.question(), 3))
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
                org.mockito.ArgumentMatchers.startsWith("eval:")))
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
                        "commit", "pipeline", "prompt", "model", "embedding", "chunk",
                        "formula", "gate", "limits", "config"),
                RunStatus.RUNNING, 1, 1, 0, 1, null, NOW, null);
    }

    private EvidenceHit hit(UUID segmentId) {
        return new EvidenceHit(
                UUID.randomUUID(), List.of(segmentId), 0, 1_000, "excerpt", List.of(EvidenceHit.Channel.ASR),
                new EvidenceHit.Scores(1, 1, 0.6, 1, 1, 0.25, 0, 0, 0, 0.85));
    }
}
