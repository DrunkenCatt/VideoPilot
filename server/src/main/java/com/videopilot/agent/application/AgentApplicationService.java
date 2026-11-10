package com.videopilot.agent.application;

import com.videopilot.agent.domain.AgentModels.AgentBudget;
import com.videopilot.agent.domain.AgentModels.AgentRun;
import com.videopilot.agent.domain.AgentModels.AgentTrace;
import com.videopilot.agent.domain.AgentModels.AgentVersions;
import com.videopilot.agent.domain.AgentModels.RunStatus;
import com.videopilot.agent.port.AgentStore;
import com.videopilot.identity.application.OwnerContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class AgentApplicationService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final int MAX_GOAL_LENGTH = 1_000;

    private final AgentStore store;
    private final AgentVersions versions;
    private final Supplier<AgentBudget> budgets;
    private final Supplier<Instant> now;
    private final Supplier<UUID> ids;

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
    }

    public AgentRun create(OwnerContext owner, UUID jobId, String goal, String idempotencyKey) {
        String normalizedGoal = normalizeGoal(goal);
        if (jobId == null || idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new AgentException(AgentException.Reason.INVALID_REQUEST);
        }
        if (!store.ownsReadyJob(owner.ownerId(), jobId)) {
            throw new AgentException(AgentException.Reason.NOT_FOUND);
        }
        AgentBudget budget = budgets.get();
        String requestFingerprint = sha256(jobId + "|" + normalizedGoal + "|"
                + String.join("|", versions.values()) + "|"
                + budget.maxSteps() + "|" + budget.maxAnswerAttempts() + "|"
                + budget.maxInputTokens() + "|" + budget.maxOutputTokens() + "|"
                + budget.maxKnownCost());
        AgentRun requested = new AgentRun(
                ids.get(), owner.ownerId(), jobId, idempotencyKey, requestFingerprint,
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
