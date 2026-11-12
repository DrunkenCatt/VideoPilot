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
import com.videopilot.agent.port.AgentStore;
import com.videopilot.answer.application.AnswerApplicationService;
import com.videopilot.answer.port.AnswerStore;
import com.videopilot.answer.port.LanguageModelException;
import com.videopilot.context.application.VideoContextQueryApplicationService;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class AgentApplicationService {

    public static final String POLICY_VERSION = "agent-policy-v1";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final int MAX_GOAL_LENGTH = 1_000;
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

    private final AgentStore store;
    private final AgentVersions versions;
    private final Supplier<AgentBudget> budgets;
    private final Supplier<Instant> now;
    private final Supplier<UUID> ids;
    private final AgentPolicy policy;
    private final RetrievalApplicationService retrieval;
    private final VideoContextQueryApplicationService contexts;
    private final AnswerApplicationService answers;

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
        this.retrieval = null;
        this.contexts = null;
        this.answers = null;
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
        this.store = store;
        this.versions = versions;
        this.budgets = budgets;
        this.now = now;
        this.ids = ids;
        this.policy = policy;
        this.retrieval = retrieval;
        this.contexts = contexts;
        this.answers = answers;
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
        Instant startedAt = now.get();
        ToolCall intent = new ToolCall(
                toolCallId, run.runId(), stepNumber, action,
                run.runId() + ":" + stepNumber + ":" + action,
                sha256(run.goal() + "|" + action + "|" + stepNumber),
                ToolCallStatus.RUNNING, parameterSummary(run, decision.action()),
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
        return null;
    }

    private ActionResult execute(AgentRun run, AgentTrace trace, AgentPolicy.Action action) {
        OwnerContext owner = new OwnerContext(run.ownerId());
        return switch (action) {
            case RETRIEVE -> retrieve(owner, run);
            case EXPAND_WINDOW -> expand(owner, run, trace.steps());
            case GENERATE_AND_VERIFY -> answer(owner, run);
            default -> throw new AgentException(AgentException.Reason.INVALID_REQUEST);
        };
    }

    private ActionResult retrieve(OwnerContext owner, AgentRun run) {
        var result = run.evaluationRunId() == null
                ? retrieval.retrieve(owner, run.jobId(), run.goal(), 5, run.runId())
                : retrieval.retrieve(owner, run.jobId(), run.goal(), 5,
                        run.evaluationRunId(), run.evaluationCaseId(), run.runId());
        int hitCount = result.hits().size();
        if (hitCount == 0) return new ActionResult("EVIDENCE:none", null);
        var first = result.hits().getFirst();
        return new ActionResult(hitCount == 1 ? "EVIDENCE:single" : "EVIDENCE:multiple",
                "window:" + first.startMs() + ":" + first.endMs());
    }

    private ActionResult expand(OwnerContext owner, AgentRun run, List<AgentStep> steps) {
        if (steps.isEmpty()) throw new AgentException(AgentException.Reason.INVALID_REQUEST);
        String[] window = steps.getLast().resultReference().split(":");
        if (window.length != 3 || !"window".equals(window[0])) {
            throw new AgentException(AgentException.Reason.INVALID_REQUEST);
        }
        long from = Math.max(0, Long.parseLong(window[1]) - 30_000);
        long duration = contexts.get(owner, run.jobId()).durationMs();
        long to = Math.min(duration, Long.parseLong(window[2]) + 30_000);
        var result = contexts.segments(owner, run.jobId(), null, 20, from, to);
        return new ActionResult("WINDOW:" + result.data().size(), "window:" + from + ":" + to);
    }

    private ActionResult answer(OwnerContext owner, AgentRun run) {
        String key = "agent-" + run.runId() + "-answer-" + (run.answerAttemptCount() + 1);
        AnswerStore.StoredAnswer answer = run.evaluationRunId() == null
                ? answers.ask(owner, run.jobId(), run.goal(), key, run.runId())
                : answers.ask(owner, run.jobId(), run.goal(), key,
                        run.evaluationRunId(), run.evaluationCaseId(), run.runId());
        return new ActionResult("ANSWER:" + answer.status(), "answer:" + answer.answerId());
    }

    private String parameterSummary(AgentRun run, AgentPolicy.Action action) {
        return "{\"action\":\"" + action.name() + "\",\"inputFingerprint\":\""
                + sha256(run.goal()) + "\"}";
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
