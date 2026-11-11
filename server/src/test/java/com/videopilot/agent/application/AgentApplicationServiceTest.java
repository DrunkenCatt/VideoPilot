package com.videopilot.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.agent.domain.AgentModels.AgentBudget;
import com.videopilot.agent.domain.AgentModels.AgentRun;
import com.videopilot.agent.domain.AgentModels.AgentTrace;
import com.videopilot.agent.domain.AgentModels.AgentVersions;
import com.videopilot.agent.port.AgentStore;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T14:00:00Z");
    private static final UUID OWNER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_OWNER = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID JOB = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID RUN = UUID.fromString("30000000-0000-0000-0000-000000000001");

    @Test
    void createsOneOwnerScopedRunWithFrozenVersionsAndBudget() {
        FakeAgentStore store = new FakeAgentStore();
        store.readyJobs.put(JOB, new OwnerId(OWNER));
        AgentApplicationService service = service(store);

        AgentRun created = service.create(
                owner(OWNER), JOB, "  解释 String 的用途  ", "agent-intent-1");
        AgentRun duplicate = service.create(
                owner(OWNER), JOB, "解释 String 的用途", "agent-intent-1");

        assertThat(duplicate.runId()).isEqualTo(created.runId());
        assertThat(created.runId()).isEqualTo(RUN);
        assertThat(created.goal()).isEqualTo("解释 String 的用途");
        assertThat(created.status().name()).isEqualTo("REQUESTED");
        assertThat(created.versions()).isEqualTo(versions());
        assertThat(created.budget()).isEqualTo(budget());
        assertThat(created.version()).isZero();
        assertThat(store.created).hasSize(1);
    }

    @Test
    void rejectsIdempotencyReuseWithDifferentIntentBeforeCreatingAnotherRun() {
        FakeAgentStore store = new FakeAgentStore();
        store.readyJobs.put(JOB, new OwnerId(OWNER));
        AgentApplicationService service = service(store);
        service.create(owner(OWNER), JOB, "问题一", "agent-intent-1");

        assertThatThrownBy(() -> service.create(owner(OWNER), JOB, "问题二", "agent-intent-1"))
                .isInstanceOf(AgentException.class)
                .extracting(value -> ((AgentException) value).reason())
                .isEqualTo(AgentException.Reason.IDEMPOTENCY_KEY_REUSED);
        assertThat(store.created).hasSize(1);
    }

    @Test
    void keepsRequestIdempotentWhenOnlyGeneratedDeadlineChanges() {
        FakeAgentStore store = new FakeAgentStore();
        store.readyJobs.put(JOB, new OwnerId(OWNER));
        AtomicInteger deadlines = new AtomicInteger();
        AgentApplicationService service = new AgentApplicationService(
                store, versions(), () -> new AgentBudget(
                        6, 2, 12_000, 6_000, new BigDecimal("1.00"),
                        NOW.plusSeconds(180 + deadlines.getAndIncrement())),
                () -> NOW, () -> RUN);

        AgentRun first = service.create(owner(OWNER), JOB, "问题", "agent-intent-1");
        AgentRun duplicate = service.create(owner(OWNER), JOB, "问题", "agent-intent-1");

        assertThat(duplicate.runId()).isEqualTo(first.runId());
        assertThat(store.created).hasSize(1);
    }

    @Test
    void hidesRunsAndJobsOwnedByAnotherUser() {
        FakeAgentStore store = new FakeAgentStore();
        store.readyJobs.put(JOB, new OwnerId(OWNER));
        AgentApplicationService service = service(store);
        service.create(owner(OWNER), JOB, "问题", "agent-intent-1");

        assertThatThrownBy(() -> service.get(owner(OTHER_OWNER), RUN))
                .isInstanceOf(AgentException.class)
                .extracting(value -> ((AgentException) value).reason())
                .isEqualTo(AgentException.Reason.NOT_FOUND);
        assertThatThrownBy(() -> service.create(
                owner(OTHER_OWNER), JOB, "越权问题", "agent-intent-2"))
                .isInstanceOf(AgentException.class)
                .extracting(value -> ((AgentException) value).reason())
                .isEqualTo(AgentException.Reason.NOT_FOUND);
    }

    private AgentApplicationService service(FakeAgentStore store) {
        return new AgentApplicationService(store, versions(), this::budget, () -> NOW, () -> RUN);
    }

    private AgentVersions versions() {
        return new AgentVersions(
                "agent-policy-v1", "answer-evidence-v2", "deepseek-v4-flash",
                "siliconflow-bge-m3-1024-v1", "hybrid-v1", "answer-gate-v1", "m4-limits-v3");
    }

    private AgentBudget budget() {
        return new AgentBudget(6, 2, 12_000, 6_000, new BigDecimal("1.00"), NOW.plusSeconds(180));
    }

    private OwnerContext owner(UUID value) {
        return new OwnerContext(new OwnerId(value));
    }

    private static final class FakeAgentStore implements AgentStore {

        private final Map<UUID, OwnerId> readyJobs = new HashMap<>();
        private final Map<String, AgentRun> created = new HashMap<>();

        @Override
        public boolean ownsReadyJob(OwnerId ownerId, UUID jobId) {
            return ownerId.equals(readyJobs.get(jobId));
        }

        @Override
        public CreateResult create(OwnerId ownerId, AgentRun run) {
            String key = ownerId.value() + "|" + run.idempotencyKey();
            AgentRun existing = created.get(key);
            if (existing != null) {
                return new CreateResult(existing, !existing.requestFingerprint().equals(run.requestFingerprint()));
            }
            created.put(key, run);
            return new CreateResult(run, false);
        }

        @Override
        public Optional<AgentTrace> findTrace(OwnerId ownerId, UUID runId) {
            return created.values().stream()
                    .filter(run -> run.ownerId().equals(ownerId) && run.runId().equals(runId))
                    .findFirst()
                    .map(run -> new AgentTrace(run, java.util.List.of(), java.util.List.of()));
        }

        @Override
        public Optional<com.videopilot.agent.domain.AgentModels.ClaimedRun> claimNext(
                String leaseId, Instant leaseUntil, Instant now) {
            return Optional.empty();
        }

        @Override
        public void appendStep(
                String leaseId, UUID runId, long expectedVersion,
                com.videopilot.agent.domain.AgentModels.AgentStep step) {
            throw new AssertionError("application create/get tests must not advance a run");
        }

        @Override
        public long beginToolCall(String leaseId, UUID runId, long expectedVersion,
                com.videopilot.agent.domain.AgentModels.ToolCall call) {
            throw new AssertionError("application create/get tests must not begin a tool call");
        }

        @Override
        public void completeToolCallAndStep(String leaseId, UUID runId, long expectedVersion,
                com.videopilot.agent.domain.AgentModels.ToolCall completedCall,
                com.videopilot.agent.domain.AgentModels.AgentStep step, boolean answerAttempt) {
            throw new AssertionError("application create/get tests must not complete a tool call");
        }

        @Override
        public void failToolCallAndTerminate(String leaseId, UUID runId, long expectedVersion,
                UUID toolCallId,
                com.videopilot.agent.domain.AgentModels.ToolCallStatus callStatus,
                String terminationReason, Instant completedAt) {
            throw new AssertionError("application create/get tests must not fail a tool call");
        }

        @Override
        public void terminate(
                String leaseId, UUID runId, long expectedVersion,
                com.videopilot.agent.domain.AgentModels.RunStatus status,
                String terminationReason, Instant completedAt) {
            throw new AssertionError("application create/get tests must not terminate a run");
        }
    }
}
