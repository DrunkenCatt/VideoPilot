package com.videopilot.evaluation.application;

import com.videopilot.answer.application.AnswerApplicationService;
import com.videopilot.answer.application.AnswerException;
import com.videopilot.answer.port.AnswerStore;
import com.videopilot.evaluation.domain.EvaluationModels.CaseOutcome;
import com.videopilot.evaluation.domain.EvaluationModels.CaseResult;
import com.videopilot.evaluation.domain.EvaluationModels.CaseType;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationCase;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationRun;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import com.videopilot.retrieval.application.RetrievalException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

public class EvaluationBaselineRunner {

    private final RetrievalApplicationService retrieval;
    private final AnswerApplicationService answers;
    private final Supplier<Instant> now;

    public EvaluationBaselineRunner(
            RetrievalApplicationService retrieval,
            AnswerApplicationService answers,
            Supplier<Instant> now) {
        this.retrieval = retrieval;
        this.answers = answers;
        this.now = now;
    }

    public CaseResult run(OwnerContext owner, EvaluationRun run, EvaluationCase evaluationCase) {
        Instant startedAt = now.get();
        try {
            return switch (evaluationCase.type()) {
                case RETRIEVAL -> retrieval(run, evaluationCase, owner, startedAt);
                case ANSWER -> answer(run, evaluationCase, owner, startedAt);
                case RELIABILITY -> result(run, evaluationCase, CaseOutcome.ERROR, null, 0, 0,
                        startedAt, "RELIABILITY_REQUIRES_FROZEN_M4_FACTS");
            };
        } catch (RetrievalException exception) {
            return result(run, evaluationCase, CaseOutcome.ERROR, null, 0, 0,
                    startedAt, "RETRIEVAL_" + exception.reason().name());
        } catch (AnswerException exception) {
            CaseOutcome outcome = exception.reason() == AnswerException.Reason.DEPENDENCY_UNAVAILABLE
                    ? CaseOutcome.UNKNOWN : CaseOutcome.ERROR;
            return result(run, evaluationCase, outcome, null, 0, 0,
                    startedAt, "ANSWER_" + exception.reason().name());
        }
    }

    private CaseResult retrieval(
            EvaluationRun run,
            EvaluationCase evaluationCase,
            OwnerContext owner,
            Instant startedAt) {
        var response = retrieval.retrieve(owner, evaluationCase.jobId(), evaluationCase.question(), 3);
        Set<java.util.UUID> expected = new HashSet<>(evaluationCase.expectedSegmentIds());
        Integer rank = null;
        for (int index = 0; index < response.hits().size(); index++) {
            if (response.hits().get(index).segmentIds().stream().anyMatch(expected::contains)) {
                rank = index + 1;
                break;
            }
        }
        return result(run, evaluationCase, CaseOutcome.COMPLETED, rank, 0, 0, startedAt, null);
    }

    private CaseResult answer(
            EvaluationRun run,
            EvaluationCase evaluationCase,
            OwnerContext owner,
            Instant startedAt) {
        String idempotencyKey = "eval:" + run.runId() + ':' + evaluationCase.fingerprint().substring(0, 16);
        AnswerStore.StoredAnswer answer = answers.ask(
                owner, evaluationCase.jobId(), evaluationCase.question(), idempotencyKey);
        int citations = answer.claims().stream().mapToInt(claim -> claim.citations().size()).sum();
        CaseOutcome outcome = "ANSWERED".equals(answer.status())
                ? CaseOutcome.COMPLETED : CaseOutcome.REFUSED;
        return result(run, evaluationCase, outcome, null, answer.claims().size(), citations, startedAt, null);
    }

    private CaseResult result(
            EvaluationRun run,
            EvaluationCase evaluationCase,
            CaseOutcome outcome,
            Integer rank,
            int claims,
            int citations,
            Instant startedAt,
            String errorCode) {
        Instant completedAt = now.get();
        return new CaseResult(
                run.runId(), evaluationCase.caseId(), evaluationCase.type(), outcome, rank, claims, citations,
                Math.max(0, Duration.between(startedAt, completedAt).toMillis()), 0, null, errorCode, completedAt);
    }
}
