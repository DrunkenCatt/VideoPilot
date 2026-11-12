package com.videopilot.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videopilot.agent.application.AgentApplicationService;
import com.videopilot.agent.application.AgentException;
import com.videopilot.agent.application.AgentApplicationService.GoalAnalysisResult;
import com.videopilot.agent.application.AgentApplicationService.GoalAnalysisState;
import com.videopilot.agent.domain.AgentModels.AgentBudget;
import com.videopilot.agent.domain.AgentModels.AgentRun;
import com.videopilot.agent.domain.AgentModels.AgentTrace;
import com.videopilot.agent.domain.AgentModels.AgentVersions;
import com.videopilot.agent.domain.AgentModels.RunStatus;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.infrastructure.security.IdentityPrincipal;
import com.videopilot.infrastructure.web.contract.M6ApiContracts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AgentControllerTest {

    @Test
    void createsAndReadsOwnerScopedRedactedRunTrace() {
        AgentApplicationService service = mock(AgentApplicationService.class);
        IdentityPrincipal principal = mock(IdentityPrincipal.class);
        OwnerContext owner = new OwnerContext(new OwnerId(UUID.randomUUID()));
        when(principal.ownerContext()).thenReturn(owner);
        UUID jobId = UUID.randomUUID();
        AgentRun run = run(owner, jobId);
        when(service.create(owner, jobId, "解释课程", "agent-intent-1")).thenReturn(run);
        when(service.get(owner, run.runId())).thenReturn(new AgentTrace(run, List.of(), List.of()));
        AgentController controller = new AgentController(service);

        var created = controller.create(principal, jobId, "agent-intent-1",
                new M6ApiContracts.CreateAgentRunRequest("解释课程"));
        var loaded = controller.get(principal, run.runId());

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(created.getBody().runId()).isEqualTo(run.runId());
        assertThat(loaded.runId()).isEqualTo(run.runId());
        assertThat(loaded.status()).isEqualTo(M6ApiContracts.AgentRunStatus.REQUESTED);
        assertThat(loaded.budget().maxSteps()).isEqualTo(6);
        assertThat(loaded.steps()).isEmpty();
        assertThat(loaded.toolCalls()).isEmpty();
    }

    @Test
    void mapsAgentFailuresToStableApiErrors() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        assertThat(handler.handleAgentFailure(new AgentException(AgentException.Reason.NOT_FOUND))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handleAgentFailure(
                new AgentException(AgentException.Reason.IDEMPOTENCY_KEY_REUSED)).getBody().code())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void returnsTheGoalAnalysisRunAndItsExactPublishedAnswer() {
        AgentApplicationService service = mock(AgentApplicationService.class);
        IdentityPrincipal principal = mock(IdentityPrincipal.class);
        OwnerContext owner = new OwnerContext(new OwnerId(UUID.randomUUID()));
        when(principal.ownerContext()).thenReturn(owner);
        UUID jobId = UUID.randomUUID();
        AgentRun run = run(owner, jobId);
        var answer = new com.videopilot.answer.port.AnswerStore.StoredAnswer(
                1, UUID.randomUUID(), "ANSWERED", run.goal(), null, List.of(),
                "prompt-v1", "model-v1", "embedding-v1", "formula-v1", "chunk-v1",
                List.of(), Instant.parse("2026-11-10T16:00:01Z"));
        when(service.goalAnalysisResult(owner, jobId)).thenReturn(new GoalAnalysisResult(
                GoalAnalysisState.ANSWERED, new AgentTrace(run, List.of(), List.of()), answer));
        AgentController controller = new AgentController(service);

        var response = controller.goalAnalysisResult(principal, jobId);

        assertThat(response.state()).isEqualTo(M6ApiContracts.GoalAnalysisState.ANSWERED);
        assertThat(response.run().runId()).isEqualTo(run.runId());
        assertThat(response.answer().answerId()).isEqualTo(answer.answerId());
    }

    private AgentRun run(OwnerContext owner, UUID jobId) {
        Instant created = Instant.parse("2026-11-10T16:00:00Z");
        return new AgentRun(
                UUID.randomUUID(), owner.ownerId(), jobId, "agent-intent-1", "a".repeat(64),
                "解释课程", RunStatus.REQUESTED,
                new AgentVersions("agent-policy-v1", "prompt-v1", "model-v1", "embedding-v1",
                        "hybrid-v1", "gate-v1", "limits-v1"),
                new AgentBudget(6, 2, 12_000, 6_000, new BigDecimal("1.00"), created.plusSeconds(180)),
                0, 0, 0, 0, BigDecimal.ZERO, 0, null, 0, created, null);
    }
}
