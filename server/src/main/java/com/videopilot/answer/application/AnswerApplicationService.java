package com.videopilot.answer.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.analysis.application.ExecutionBudgetExceededException;
import com.videopilot.analysis.application.ExecutionBudgetPolicy;
import com.videopilot.answer.port.AnswerStore;
import com.videopilot.answer.port.LanguageModelException;
import com.videopilot.answer.port.LanguageModelPort;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import com.videopilot.retrieval.application.RetrievalException;
import com.videopilot.retrieval.domain.EvidenceHit;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class AnswerApplicationService {

    public static final String PROMPT_VERSION = "answer-evidence-v2";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final int TOP_K = 5;
    private static final int MAX_PROMPT_EVIDENCE = 12;
    private static final int MAX_PROMPT_EVIDENCE_CHARACTERS = 900;

    private final RetrievalApplicationService retrieval;
    private final LanguageModelPort model;
    private final AnswerStore store;
    private final ObjectMapper json;
    private final String provider;
    private final String modelVersion;
    private final String promptVersion;
    private final double minimumEvidenceScore;
    private final Supplier<Instant> now;
    private final Supplier<UUID> ids;
    private final AnswerEvidenceGate gate = new AnswerEvidenceGate(8, 600, 4, 300);
    private final ExecutionBudgetPolicy budget;

    public AnswerApplicationService(
            RetrievalApplicationService retrieval,
            LanguageModelPort model,
            AnswerStore store,
            ObjectMapper json,
            String provider,
            String modelVersion,
            String promptVersion,
            double minimumEvidenceScore,
            Supplier<Instant> now,
            Supplier<UUID> ids) {
        this(retrieval, model, store, json, provider, modelVersion, promptVersion,
                minimumEvidenceScore, now, ids, null);
    }

    public AnswerApplicationService(
            RetrievalApplicationService retrieval,
            LanguageModelPort model,
            AnswerStore store,
            ObjectMapper json,
            String provider,
            String modelVersion,
            String promptVersion,
            double minimumEvidenceScore,
            Supplier<Instant> now,
            Supplier<UUID> ids,
            ExecutionBudgetPolicy budget) {
        this.retrieval = retrieval;
        this.model = model;
        this.store = store;
        this.json = json;
        this.provider = requireText(provider);
        this.modelVersion = requireText(modelVersion);
        this.promptVersion = requireText(promptVersion);
        if (!Double.isFinite(minimumEvidenceScore) || minimumEvidenceScore < 0 || minimumEvidenceScore > 1) {
            throw new IllegalArgumentException("minimum evidence score must be between zero and one");
        }
        this.minimumEvidenceScore = minimumEvidenceScore;
        this.now = now;
        this.ids = ids;
        this.budget = budget;
    }

    public AnswerStore.StoredAnswer ask(
            OwnerContext owner, UUID jobId, String question, String idempotencyKey) {
        return ask(owner, jobId, question, idempotencyKey, null, null, null);
    }

    public AnswerStore.StoredAnswer ask(
            OwnerContext owner, UUID jobId, String question, String idempotencyKey,
            UUID executionRunId) {
        return ask(owner, jobId, question, idempotencyKey, null, null, executionRunId);
    }

    public AnswerStore.StoredAnswer ask(
            OwnerContext owner, UUID jobId, String question, String idempotencyKey,
            UUID evaluationRunId, String evaluationCaseId) {
        return ask(owner, jobId, question, idempotencyKey, evaluationRunId, evaluationCaseId, null);
    }

    private AnswerStore.StoredAnswer ask(
            OwnerContext owner, UUID jobId, String question, String idempotencyKey,
            UUID evaluationRunId, String evaluationCaseId, UUID executionRunId) {
        String normalizedQuestion = normalizeQuestion(question);
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new AnswerException(AnswerException.Reason.INVALID_REQUEST);
        }
        if (!store.ownsJob(owner.ownerId(), jobId)) {
            throw new AnswerException(AnswerException.Reason.NOT_FOUND);
        }
        String fingerprint = sha256(jobId + "|" + normalizedQuestion + "|" + promptVersion + "|" + modelVersion);
        UUID answerId = ids.get();
        AnswerStore.ClaimResult claim = store.claim(owner.ownerId(), new AnswerStore.ClaimRequest(
                answerId, jobId, idempotencyKey, fingerprint, normalizedQuestion,
                promptVersion, modelVersion, now.get()));
        if (claim.fingerprintConflict()) throw new AnswerException(AnswerException.Reason.IDEMPOTENCY_KEY_REUSED);
        if (!claim.created()) {
            if (claim.answer() == null) throw new AnswerException(AnswerException.Reason.NOT_READY);
            return existing(claim.answer());
        }

        RetrievalApplicationService.RetrievalResult result;
        try {
            result = executionRunId != null
                    ? retrieval.retrieve(owner, jobId, normalizedQuestion, TOP_K, executionRunId)
                    : evaluationRunId == null
                            ? retrieval.retrieve(owner, jobId, normalizedQuestion, TOP_K)
                            : retrieval.retrieve(
                                    owner, jobId, normalizedQuestion, TOP_K,
                                    evaluationRunId, evaluationCaseId);
        } catch (ExecutionBudgetExceededException exception) {
            store.fail(answerId, null, "FAILED", "BUDGET_EXHAUSTED",
                    exception.limitVersion() + ':' + exception.reason().name(), now.get());
            throw new AnswerException(AnswerException.Reason.BUDGET_EXHAUSTED, exception);
        } catch (RetrievalException exception) {
            store.fail(answerId, null, "FAILED", "RETRIEVAL", exception.reason().name(), now.get());
            throw switch (exception.reason()) {
                case NOT_FOUND -> new AnswerException(AnswerException.Reason.NOT_FOUND, exception);
                case NOT_READY -> new AnswerException(AnswerException.Reason.NOT_READY, exception);
                case INVALID_REQUEST -> new AnswerException(AnswerException.Reason.INVALID_REQUEST, exception);
            };
        }
        List<String> degraded = result.degradedChannels().stream().map(Enum::name).toList();
        if (result.hits().isEmpty()) {
            return refuse(owner, answerId, result, degraded, "NO_SUFFICIENT_EVIDENCE");
        }
        if (result.hits().getFirst().scores().total() < minimumEvidenceScore) {
            return refuse(owner, answerId, result, degraded, "EVIDENCE_BELOW_THRESHOLD");
        }

        Set<UUID> chunks = result.hits().stream().map(EvidenceHit::chunkId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> segments = result.hits().stream().flatMap(hit -> hit.segmentIds().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<AnswerEvidenceGate.EvidenceSource> evidence = store.loadEvidence(
                owner.ownerId(), jobId, chunks, segments);
        if (evidence.stream().noneMatch(AnswerEvidenceGate.EvidenceSource::selectable)) {
            return refuse(owner, answerId, result, degraded, "NO_SUFFICIENT_EVIDENCE");
        }

        List<AnswerEvidenceGate.EvidenceSource> exposed = evidence.stream()
                .filter(AnswerEvidenceGate.EvidenceSource::selectable)
                .limit(MAX_PROMPT_EVIDENCE)
                .toList();
        Set<String> exposedIds = exposed.stream().map(AnswerEvidenceGate.EvidenceSource::evidenceId)
                .collect(Collectors.toUnmodifiableSet());
        List<AnswerEvidenceGate.EvidenceSource> validationEvidence = evidence.stream()
                .map(source -> new AnswerEvidenceGate.EvidenceSource(
                        source.evidenceId(), source.chunkId(), source.segmentId(), source.ordinal(),
                        source.startMs(), source.endMs(), source.channel(), source.text(),
                        exposedIds.contains(source.evidenceId())))
                .toList();
        String prompt = prompt(normalizedQuestion, exposed);
        try {
            if (budget != null) {
                if (evaluationRunId == null) {
                    budget.beforeInteractiveCall(jobId, "ANSWER", 0);
                } else {
                    budget.beforeInteractiveCall(jobId, "ANSWER", 0, evaluationRunId);
                }
            }
        } catch (ExecutionBudgetExceededException exception) {
            store.fail(answerId, null, "FAILED", "BUDGET_EXHAUSTED",
                    exception.limitVersion() + ':' + exception.reason().name(), now.get());
            throw new AnswerException(AnswerException.Reason.BUDGET_EXHAUSTED, exception);
        }
        UUID callId = ids.get();
        String callFingerprint = sha256(fingerprint + "|" + answerId);
        Instant started = now.get();
        store.startModelCall(new AnswerStore.ModelCallStart(
                callId, answerId, jobId, ids.get().toString(), provider, modelVersion,
                promptVersion, callFingerprint, executionRunId,
                evaluationRunId, evaluationCaseId, started));
        LanguageModelPort.Response response;
        try {
            response = model.generate(new LanguageModelPort.Request(prompt, callFingerprint));
        } catch (LanguageModelException exception) {
            Instant completed = now.get();
            String status = exception.reason() == LanguageModelException.Reason.UNKNOWN_OUTCOME
                    ? "UNKNOWN_OUTCOME" : "FAILED";
            store.fail(answerId, callId, status, exception.reason().name(), exception.code(), completed);
            throw new AnswerException(AnswerException.Reason.DEPENDENCY_UNAVAILABLE, exception);
        }

        try {
            if (budget != null) {
                if (evaluationRunId == null) {
                    budget.afterInteractiveCall(jobId, "ANSWER", response.usage().inputTokens(),
                            response.usage().outputTokens(), BigDecimal.ZERO);
                } else {
                    budget.afterInteractiveCall(jobId, "ANSWER", response.usage().inputTokens(),
                            response.usage().outputTokens(), BigDecimal.ZERO, evaluationRunId);
                }
            }
        } catch (ExecutionBudgetExceededException exception) {
            store.fail(answerId, callId, "FAILED", "BUDGET_EXHAUSTED",
                    exception.limitVersion() + ':' + exception.reason().name(), now.get());
            throw new AnswerException(AnswerException.Reason.BUDGET_EXHAUSTED, exception);
        }

        AnswerEvidenceGate.ValidationResult validation;
        try {
            CandidateJson candidate = json.readValue(response.candidateText(), CandidateJson.class);
            validation = gate.validate(candidate.toCandidate(), validationEvidence);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            validation = AnswerEvidenceGate.ValidationResult.rejected("CITATION_VALIDATION_FAILED");
        }
        Instant completed = now.get();
        String status = validation.accepted() ? "ANSWERED" : "REFUSED";
        return store.publish(owner.ownerId(), new AnswerStore.Publication(
                answerId, callId, status, validation.failure(), degraded,
                result.embeddingVersion(), result.formulaVersion(), result.chunkVersion(),
                response.usage().inputTokens(), response.usage().outputTokens(),
                Math.max(0, Duration.between(started, completed).toMillis()),
                validation.claims(), completed));
    }

    public AnswerStore.StoredAnswer get(OwnerContext owner, UUID jobId, UUID answerId) {
        return store.find(owner.ownerId(), jobId, answerId)
                .orElseThrow(() -> new AnswerException(AnswerException.Reason.NOT_FOUND));
    }

    public List<AnswerStore.StoredAnswer> list(OwnerContext owner, UUID jobId, Long cursor, int pageSize) {
        if (pageSize < 1 || pageSize > 100 || cursor != null && cursor < 1) {
            throw new AnswerException(AnswerException.Reason.INVALID_REQUEST);
        }
        if (!store.ownsJob(owner.ownerId(), jobId)) {
            throw new AnswerException(AnswerException.Reason.NOT_FOUND);
        }
        return store.list(owner.ownerId(), jobId, cursor, pageSize);
    }

    private AnswerStore.StoredAnswer existing(AnswerStore.StoredAnswer answer) {
        if (answer == null) throw new AnswerException(AnswerException.Reason.IN_PROGRESS);
        return switch (answer.status()) {
            case "ANSWERED", "REFUSED" -> answer;
            case "PROCESSING" -> throw new AnswerException(AnswerException.Reason.IN_PROGRESS);
            case "FAILED" -> {
                if (answer.refusalReason() != null && answer.refusalReason().startsWith("BUDGET_EXHAUSTED:")) {
                    throw new AnswerException(AnswerException.Reason.BUDGET_EXHAUSTED);
                }
                throw new AnswerException(AnswerException.Reason.DEPENDENCY_UNAVAILABLE);
            }
            default -> throw new AnswerException(AnswerException.Reason.DEPENDENCY_UNAVAILABLE);
        };
    }

    private AnswerStore.StoredAnswer refuse(
            OwnerContext owner,
            UUID answerId,
            RetrievalApplicationService.RetrievalResult result,
            List<String> degraded,
            String reason) {
        return store.publish(owner.ownerId(), new AnswerStore.Publication(
                answerId, null, "REFUSED", reason, degraded,
                result.embeddingVersion(), result.formulaVersion(), result.chunkVersion(),
                0, 0, 0, List.of(), now.get()));
    }

    private String prompt(String question, List<AnswerEvidenceGate.EvidenceSource> evidence) {
        List<PromptEvidence> selectable = evidence.stream()
                .map(source -> new PromptEvidence(source.evidenceId(), source.startMs(), source.endMs(),
                        source.channel(), source.text().substring(
                                0, Math.min(source.text().length(), MAX_PROMPT_EVIDENCE_CHARACTERS))))
                .toList();
        try {
            return "Answer every part of the question using only the evidence. Return exactly one JSON object "
                    + "{answerable:boolean,claims:[{text:string,citations:[{evidenceId:string,quote:string}]}]}. "
                    + "Use one atomic fact per claim. Copy evidenceId exactly from EVIDENCE. Each quote must be a "
                    + "contiguous verbatim substring of the text for that evidenceId; never paraphrase a quote. "
                    + "Every claim must be directly supported by its quotes. Maximum 8 claims and 4 citations per "
                    + "claim. If the evidence cannot support every requested part, return answerable=false and "
                    + "claims=[].\nQUESTION:\n"
                    + question + "\nEVIDENCE:\n" + json.writeValueAsString(selectable);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("answer prompt could not be encoded", exception);
        }
    }

    private String normalizeQuestion(String question) {
        if (question == null) throw new AnswerException(AnswerException.Reason.INVALID_REQUEST);
        String value = question.replaceAll("\\s+", " ").trim();
        if (value.isEmpty() || value.length() > 1_000) {
            throw new AnswerException(AnswerException.Reason.INVALID_REQUEST);
        }
        return value;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private String requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("answer version is required");
        return value;
    }

    private record PromptEvidence(String evidenceId, long startMs, long endMs, String channel, String text) { }

    private record CandidateJson(boolean answerable, List<ClaimJson> claims) {
        CandidateJson { claims = claims == null ? List.of() : List.copyOf(claims); }

        AnswerEvidenceGate.Candidate toCandidate() {
            return new AnswerEvidenceGate.Candidate(answerable, claims.stream().map(ClaimJson::toClaim).toList());
        }
    }

    private record ClaimJson(String text, List<CitationJson> citations) {
        ClaimJson { citations = citations == null ? List.of() : List.copyOf(citations); }

        AnswerEvidenceGate.CandidateClaim toClaim() {
            return new AnswerEvidenceGate.CandidateClaim(text, citations.stream().map(CitationJson::toCitation).toList());
        }
    }

    private record CitationJson(String evidenceId, String quote) {
        AnswerEvidenceGate.CandidateCitation toCitation() {
            return new AnswerEvidenceGate.CandidateCitation(evidenceId, quote);
        }
    }
}
