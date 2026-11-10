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
import java.util.List;
import java.util.Locale;
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
                case RELIABILITY -> result(run, evaluationCase,
                        evaluationCase.reliability() != null && evaluationCase.reliability().passed()
                                ? CaseOutcome.COMPLETED : CaseOutcome.ERROR,
                        null, 0, 0, 0, 0, null, startedAt,
                        evaluationCase.reliability() != null && evaluationCase.reliability().passed()
                                ? null : "RELIABILITY_FACT_FAILED");
            };
        } catch (RetrievalException exception) {
            return result(run, evaluationCase, CaseOutcome.ERROR, null, 0, 0,
                    0, 0, null, startedAt, "RETRIEVAL_" + exception.reason().name());
        } catch (AnswerException exception) {
            CaseOutcome outcome = exception.reason() == AnswerException.Reason.DEPENDENCY_UNAVAILABLE
                    ? CaseOutcome.UNKNOWN : CaseOutcome.ERROR;
            return result(run, evaluationCase, outcome, null, 0, 0,
                    0, 0, null, startedAt, "ANSWER_" + exception.reason().name());
        }
    }

    private CaseResult retrieval(
            EvaluationRun run,
            EvaluationCase evaluationCase,
            OwnerContext owner,
            Instant startedAt) {
        var response = retrieval.retrieve(
                owner, evaluationCase.jobId(), evaluationCase.question(), 3,
                run.runId(), evaluationCase.caseId());
        if (!run.versions().retrievalFormulaVersion().equals(response.formulaVersion())
                || !run.versions().embeddingVersion().equals(response.embeddingVersion())
                || !run.versions().chunkVersion().equals(response.chunkVersion())) {
            return result(run, evaluationCase, CaseOutcome.ERROR, null, 0, 0, 0, 0,
                    null, startedAt, "VERSION_DRIFT");
        }
        Set<java.util.UUID> expected = new HashSet<>(evaluationCase.expectedSegmentIds());
        Integer rank = null;
        for (int index = 0; index < response.hits().size(); index++) {
            if (response.hits().get(index).segmentIds().stream().anyMatch(expected::contains)) {
                rank = index + 1;
                break;
            }
        }
        return result(run, evaluationCase, CaseOutcome.COMPLETED, rank, 0, 0, 0, 0,
                null, startedAt, null);
    }

    private CaseResult answer(
            EvaluationRun run,
            EvaluationCase evaluationCase,
            OwnerContext owner,
            Instant startedAt) {
        String idempotencyKey = "eval:" + run.runId() + ':' + evaluationCase.fingerprint().substring(0, 16);
        AnswerStore.StoredAnswer answer = answers.ask(
                owner, evaluationCase.jobId(), evaluationCase.question(), idempotencyKey,
                run.runId(), evaluationCase.caseId());
        if (!run.versions().promptVersion().equals(answer.promptVersion())
                || !run.versions().modelVersion().equals(answer.modelVersion())
                || !run.versions().embeddingVersion().equals(answer.embeddingVersion())
                || !run.versions().retrievalFormulaVersion().equals(answer.retrievalFormulaVersion())
                || !run.versions().chunkVersion().equals(answer.chunkVersion())) {
            return result(run, evaluationCase, CaseOutcome.ERROR, null, 0, 0, 0, 0,
                    answer.answerId(), startedAt, "VERSION_DRIFT");
        }
        int citations = answer.claims().stream().mapToInt(claim -> claim.citations().size()).sum();
        int localized = answer.claims().stream().mapToInt(claim -> (int) claim.citations().stream()
                .filter(citation -> !evaluationCase.expectedSegmentIds().isEmpty()
                        && evaluationCase.expectedSegmentIds().contains(citation.segmentId()))
                .count()).sum();
        int supported = supportedClaims(answer.claims(), evaluationCase);
        CaseOutcome outcome = "ANSWERED".equals(answer.status())
                ? CaseOutcome.COMPLETED : CaseOutcome.REFUSED;
        return result(run, evaluationCase, outcome, null, answer.claims().size(), citations,
                supported, localized, answer.answerId(), startedAt, null);
    }

    private int supportedClaims(List<AnswerStore.StoredClaim> claims, EvaluationCase evaluationCase) {
        List<String> requiredFacts = evaluationCase.answerScoring().requiredFacts().stream()
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .toList();
        if (requiredFacts.isEmpty()) return 0;
        List<AnswerStore.StoredClaim> eligible = claims.stream()
                .filter(claim -> eligible(claim, evaluationCase))
                .toList();
        boolean complete = requiredFacts.stream()
                .allMatch(fact -> eligible.stream().anyMatch(claim -> evidence(claim).contains(fact)));
        if (!complete) return 0;
        return (int) eligible.stream()
                .filter(claim -> requiredFacts.stream().anyMatch(evidence(claim)::contains))
                .count();
    }

    private boolean eligible(AnswerStore.StoredClaim claim, EvaluationCase evaluationCase) {
        if (claim.citations().isEmpty()) return false;
        boolean forbidden = evaluationCase.answerScoring().forbiddenClaims().stream()
                .anyMatch(value -> normalize(claim.text()).contains(normalize(value)));
        boolean channels = evaluationCase.answerScoring().allowedChannels().isEmpty()
                || claim.citations().stream().allMatch(value -> evaluationCase.answerScoring()
                .allowedChannels().stream().anyMatch(channel -> channel.equalsIgnoreCase(value.channel())));
        boolean windows = !evaluationCase.expectedSegmentIds().isEmpty()
                && claim.citations().stream().allMatch(value ->
                evaluationCase.expectedSegmentIds().contains(value.segmentId()));
        return !forbidden && channels && windows;
    }

    private String evidence(AnswerStore.StoredClaim claim) {
        return normalize(claim.text() + " " + claim.citations().stream()
                .map(AnswerStore.StoredCitation::quote).collect(java.util.stream.Collectors.joining(" ")));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private CaseResult result(
            EvaluationRun run,
            EvaluationCase evaluationCase,
            CaseOutcome outcome,
            Integer rank,
            int claims,
            int citations,
            int supportedClaims,
            int localizedCitations,
            java.util.UUID answerId,
            Instant startedAt,
            String errorCode) {
        Instant completedAt = now.get();
        return new CaseResult(
                run.runId(), evaluationCase.caseId(), evaluationCase.type(), outcome, rank, claims, citations,
                supportedClaims, localizedCitations, answerId,
                Math.max(0, Duration.between(startedAt, completedAt).toMillis()), 0, null, errorCode, completedAt);
    }
}
