package com.videopilot.agent.application;

import com.videopilot.agent.domain.AgentModels.AgentBudget;
import com.videopilot.agent.domain.AgentModels.AgentRun;
import com.videopilot.agent.domain.AgentModels.AgentStep;
import com.videopilot.agent.domain.AgentModels.AgentTrace;
import com.videopilot.agent.domain.AgentModels.AgentVersions;
import com.videopilot.agent.domain.AgentModels.RunStatus;
import com.videopilot.agent.domain.AgentModels.ToolCall;
import com.videopilot.agent.domain.AgentModels.ToolCallStatus;
import com.videopilot.agent.port.AgentPolicy;
import com.videopilot.agent.port.AgentDecisionPort;
import com.videopilot.agent.port.AgentStore;
import com.videopilot.answer.application.AnswerApplicationService;
import com.videopilot.answer.port.AnswerStore;
import com.videopilot.answer.port.LanguageModelException;
import com.videopilot.context.application.VideoContextQueryApplicationService;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import com.videopilot.retrieval.application.VideoChunkBuilder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.Base64;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class AgentApplicationService {

    public static final String POLICY_VERSION = "agent-policy-v2-planner";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern ANSWER_REFERENCE = Pattern.compile(
            "answer:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int MAX_GOAL_LENGTH = 1_000;
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

    private final AgentStore store;
    private final AgentVersions versions;
    private final Supplier<AgentBudget> budgets;
    private final Supplier<Instant> now;
    private final Supplier<UUID> ids;
    private final AgentPolicy policy;
    private final AgentDecisionPort decisions;
    private final RetrievalApplicationService retrieval;
    private final VideoContextQueryApplicationService contexts;
    private final AnswerApplicationService answers;
    private final boolean goalAnalysisEnabled;

    public AgentApplicationService(
            AgentStore store,
            AgentVersions versions,
            Supplier<AgentBudget> budgets,
            Supplier<Instant> now,
            Supplier<UUID> ids) {
        this.store = store;
        this.versions = versions;
        this.budgets = budgets;
        this.now = now;
        this.ids = ids;
        this.policy = null;
        this.decisions = null;
        this.retrieval = null;
        this.contexts = null;
        this.answers = null;
        this.goalAnalysisEnabled = false;
    }

    public AgentApplicationService(
            AgentStore store,
            AgentVersions versions,
            Supplier<AgentBudget> budgets,
            Supplier<Instant> now,
            Supplier<UUID> ids,
            boolean goalAnalysisEnabled) {
        this.store = store;
        this.versions = versions;
        this.budgets = budgets;
        this.now = now;
        this.ids = ids;
        this.policy = null;
        this.decisions = null;
        this.retrieval = null;
        this.contexts = null;
        this.answers = null;
        this.goalAnalysisEnabled = goalAnalysisEnabled;
    }

    public AgentApplicationService(
            AgentStore store,
            AgentVersions versions,
            Supplier<AgentBudget> budgets,
            Supplier<Instant> now,
            Supplier<UUID> ids,
            AgentPolicy policy,
            RetrievalApplicationService retrieval,
            VideoContextQueryApplicationService contexts,
            AnswerApplicationService answers) {
        this(store, versions, budgets, now, ids, policy, null, retrieval, contexts, answers, false);
    }

    public AgentApplicationService(
            AgentStore store,
            AgentVersions versions,
            Supplier<AgentBudget> budgets,
            Supplier<Instant> now,
            Supplier<UUID> ids,
            AgentPolicy policy,
            RetrievalApplicationService retrieval,
            VideoContextQueryApplicationService contexts,
            AnswerApplicationService answers,
            boolean goalAnalysisEnabled) {
        this(store, versions, budgets, now, ids, policy, null, retrieval, contexts, answers,
                goalAnalysisEnabled);
    }

    public AgentApplicationService(
            AgentStore store,
            AgentVersions versions,
            Supplier<AgentBudget> budgets,
            Supplier<Instant> now,
            Supplier<UUID> ids,
            AgentPolicy policy,
            AgentDecisionPort decisions,
            RetrievalApplicationService retrieval,
            VideoContextQueryApplicationService contexts,
            AnswerApplicationService answers) {
        this(store, versions, budgets, now, ids, policy, decisions, retrieval, contexts, answers, false);
    }

    public AgentApplicationService(
            AgentStore store,
            AgentVersions versions,
            Supplier<AgentBudget> budgets,
            Supplier<Instant> now,
            Supplier<UUID> ids,
            AgentPolicy policy,
            AgentDecisionPort decisions,
            RetrievalApplicationService retrieval,
            VideoContextQueryApplicationService contexts,
            AnswerApplicationService answers,
            boolean goalAnalysisEnabled) {
        this.store = store;
        this.versions = versions;
        this.budgets = budgets;
        this.now = now;
        this.ids = ids;
        this.policy = policy;
        this.decisions = decisions;
        this.retrieval = retrieval;
        this.contexts = contexts;
        this.answers = answers;
        this.goalAnalysisEnabled = goalAnalysisEnabled;
    }

    public AgentRun create(OwnerContext owner, UUID jobId, String goal, String idempotencyKey) {
        return create(owner, jobId, goal, idempotencyKey, null, null);
    }

    public AgentRun createForEvaluation(
            OwnerContext owner, UUID jobId, String goal, String idempotencyKey,
            UUID evaluationRunId, String evaluationCaseId) {
        if (evaluationRunId == null || evaluationCaseId == null || evaluationCaseId.isBlank()) {
            throw new AgentException(AgentException.Reason.INVALID_REQUEST);
        }
        return create(owner, jobId, goal, idempotencyKey, evaluationRunId, evaluationCaseId);
    }

    private AgentRun create(
            OwnerContext owner, UUID jobId, String goal, String idempotencyKey,
            UUID evaluationRunId, String evaluationCaseId) {
        String normalizedGoal = normalizeGoal(goal);
        if (jobId == null || idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new AgentException(AgentException.Reason.INVALID_REQUEST);
        }
        if (!store.ownsReadyJob(owner.ownerId(), jobId)) {
            throw new AgentException(AgentException.Reason.NOT_FOUND);
        }
        AgentBudget budget = budgets.get();
        String requestFingerprint = sha256(jobId + "|" + normalizedGoal + "|"
                + evaluationRunId + "|" + evaluationCaseId + "|"
                + String.join("|", versions.values()) + "|"
                + budget.maxSteps() + "|" + budget.maxAnswerAttempts() + "|"
                + budget.maxInputTokens() + "|" + budget.maxOutputTokens() + "|"
                + budget.maxKnownCost());
        AgentRun requested = new AgentRun(
                ids.get(), owner.ownerId(), jobId, evaluationRunId, evaluationCaseId,
                idempotencyKey, requestFingerprint,
                normalizedGoal, RunStatus.REQUESTED, versions, budget,
                0, 0, 0, 0, BigDecimal.ZERO, 0, null, 0, now.get(), null);
        AgentStore.CreateResult result = store.create(owner.ownerId(), requested);
        if (result.fingerprintConflict()) {
            throw new AgentException(AgentException.Reason.IDEMPOTENCY_KEY_REUSED);
        }
        return result.run();
    }

    public AgentTrace get(OwnerContext owner, UUID runId) {
        if (runId == null) throw new AgentException(AgentException.Reason.INVALID_REQUEST);
        return store.findTrace(owner.ownerId(), runId)
                .orElseThrow(() -> new AgentException(AgentException.Reason.NOT_FOUND));
    }

    public boolean ensureNextGoalAnalysis() {
        if (!goalAnalysisEnabled) return false;
        var job = store.findNextGoalAnalysisJob().orElse(null);
        if (job == null) return false;
        create(new OwnerContext(job.ownerId()), job.jobId(), job.goal(), goalAnalysisKey(job.jobId()));
        return true;
    }

    public boolean goalAnalysisEnabled() {
        return goalAnalysisEnabled;
    }

    public GoalAnalysisResult goalAnalysisResult(OwnerContext owner, UUID jobId) {
        if (owner == null || jobId == null) throw new AgentException(AgentException.Reason.INVALID_REQUEST);
        var job = store.findGoalAnalysisJob(owner.ownerId(), jobId)
                .orElseThrow(() -> new AgentException(AgentException.Reason.NOT_FOUND));
        if (!job.ready()) throw new AgentException(AgentException.Reason.CONFLICT);
        if (!goalAnalysisEnabled) return new GoalAnalysisResult(GoalAnalysisState.DISABLED, null, null);
        AgentTrace trace = store.findTraceByIntent(owner.ownerId(), goalAnalysisKey(jobId)).orElse(null);
        if (trace == null) return new GoalAnalysisResult(GoalAnalysisState.PENDING, null, null);
        return switch (trace.run().status()) {
            case REQUESTED -> new GoalAnalysisResult(GoalAnalysisState.REQUESTED, trace, null);
            case RUNNING -> new GoalAnalysisResult(GoalAnalysisState.RUNNING, trace, null);
            case REFUSED -> new GoalAnalysisResult(GoalAnalysisState.REFUSED, trace, null);
            case FAILED -> new GoalAnalysisResult(GoalAnalysisState.FAILED, trace, null);
            case COMPLETED -> completedGoalAnalysis(owner, jobId, trace);
        };
    }

    private GoalAnalysisResult completedGoalAnalysis(OwnerContext owner, UUID jobId, AgentTrace trace) {
        AgentStep answerStep = trace.steps().stream()
                .filter(step -> "GENERATE_AND_VERIFY".equals(step.action()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AgentException(AgentException.Reason.CONFLICT));
        String reference = answerStep.resultReference();
        if (reference == null || !ANSWER_REFERENCE.matcher(reference).matches()) {
            throw new AgentException(AgentException.Reason.CONFLICT);
        }
        UUID answerId;
        try {
            answerId = UUID.fromString(reference.substring("answer:".length()));
        } catch (IllegalArgumentException exception) {
            throw new AgentException(AgentException.Reason.CONFLICT, exception);
        }
        AnswerStore.StoredAnswer answer = answers.get(owner, jobId, answerId);
        GoalAnalysisState state = "ANSWERED".equals(answer.status())
                ? GoalAnalysisState.ANSWERED : GoalAnalysisState.REFUSED;
        return new GoalAnalysisResult(state, trace, answer);
    }

    private String goalAnalysisKey(UUID jobId) {
        return "goal-analysis:" + jobId;
    }

    public boolean processNext() {
        return processClaim(null, null);
    }

    public boolean process(OwnerContext owner, UUID runId) {
        if (owner == null || runId == null) throw new AgentException(AgentException.Reason.INVALID_REQUEST);
        return processClaim(owner, runId);
    }

    private boolean processClaim(OwnerContext owner, UUID runId) {
        requireRuntime();
        Instant claimedAt = now.get();
        String leaseId = ids.get().toString();
        var claimed = owner == null
                ? store.claimNext(leaseId, claimedAt.plus(LEASE_DURATION), claimedAt)
                : store.claim(owner.ownerId(), runId, leaseId, claimedAt.plus(LEASE_DURATION), claimedAt);
        if (claimed.isEmpty()) return false;

        AgentRun run = claimed.orElseThrow().run();
        AgentTrace trace = store.findTrace(claimed.orElseThrow().ownerId(), run.runId())
                .orElseThrow(() -> new AgentException(AgentException.Reason.CONFLICT));
        long version = run.version();
        ToolCall interrupted = trace.toolCalls().stream()
                .filter(call -> call.status() == ToolCallStatus.RUNNING).findFirst().orElse(null);
        if (interrupted != null) {
            store.failToolCallAndTerminate(leaseId, run.runId(), version, interrupted.toolCallId(),
                    ToolCallStatus.UNKNOWN, "UNKNOWN_OUTCOME", now.get());
            return true;
        }

        AgentPolicy.Decision decision = policy.next(trace);
        String gateFailure = gateFailure(trace, decision);
        if (gateFailure != null) {
            store.terminate(leaseId, run.runId(), version, RunStatus.FAILED, gateFailure, now.get());
            return true;
        }
        if (isTerminal(decision.action())) {
            RunStatus status = decision.action() == AgentPolicy.Action.FINISH
                    ? RunStatus.COMPLETED : RunStatus.REFUSED;
            String reason = decision.terminationReason() == null
                    ? decision.action().name() : decision.terminationReason();
            store.terminate(leaseId, run.runId(), version, status, reason, now.get());
            return true;
        }

        int stepNumber = run.stepCount() + 1;
        UUID toolCallId = ids.get();
        String action = decision.action().name();
        String actionInput = actionInput(run, trace, decision.action());
        Instant startedAt = now.get();
        ToolCall intent = new ToolCall(
                toolCallId, run.runId(), stepNumber, action,
                run.runId() + ":" + stepNumber + ":" + action,
                sha256(action + "|" + actionInput + "|" + stepNumber),
                ToolCallStatus.RUNNING, parameterSummary(decision.action(), actionInput),
                null, null, 0, startedAt, null);
        long callVersion = store.beginToolCall(leaseId, run.runId(), version, intent);
        ActionResult result;
        try {
            result = execute(run, trace, decision.action());
        } catch (RuntimeException exception) {
            Instant completedAt = now.get();
            boolean unknown = hasUnknownOutcome(exception);
            store.failToolCallAndTerminate(leaseId, run.runId(), callVersion, toolCallId,
                    unknown ? ToolCallStatus.UNKNOWN : ToolCallStatus.FAILED,
                    unknown ? "UNKNOWN_OUTCOME" : "TOOL_EXECUTION_FAILED", completedAt);
            return true;
        }
        Instant completedAt = now.get();
        ToolCall completed = new ToolCall(
                toolCallId, run.runId(), stepNumber, action, intent.idempotencyKey(),
                intent.inputFingerprint(), ToolCallStatus.COMPLETED, intent.parameterSummary(),
                "{\"code\":\"" + jsonCode(result.observation()) + "\"}", result.reference(),
                Math.max(0, Duration.between(startedAt, completedAt).toMillis()),
                startedAt, completedAt);
        AgentStep step = new AgentStep(run.runId(), stepNumber, action,
                result.observation(), result.reference(), completedAt);
        store.completeToolCallAndStep(leaseId, run.runId(), callVersion, completed, step,
                decision.action() == AgentPolicy.Action.GENERATE_AND_VERIFY);
        return true;
    }

    private String gateFailure(AgentTrace trace, AgentPolicy.Decision decision) {
        AgentRun run = trace.run();
        if (decision.schemaVersion() != AgentPolicy.SCHEMA_VERSION) return "INVALID_ACTION_SCHEMA";
        if (!store.ownsReadyJob(run.ownerId(), run.jobId())) return "OWNER_OR_JOB_REJECTED";
        if (!now.get().isBefore(run.budget().deadlineAt())) return "DEADLINE_EXCEEDED";
        if (run.stepCount() >= run.budget().maxSteps()) return "STEP_BUDGET_EXHAUSTED";
        if (run.inputTokens() >= run.budget().maxInputTokens()) return "INPUT_TOKEN_BUDGET_EXHAUSTED";
        if (run.outputTokens() >= run.budget().maxOutputTokens()) return "OUTPUT_TOKEN_BUDGET_EXHAUSTED";
        if (run.knownCost().compareTo(run.budget().maxKnownCost()) >= 0) return "COST_BUDGET_EXHAUSTED";
        if (run.unknownCostCalls() > 0 && !isTerminal(decision.action())) return "UNKNOWN_COST";
        if (decision.action() == AgentPolicy.Action.GENERATE_AND_VERIFY
                && run.answerAttemptCount() >= run.budget().maxAnswerAttempts()) {
            return "ANSWER_ATTEMPT_BUDGET_EXHAUSTED";
        }
        if (decision.action() == AgentPolicy.Action.RETRIEVE) {
            try {
                plannedQuery(trace.steps());
            } catch (RuntimeException exception) {
                return "INVALID_ACTION_PARAMETERS";
            }
        }
        return null;
    }

    private ActionResult execute(AgentRun run, AgentTrace trace, AgentPolicy.Action action) {
        OwnerContext owner = new OwnerContext(run.ownerId());
        return switch (action) {
            case PLAN -> plan(run);
            case RETRIEVE -> retrieve(owner, run, trace.steps());
            case EXPAND_WINDOW -> expand(owner, run, trace.steps());
            case GENERATE_AND_VERIFY -> answer(owner, run, trace.steps());
            default -> throw new AgentException(AgentException.Reason.INVALID_REQUEST);
        };
    }

    private ActionResult plan(AgentRun run) {
        if (decisions == null) throw new IllegalStateException("agent decision model is not configured");
        String fingerprint = sha256(AgentDecisionPort.PLANNER_PROMPT_VERSION + "|" + run.goal());
        UUID modelCallId = ids.get();
        Instant startedAt = now.get();
        store.startDecisionModelCall(new AgentStore.DecisionModelCallStart(
                modelCallId, run.jobId(), run.runId(), run.evaluationRunId(), run.evaluationCaseId(),
                "agent-plan-" + run.runId() + "-" + (run.stepCount() + 1),
                "SiliconFlow", run.versions().modelVersion(),
                AgentDecisionPort.PLANNER_PROMPT_VERSION, fingerprint, startedAt));
        AgentDecisionPort.PlanResponse response;
        try {
            response = decisions.plan(new AgentDecisionPort.PlanRequest(
                    run.jobId(), run.runId(), run.evaluationRunId(), run.evaluationCaseId(),
                    run.goal(), run.versions().modelVersion(),
                    AgentDecisionPort.PLANNER_PROMPT_VERSION, fingerprint));
        } catch (RuntimeException exception) {
            Instant failedAt = now.get();
            boolean unknown = hasUnknownOutcome(exception);
            store.failDecisionModelCall(modelCallId,
                    unknown ? "UNKNOWN_OUTCOME" : "FAILED",
                    exception instanceof AgentDecisionPort.InvalidDecisionException
                            ? "INVALID_OUTPUT" : "PROVIDER",
                    exception instanceof AgentDecisionPort.InvalidDecisionException
                            ? "INVALID_DECISION" : "MODEL_CALL_FAILED",
                    failedAt);
            throw exception;
        }
        Instant completedAt = now.get();
        store.completeDecisionModelCall(new AgentStore.DecisionModelCallCompletion(
                modelCallId, run.versions().modelVersion(),
                response.usage().inputTokens(), response.usage().outputTokens(),
                Math.max(0, Duration.between(startedAt, completedAt).toMillis()),
                startedAt, completedAt));
        AgentDecisionPort.Plan plan = response.plan();
        String facts = Base64.getUrlEncoder().withoutPadding().encodeToString(
                String.join("\n", plan.expectedFacts()).getBytes(StandardCharsets.UTF_8));
        return new ActionResult("PLAN:" + plan.action() + ";facts:" + plan.expectedFacts().size()
                + ";expected:" + facts,
                planReference(plan));
    }

    private ActionResult retrieve(OwnerContext owner, AgentRun run, List<AgentStep> steps) {
        String query = plannedQuery(steps);
        var result = run.evaluationRunId() == null
                ? retrieval.retrieve(owner, run.jobId(), query, 5, run.runId())
                : retrieval.retrieve(owner, run.jobId(), query, 5,
                        run.evaluationRunId(), run.evaluationCaseId(), run.runId());
        int hitCount = result.hits().size();
        if (hitCount == 0) return new ActionResult("EVIDENCE:none", null);
        var first = result.hits().getFirst();
        return new ActionResult(hitCount == 1 ? "EVIDENCE:single" : "EVIDENCE:multiple",
                evidenceReference(first.startMs(), first.endMs(), result.hits().stream()
                        .flatMap(hit -> hit.segmentIds().stream()).toList()));
    }

    private String planReference(AgentDecisionPort.Plan plan) {
        String reference = "plan:v1:" + plan.action() + ":" + plan.query();
        if (reference.length() > 500) throw new AgentDecisionPort.InvalidDecisionException(
                new IllegalArgumentException("planner reference exceeds persistence limit"));
        return reference;
    }

    private String plannedQuery(List<AgentStep> steps) {
        String reference = steps.stream().filter(step -> "PLAN".equals(step.action()))
                .map(AgentStep::resultReference).reduce((first, second) -> second)
                .orElseThrow(() -> new AgentException(AgentException.Reason.INVALID_REQUEST));
        String prefix = "plan:v1:" + AgentDecisionPort.PlanAction.RETRIEVE + ":";
        if (reference == null || !reference.startsWith(prefix)) {
            throw new AgentException(AgentException.Reason.INVALID_REQUEST);
        }
        String query = reference.substring(prefix.length());
        try {
            return new AgentDecisionPort.Plan(AgentDecisionPort.SCHEMA_VERSION,
                    AgentDecisionPort.PlanAction.RETRIEVE, query, List.of("persisted-plan")).query();
        } catch (IllegalArgumentException exception) {
            throw new AgentException(AgentException.Reason.INVALID_REQUEST, exception);
        }
    }

    private ActionResult expand(OwnerContext owner, AgentRun run, List<AgentStep> steps) {
        if (steps.isEmpty()) throw new AgentException(AgentException.Reason.INVALID_REQUEST);
        String[] window = steps.getLast().resultReference().split(";", 2)[0].split(":");
        if (window.length != 3 || !"window".equals(window[0])) {
            throw new AgentException(AgentException.Reason.INVALID_REQUEST);
        }
        long from = Math.max(0, Long.parseLong(window[1]) - 30_000);
        long duration = contexts.get(owner, run.jobId()).durationMs();
        long to = Math.min(duration, Long.parseLong(window[2]) + 30_000);
        var result = contexts.segments(owner, run.jobId(), null, 20, from, to);
        return new ActionResult("WINDOW:" + result.data().size(), evidenceReference(
                from, to, result.data().stream().map(segment -> segment.segmentId()).toList()));
    }

    private ActionResult answer(OwnerContext owner, AgentRun run, List<AgentStep> steps) {
        String key = "agent-" + run.runId() + "-answer-" + (run.answerAttemptCount() + 1);
        LinkedHashSet<UUID> segmentIds = new LinkedHashSet<>();
        steps.stream().map(AgentStep::resultReference).filter(java.util.Objects::nonNull)
                .forEach(reference -> segmentIds.addAll(segmentIds(reference)));
        var selection = new AnswerApplicationService.EvidenceSelection(
                segmentIds.stream().limit(12).toList(), List.of(),
                run.versions().embeddingVersion(), run.versions().retrievalFormulaVersion(),
                VideoChunkBuilder.CHUNK_VERSION);
        AnswerStore.StoredAnswer answer = run.evaluationRunId() == null
                ? answers.askWithEvidence(owner, run.jobId(), run.goal(), key, selection, run.runId())
                : answers.askWithEvidence(owner, run.jobId(), run.goal(), key, selection,
                        run.evaluationRunId(), run.evaluationCaseId(), run.runId());
        return new ActionResult("ANSWER:" + answer.status(), "answer:" + answer.answerId());
    }

    private String evidenceReference(long from, long to, List<UUID> segmentIds) {
        String ids = segmentIds.stream().distinct().limit(12).map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
        return "window:" + from + ":" + to + ";segments:" + ids;
    }

    private List<UUID> segmentIds(String reference) {
        int marker = reference.indexOf(";segments:");
        if (marker < 0) return List.of();
        String encoded = reference.substring(marker + ";segments:".length());
        if (encoded.isBlank()) return List.of();
        try {
            return java.util.Arrays.stream(encoded.split(",")).map(UUID::fromString).toList();
        } catch (IllegalArgumentException exception) {
            throw new AgentException(AgentException.Reason.INVALID_REQUEST, exception);
        }
    }

    private String actionInput(AgentRun run, AgentTrace trace, AgentPolicy.Action action) {
        return switch (action) {
            case PLAN -> run.goal();
            case RETRIEVE -> plannedQuery(trace.steps());
            case EXPAND_WINDOW -> trace.steps().isEmpty() ? "" : trace.steps().getLast().resultReference();
            case GENERATE_AND_VERIFY -> trace.steps().stream().map(AgentStep::resultReference)
                    .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.joining("|"));
            default -> action.name();
        };
    }

    private String parameterSummary(AgentPolicy.Action action, String actionInput) {
        return "{\"action\":\"" + action.name() + "\",\"inputFingerprint\":\""
                + sha256(actionInput) + "\"}";
    }

    private boolean isTerminal(AgentPolicy.Action action) {
        return action == AgentPolicy.Action.FINISH || action == AgentPolicy.Action.REFUSE
                || action == AgentPolicy.Action.NEEDS_CLARIFICATION;
    }

    private String jsonCode(String value) {
        return value.replaceAll("[^A-Za-z0-9:_-]", "_");
    }

    private boolean hasUnknownOutcome(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof LanguageModelException model
                    && model.reason() == LanguageModelException.Reason.UNKNOWN_OUTCOME) {
                return true;
            }
        }
        return false;
    }

    private void requireRuntime() {
        if (policy == null || retrieval == null || contexts == null || answers == null) {
            throw new IllegalStateException("agent runtime dependencies are not configured");
        }
    }

    private record ActionResult(String observation, String reference) {
    }

    public enum GoalAnalysisState { DISABLED, PENDING, REQUESTED, RUNNING, ANSWERED, REFUSED, FAILED }

    public record GoalAnalysisResult(
            GoalAnalysisState state, AgentTrace trace, AnswerStore.StoredAnswer answer) {
    }

    private String normalizeGoal(String goal) {
        if (goal == null) throw new AgentException(AgentException.Reason.INVALID_REQUEST);
        String normalized = goal.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty() || normalized.length() > MAX_GOAL_LENGTH) {
            throw new AgentException(AgentException.Reason.INVALID_REQUEST);
        }
        return normalized;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
